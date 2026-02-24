package com.actors

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR CELL: Internal Actor Runtime
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Specs: ActorLifecycle.tla + ActorMailbox.tla
 *
 * The ActorCell is the runtime container for an actor. It manages:
 *   1. Lifecycle state machine (ActorLifecycle.tla)
 *   2. Message processing loop with signal support
 *   3. Supervisor strategy on failures
 *   4. Mailbox (ActorMailbox.tla)
 *   5. Parent-child hierarchy (supervision tree)
 *   6. DeathWatch (watchers / watching)
 *   7. Signal delivery (PreStart, PostStop, Terminated, ChildFailed)
 *
 * NOT exposed to users — they interact via [ActorRef] and [ActorContext].
 *
 * Architecture:
 * ```
 *                    ActorSystem (root scope)
 *                         │
 *              ┌──────────┼──────────┐
 *              ▼          ▼          ▼
 *          ActorCell   ActorCell   ActorCell   (top-level actors)
 *            │                       │
 *        ┌───┼───┐               ┌───┘
 *        ▼       ▼               ▼
 *    ActorCell  ActorCell    ActorCell          (child actors)
 * ```
 *
 * One coroutine per actor ensures:
 *   - Messages are processed sequentially (no internal concurrency)
 *   - State mutations in behavior are thread-safe without locks
 *   - Backpressure via bounded mailbox channel
 *   - Structured concurrency: parent stop → children stop
 */
@TlaSpec("ActorLifecycle|ActorHierarchy|DeathWatch")
class ActorCell<M : Any>(
    val name: String,
    private val initialBehavior: Behavior<M>,
    internal val mailbox: Mailbox<M>,
    private val supervisorStrategy: SupervisorStrategy,
    private val parent: ActorCell<*>? = null
) {
    companion object {
        private val log = LoggerFactory.getLogger(ActorCell::class.java)
    }

    // ─── Lifecycle State ─────────────────────────────────────────

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

    // ─── Actor Identity ──────────────────────────────────────────

    /** The ActorRef for this cell — created eagerly. */
    val ref: ActorRef<M> = ActorRef(name, mailbox, this)

    /** The context passed to behaviors — initialized during start. */
    internal lateinit var context: ActorContext<M>

    /** Reference to the ActorSystem — set during start. */
    internal lateinit var system: ActorSystem

    /** The coroutine scope for this actor's children. */
    private lateinit var childScope: CoroutineScope

    // ─── Hierarchy: Children ─────────────────────────────────────

    /** Direct children of this actor. Key = local name (not full path). */
    @TlaVariable("children")
    private val children = ConcurrentHashMap<String, ActorCell<*>>()

    /** Read-only view of child refs for ActorContext. */
    internal val childRefs: Set<ActorRef<*>>
        get() = children.values.map { it.ref }.toSet()

    // ─── DeathWatch ──────────────────────────────────────────────

    /** Actors watching this actor (notified on termination). */
    @TlaVariable("watchers")
    private val watchers = CopyOnWriteArraySet<ActorCell<*>>()

    /** Actors this actor is watching. */
    @TlaVariable("watching")
    private val watching = CopyOnWriteArraySet<ActorCell<*>>()

    // ─── Signal Channel ──────────────────────────────────────────

    /**
     * Internal channel for asynchronous signals (Terminated, ChildFailed).
     * Unbounded because signals should never be dropped.
     * Signals are prioritized over messages in the processing loop.
     */
    internal val signalChannel = Channel<Signal>(Channel.UNLIMITED)

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start the actor within a coroutine scope.
     * Launches the message processing loop.
     *
     * TLA+ action: Start(a) → BecomeRunning(a)
     */
    @TlaAction("Start|BecomeRunning")
    fun start(scope: CoroutineScope, actorSystem: ActorSystem) {
        check(stateRef.compareAndSet(ActorState.CREATED, ActorState.STARTING)) {
            "Cannot start actor '$name' in state ${stateRef.get()}"
        }

        system = actorSystem

        // Create child scope with SupervisorJob for fault isolation
        val childSupervisor = SupervisorJob(scope.coroutineContext.job)
        childScope = CoroutineScope(scope.coroutineContext + childSupervisor + CoroutineName("actor-$name-children"))

        // Create context (self ref already available)
        context = ActorContext(ref, actorSystem, this)

        job = scope.launch(CoroutineName("actor-$name")) {
            try {
                // Unwrap SetupBehavior: run factory to initialize
                if (currentBehavior is SetupBehavior<M>) {
                    currentBehavior = (currentBehavior as SetupBehavior<M>).factory(context)
                }

                // Deliver PreStart signal
                deliverSignal(Signal.PreStart)

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
    internal fun startInline(actorSystem: ActorSystem) {
        system = actorSystem
        stateRef.set(ActorState.STARTING)
        context = ActorContext(ref, actorSystem, this)
        childScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        @OptIn(DelicateCoroutinesApi::class)
        val scope = GlobalScope
        job = scope.launch(CoroutineName("actor-$name")) {
            try {
                if (currentBehavior is SetupBehavior<M>) {
                    currentBehavior = (currentBehavior as SetupBehavior<M>).factory(context)
                }
                stateRef.set(ActorState.RUNNING)
                messageLoop()
            } catch (e: CancellationException) {
                // Expected for reply refs
            } finally {
                performStop()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE PROCESSING
    // ═══════════════════════════════════════════════════════════════

    /**
     * The core message processing loop.
     * Uses `select` to wait on both the message mailbox and the signal channel,
     * prioritizing signals over messages.
     *
     * TLA+ action: ProcessMessage(a)
     */
    @TlaAction("ProcessMessage")
    private suspend fun messageLoop() {
        while (stateRef.get() == ActorState.RUNNING) {
            try {
                // Priority: drain all pending signals first
                drainSignals()

                // Wait for next message or signal
                select<Unit> {
                    signalChannel.onReceive { signal ->
                        val result = deliverSignal(signal)
                        handleBehaviorResult(result)
                    }
                    mailbox.channel.onReceive { message ->
                        mailbox.onReceived()
                        val nextBehavior = currentBehavior.onMessage(context, message)
                        processedCounter.incrementAndGet()
                        handleBehaviorResult(nextBehavior)
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
     * Drain all pending signals without suspending.
     * Signals have priority over regular messages.
     */
    private suspend fun drainSignals() {
        while (true) {
            val signal = signalChannel.tryReceive().getOrNull() ?: break
            val result = deliverSignal(signal)
            handleBehaviorResult(result)
        }
    }

    /**
     * Deliver a signal to the current behavior.
     * If the behavior is a [SignalBehavior], invokes its signal handler.
     * Otherwise, the signal is ignored (default behavior).
     */
    private suspend fun deliverSignal(signal: Signal): Behavior<M> {
        return if (currentBehavior is SignalBehavior<M>) {
            (currentBehavior as SignalBehavior<M>).onSignal(context, signal)
        } else {
            Behavior.same()
        }
    }

    /**
     * Process the behavior result (same, stopped, or new behavior).
     */
    private fun handleBehaviorResult(nextBehavior: Behavior<M>) {
        when {
            Behavior.isStopped(nextBehavior) -> {
                log.debug("Actor '{}' behavior returned Stopped", name)
                stateRef.set(ActorState.STOPPING)
                mailbox.close()
                job?.cancel()
            }
            Behavior.isSame(nextBehavior) -> {
                // Keep current behavior
            }
            else -> {
                currentBehavior = nextBehavior
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FAULT TOLERANCE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Apply supervisor strategy on failure.
     *
     * TLA+ actions: Fail(a), FailPermanent(a), Restart(a)
     */
    @TlaAction("Fail|FailPermanent|Restart")
    private suspend fun handleFailure(error: Throwable) {
        // Notify parent of child failure (if we have a parent)
        parent?.onChildFailed(this, error)

        val directive = supervisorStrategy.decide(error, restartCounter.get())
        log.warn("Actor '{}' failed: {} → {}", name, error.message, directive)

        when (directive) {
            SupervisorStrategy.Directive.RESUME -> {
                log.debug("Actor '{}' resuming after failure", name)
            }
            SupervisorStrategy.Directive.RESTART -> {
                stateRef.set(ActorState.RESTARTING)
                restartCounter.incrementAndGet()
                log.info("Actor '{}' restarting (attempt {}/{})", name,
                    restartCounter.get(), supervisorStrategy.maxRestarts)

                // Deliver PostStop to old behavior
                deliverSignal(Signal.PostStop)

                // Stop all children before restart
                stopAllChildren()

                // Reset to initial behavior
                currentBehavior = initialBehavior

                // Unwrap SetupBehavior again
                if (currentBehavior is SetupBehavior<M>) {
                    currentBehavior = (currentBehavior as SetupBehavior<M>).factory(context)
                }

                // Deliver PreStart to new behavior
                deliverSignal(Signal.PreStart)

                stateRef.set(ActorState.RUNNING)
                log.debug("Actor '{}' restarted, now RUNNING", name)
            }
            SupervisorStrategy.Directive.STOP -> {
                log.info("Actor '{}' stopping due to failure", name)
                return // Exit messageLoop, finally block calls performStop
            }
            SupervisorStrategy.Directive.ESCALATE -> {
                log.info("Actor '{}' escalating failure", name)
                throw error // Propagate to parent scope
            }
        }
    }

    /**
     * Called by a child cell when it fails.
     * Delivers ChildFailed signal to this actor.
     */
    internal fun onChildFailed(child: ActorCell<*>, cause: Throwable) {
        val signal = Signal.ChildFailed(child.ref, cause)
        signalChannel.trySend(signal)
    }

    // ═══════════════════════════════════════════════════════════════
    // CHILDREN (SUPERVISION TREE)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Spawn a child actor within this actor's supervision tree.
     * Called by [ActorContext.spawn].
     *
     * TLA+ action: SpawnChild(p, c) in ActorHierarchy.tla
     */
    @TlaAction("SpawnChild")
    fun <C : Any> spawnChild(
        localName: String,
        behavior: Behavior<C>,
        mailboxCapacity: Int = Mailbox.DEFAULT_CAPACITY,
        supervisorStrategy: SupervisorStrategy = SupervisorStrategy.restart()
    ): ActorRef<C> {
        check(stateRef.get().isAlive()) { "Cannot spawn child in dead actor '$name'" }
        check(!children.containsKey(localName)) { "Child '$localName' already exists in actor '$name'" }

        val childFullName = "$name/$localName"
        val childMailbox = Mailbox<C>(mailboxCapacity)
        val childCell = ActorCell(
            name = childFullName,
            initialBehavior = behavior,
            mailbox = childMailbox,
            supervisorStrategy = supervisorStrategy,
            parent = this
        )

        children[localName] = childCell
        childCell.start(childScope, system)
        log.debug("Actor '{}' spawned child '{}'", name, childFullName)

        return childCell.ref
    }

    /**
     * Stop a specific child actor.
     * Called by [ActorContext.stop].
     *
     * TLA+ action: InitiateStop(a) in ActorHierarchy.tla
     */
    @TlaAction("InitiateStop")
    fun stopChild(childRef: ActorRef<*>) {
        val localName = childRef.name.substringAfterLast('/')
        val child = children[localName]
            ?: throw IllegalArgumentException("'${childRef.name}' is not a child of '$name'")
        child.stop()
    }

    /**
     * Stop all children. Called during actor restart and shutdown.
     *
     * TLA+ action: CascadeStop(p, c) in ActorHierarchy.tla
     */
    @TlaAction("CascadeStop")
    private suspend fun stopAllChildren() {
        children.values.forEach { it.stop() }
        children.values.forEach { it.awaitTermination() }
        children.clear()
    }

    /**
     * Called when a child stops (normally or from failure).
     * Removes it from the children map.
     */
    internal fun onChildStopped(child: ActorCell<*>) {
        val localName = child.name.substringAfterLast('/')
        children.remove(localName)
    }

    // ═══════════════════════════════════════════════════════════════
    // DEATHWATCH
    // ═══════════════════════════════════════════════════════════════

    /**
     * Watch another actor for termination.
     * When [other] stops, this actor receives [Signal.Terminated].
     *
     * TLA+ action: Watch(w, target) in DeathWatch.tla
     */
    @TlaAction("Watch")
    fun watch(other: ActorCell<*>) {
        other.watchers.add(this)
        watching.add(other)
        log.debug("Actor '{}' watching '{}'", name, other.name)

        // If already dead, deliver immediately
        if (other.state == ActorState.STOPPED) {
            signalChannel.trySend(Signal.Terminated(other.ref))
        }
    }

    /**
     * Stop watching another actor.
     *
     * TLA+ action: Unwatch(w, target) in DeathWatch.tla
     */
    @TlaAction("Unwatch")
    fun unwatch(other: ActorCell<*>) {
        other.watchers.remove(this)
        watching.remove(other)
    }

    /**
     * Notify all watchers that this actor has terminated.
     *
     * TLA+ action: Die(a) in DeathWatch.tla — delivers Terminated to watchers
     */
    @TlaAction("Die")
    private fun notifyWatchers() {
        watchers.forEach { watcher ->
            watcher.signalChannel.trySend(Signal.Terminated(ref))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STOP & CLEANUP
    // ═══════════════════════════════════════════════════════════════

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
        signalChannel.close()
        job?.cancel()
    }

    /**
     * Internal: perform shutdown cleanup.
     * Order: stop children → PostStop signal → notify watchers → cleanup.
     *
     * TLA+ action: CompleteStopping(a)
     */
    @TlaAction("CompleteStopping")
    private suspend fun performStop() {
        stateRef.set(ActorState.STOPPING)

        // 1. Stop all children first (cascading stop)
        stopAllChildren()

        // 2. Deliver PostStop signal
        deliverSignal(Signal.PostStop)

        // 3. Notify watchers (delivers Terminated signal)
        notifyWatchers()

        // 4. Unwatch everything we were watching
        watching.forEach { it.watchers.remove(this) }
        watching.clear()

        // 5. Notify parent
        parent?.onChildStopped(this)

        // 6. Close channels
        if (!mailbox.isClosed) {
            mailbox.close()
        }
        if (!signalChannel.isClosedForSend) {
            signalChannel.close()
        }

        // 7. Cancel child scope
        if (::childScope.isInitialized) {
            childScope.cancel()
        }

        stateRef.set(ActorState.STOPPED)
        log.debug("Actor '{}' is now STOPPED (processed: {}, restarts: {}, children stopped)",
            name, processedCounter.get(), restartCounter.get())
    }

    /**
     * Suspend until the actor has stopped.
     */
    suspend fun awaitTermination() {
        job?.join()
    }

    // ─── Invariant Checks (TLA+ → Kotlin bridge) ────────────────

    // --- ActorLifecycle.tla invariants ---

    @TlaInvariant("RestartBudgetRespected")
    fun checkRestartBudget(): Boolean = restartCounter.get() <= supervisorStrategy.maxRestarts

    @TlaInvariant("StoppedNotAlive")
    fun checkStoppedNotAlive(): Boolean =
        !(stateRef.get() == ActorState.STOPPED && stateRef.get().isAlive())

    @TlaInvariant("AliveConsistency")
    fun checkAliveConsistency(): Boolean =
        !stateRef.get().isAlive() || stateRef.get() != ActorState.STOPPED

    // --- ActorHierarchy.tla invariants ---

    /** INV: Children relationship is bidirectionally consistent. */
    @TlaInvariant("ChildParentConsistency")
    fun checkChildParentConsistency(): Boolean =
        children.values.all { child -> child.parent === this }

    /** INV: A stopped actor has no remaining children. */
    @TlaInvariant("StoppedHasNoChildren")
    fun checkStoppedHasNoChildren(): Boolean =
        stateRef.get() != ActorState.STOPPED || children.isEmpty()

    /** INV: No actor is its own parent. */
    @TlaInvariant("NoCycles")
    fun checkNoCycles(): Boolean = parent !== this

    // --- DeathWatch.tla invariants ---

    /** INV: No actor watches itself. */
    @TlaInvariant("NoSelfWatch")
    fun checkNoSelfWatch(): Boolean = this !in watching

    /** INV: A dead actor has empty watching set. */
    @TlaInvariant("DeadNotWatching")
    fun checkDeadNotWatching(): Boolean =
        stateRef.get() != ActorState.STOPPED || watching.isEmpty()

    fun checkAllInvariants() {
        // ActorLifecycle invariants
        check(checkRestartBudget()) {
            "Invariant violated: RestartBudgetRespected — restarts=${restartCounter.get()} > max=${supervisorStrategy.maxRestarts}"
        }
        check(checkStoppedNotAlive()) { "Invariant violated: StoppedNotAlive" }
        check(checkAliveConsistency()) { "Invariant violated: AliveConsistency" }

        // ActorHierarchy invariants
        check(checkChildParentConsistency()) { "Invariant violated: ChildParentConsistency in '$name'" }
        check(checkStoppedHasNoChildren()) { "Invariant violated: StoppedHasNoChildren — '$name' is stopped but has ${children.size} children" }
        check(checkNoCycles()) { "Invariant violated: NoCycles — '$name' is its own parent" }

        // DeathWatch invariants
        check(checkNoSelfWatch()) { "Invariant violated: NoSelfWatch — '$name' watches itself" }
        check(checkDeadNotWatching()) { "Invariant violated: DeadNotWatching — '$name' is stopped but still watching ${watching.size} actors" }

        mailbox.checkAllInvariants()
    }

    override fun toString(): String = "ActorCell($name, state=${stateRef.get()}, children=${children.size})"
}

