package com.actors

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR CELL: Internal Actor Runtime
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Specs: ActorLifecycle.tla + ActorMailbox.tla
 *
 * The ActorCell is the runtime container for an actor. It:
 *   1. Manages the lifecycle state machine (ActorLifecycle.tla)
 *   2. Runs the message processing loop on a coroutine
 *   3. Applies supervisor strategy on failures
 *   4. Owns the mailbox (ActorMailbox.tla)
 *
 * NOT exposed to users — they interact via [ActorRef].
 *
 * One coroutine per actor ensures:
 *   - Messages are processed sequentially (no internal concurrency)
 *   - State mutations in behavior are thread-safe without locks
 *   - Backpressure via bounded mailbox channel
 */
@TlaSpec("ActorLifecycle")
class ActorCell<M : Any>(
    val name: String,
    private val initialBehavior: Behavior<M>,
    internal val mailbox: Mailbox<M>,
    private val supervisorStrategy: SupervisorStrategy
) {
    companion object {
        private val log = LoggerFactory.getLogger(ActorCell::class.java)
    }

    @TlaVariable("actorState")
    private val stateRef = AtomicReference(ActorState.CREATED)

    @TlaVariable("restartCount")
    private val restartCounter = AtomicInteger(0)

    @TlaVariable("processed")
    private val processedCounter = AtomicInteger(0)

    private var currentBehavior: Behavior<M> = initialBehavior
    private var job: Job? = null

    val state: ActorState get() = stateRef.get()
    val restartCount: Int get() = restartCounter.get()
    val processedCount: Int get() = processedCounter.get()

    /**
     * Start the actor within a coroutine scope.
     * Launches the message processing loop.
     *
     * TLA+ action: Start(a) → BecomeRunning(a)
     */
    @TlaAction("Start|BecomeRunning")
    fun start(scope: CoroutineScope) {
        check(stateRef.compareAndSet(ActorState.CREATED, ActorState.STARTING)) {
            "Cannot start actor '$name' in state ${stateRef.get()}"
        }

        job = scope.launch(CoroutineName("actor-$name")) {
            try {
                // Lifecycle: preStart hook
                if (currentBehavior is LifecycleBehavior<M>) {
                    (currentBehavior as LifecycleBehavior<M>).onStart()
                }

                stateRef.set(ActorState.RUNNING)
                log.debug("Actor '{}' is now RUNNING", name)

                messageLoop()
            } catch (e: CancellationException) {
                log.debug("Actor '{}' cancelled", name)
            } finally {
                performStop()
            }
        }
    }

    /**
     * Start inline — for temporary actors (e.g., reply refs).
     * Launches on GlobalScope with limited lifecycle.
     */
    internal fun startInline() {
        stateRef.set(ActorState.STARTING)
        @OptIn(DelicateCoroutinesApi::class)
        start(GlobalScope)
    }

    /**
     * The core message processing loop.
     *
     * TLA+ action: ProcessMessage(a) — called for each successfully processed message
     */
    @TlaAction("ProcessMessage")
    private suspend fun messageLoop() {
        while (stateRef.get() == ActorState.RUNNING) {
            try {
                val message = mailbox.receive()
                val nextBehavior = currentBehavior.onMessage(message)
                processedCounter.incrementAndGet()

                when {
                    Behavior.isStopped(nextBehavior) -> {
                        log.debug("Actor '{}' behavior returned Stopped", name)
                        return
                    }
                    Behavior.isSame(nextBehavior) -> {
                        // Keep current behavior
                    }
                    else -> {
                        currentBehavior = nextBehavior
                    }
                }
            } catch (e: CancellationException) {
                throw e // Don't catch cancellation
            } catch (e: Exception) {
                handleFailure(e)
            }
        }
    }

    /**
     * Apply supervisor strategy on failure.
     *
     * TLA+ actions: Fail(a), FailPermanent(a), Restart(a)
     */
    @TlaAction("Fail|FailPermanent|Restart")
    private suspend fun handleFailure(error: Throwable) {
        val directive = supervisorStrategy.decide(error, restartCounter.get())
        log.warn("Actor '{}' failed: {} → {}", name, error.message, directive)

        when (directive) {
            SupervisorStrategy.Directive.RESUME -> {
                // Skip failed message, continue with current behavior
                log.debug("Actor '{}' resuming after failure", name)
            }
            SupervisorStrategy.Directive.RESTART -> {
                stateRef.set(ActorState.RESTARTING)
                restartCounter.incrementAndGet()
                log.info("Actor '{}' restarting (attempt {}/{})", name,
                    restartCounter.get(), supervisorStrategy.maxRestarts)

                // Run postStop on old behavior
                if (currentBehavior is LifecycleBehavior<M>) {
                    try {
                        (currentBehavior as LifecycleBehavior<M>).onStop()
                    } catch (e: Exception) {
                        log.error("Actor '{}' postStop failed during restart", name, e)
                    }
                }

                // Reset to initial behavior
                currentBehavior = initialBehavior

                // Run preStart on new behavior
                if (currentBehavior is LifecycleBehavior<M>) {
                    (currentBehavior as LifecycleBehavior<M>).onStart()
                }

                stateRef.set(ActorState.RUNNING)
                log.debug("Actor '{}' restarted, now RUNNING", name)
            }
            SupervisorStrategy.Directive.STOP -> {
                log.info("Actor '{}' stopping due to failure", name)
                return // Exit messageLoop, finally block calls performStop
            }
            SupervisorStrategy.Directive.ESCALATE -> {
                log.info("Actor '{}' escalating failure", name)
                throw error // Propagate to parent (future: supervision tree)
            }
        }
    }

    /**
     * Graceful stop: signal the actor to stop processing.
     *
     * TLA+ action: GracefulStop(a) → CompleteStopping(a)
     */
    @TlaAction("GracefulStop")
    fun stop() {
        if (stateRef.get() == ActorState.STOPPED) return

        stateRef.set(ActorState.STOPPING)
        mailbox.close()
        job?.cancel()
    }

    /**
     * Internal: perform shutdown cleanup.
     *
     * TLA+ action: CompleteStopping(a)
     */
    @TlaAction("CompleteStopping")
    private suspend fun performStop() {
        stateRef.set(ActorState.STOPPING)

        if (currentBehavior is LifecycleBehavior<M>) {
            try {
                (currentBehavior as LifecycleBehavior<M>).onStop()
            } catch (e: Exception) {
                log.error("Actor '{}' postStop failed", name, e)
            }
        }

        if (!mailbox.isClosed) {
            mailbox.close()
        }

        stateRef.set(ActorState.STOPPED)
        log.debug("Actor '{}' is now STOPPED (processed: {}, restarts: {})",
            name, processedCounter.get(), restartCounter.get())
    }

    /**
     * Suspend until the actor has stopped.
     */
    suspend fun awaitTermination() {
        job?.join()
    }

    // ─── Invariant Checks (TLA+ → Kotlin bridge) ────────────────

    @TlaInvariant("RestartBudgetRespected")
    fun checkRestartBudget(): Boolean = restartCounter.get() <= supervisorStrategy.maxRestarts

    @TlaInvariant("StoppedNotAlive")
    fun checkStoppedNotAlive(): Boolean =
        !(stateRef.get() == ActorState.STOPPED && stateRef.get().isAlive())

    @TlaInvariant("AliveConsistency")
    fun checkAliveConsistency(): Boolean =
        !stateRef.get().isAlive() || stateRef.get() != ActorState.STOPPED

    fun checkAllInvariants() {
        check(checkRestartBudget()) {
            "Invariant violated: RestartBudgetRespected — restarts=${restartCounter.get()} > max=${supervisorStrategy.maxRestarts}"
        }
        check(checkStoppedNotAlive()) { "Invariant violated: StoppedNotAlive" }
        check(checkAliveConsistency()) { "Invariant violated: AliveConsistency" }
        mailbox.checkAllInvariants()
    }

    override fun toString(): String = "ActorCell($name, state=${stateRef.get()})"
}
