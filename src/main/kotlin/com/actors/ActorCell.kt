package com.actors

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
@TlaSpec("ActorLifecycle|ActorHierarchy|DeathWatch|ActorTrace")
class ActorCell<M : Any>(
    val name: String,
    private val initialBehavior: Behavior<M>,
    internal val mailbox: Mailbox<M>,
    private val supervisorStrategy: SupervisorStrategy,
    private val parent: ActorCell<*>? = null,
    private val traceCapacity: Int = ActorFlightRecorder.DEFAULT_CAPACITY,
    private val slowMessageThresholdMs: Long = DEFAULT_SLOW_MESSAGE_THRESHOLD_MS,
    internal val enableMessageSnapshots: Boolean = false
) {
    companion object {
        private val log = LoggerFactory.getLogger(ActorCell::class.java)

        /**
         * Default threshold for slow message detection (milliseconds).
         * When message processing exceeds this, a SlowMessageWarning
         * trace event is recorded and a warning is logged.
         * Set to 0 to disable slow message detection.
         */
        const val DEFAULT_SLOW_MESSAGE_THRESHOLD_MS: Long = 100L
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

    // ─── Flight Recorder (Coalgebraic Trace Buffer) ──────────────

    /**
     * Per-actor flight recorder: bounded ring buffer of trace events.
     * Records every observable event (message, signal, state change,
     * behavior transition, child spawn, failure) for post-mortem debugging.
     *
     * In coalgebraic terms, this captures the observation component of
     * the actor's behavior coalgebra (B, δ: B → M → B × TraceEvent).
     *
     * TLA+ Spec: ActorTrace.tla — trace variable
     */
    val flightRecorder = ActorFlightRecorder(traceCapacity)
    /**
     * Current trace context for this actor, updated on each message receipt.
     * Enables trace propagation: when this actor sends a message to another,
     * the current trace context is captured and forwarded via MessageEnvelope.
     *
     * This is the actor's "current span" — it changes with each message.
     * Safe to read without synchronization because actors process messages
     * sequentially (single-writer coroutine).
     */
    @Volatile
    internal var currentTraceContext: TraceContext = TraceContext.EMPTY
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
        recordStateChange(ActorState.CREATED, ActorState.STARTING)

        system = actorSystem

        // Create child scope with SupervisorJob for fault isolation
        val childSupervisor = SupervisorJob(scope.coroutineContext.job)
        childScope = CoroutineScope(scope.coroutineContext + childSupervisor + CoroutineName("actor-$name-children"))

        // Create context (self ref already available)
        context = ActorContext(ref, actorSystem, this)

        job = scope.launch(CoroutineName("actor-$name") + ActorTraceElement(name, this)) {
            try {
                // Unwrap SetupBehavior: run factory to initialize
                if (currentBehavior is SetupBehavior<M>) {
                    currentBehavior = (currentBehavior as SetupBehavior<M>).factory(context)
                    recordBehaviorChange(currentBehavior)
                }

                // Deliver PreStart signal
                deliverSignal(Signal.PreStart)

                stateRef.set(ActorState.RUNNING)
                recordStateChange(ActorState.STARTING, ActorState.RUNNING)
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
        job = scope.launch(CoroutineName("actor-$name") + ActorTraceElement(name, this)) {
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
                    mailbox.channel.onReceive { envelope ->
                        mailbox.onReceived()

                        val message = envelope.message
                        val messageType = message!!::class.simpleName ?: "Unknown"

                        // Update Lamport clock for causal ordering (cross-actor)
                        if (envelope.senderLamportTime > 0) {
                            flightRecorder.updateLamportClock(envelope.senderLamportTime)
                        }

                        // Update current trace context from envelope
                        currentTraceContext = envelope.traceContext?.newChildSpan()
                            ?: TraceContext.create()

                        // Record message receipt with sender info
                        recordMessageReceived(
                            messageType = messageType,
                            senderPath = envelope.senderPath,
                            traceContext = currentTraceContext,
                            messageContent = envelope.messageSnapshot
                        )

                        // Process with slow message detection
                        val startTimeNs = System.nanoTime()
                        val nextBehavior = currentBehavior.onMessage(context, message)
                        val durationMs = (System.nanoTime() - startTimeNs) / 1_000_000

                        // Detect slow messages
                        if (slowMessageThresholdMs > 0 && durationMs > slowMessageThresholdMs) {
                            recordSlowMessage(messageType, durationMs)
                            log.warn("Actor '{}' slow message: {} took {}ms (threshold={}ms)",
                                name, messageType, durationMs, slowMessageThresholdMs)
                        }

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
        // Record signal delivery in flight recorder
        val signalType = signal::class.simpleName ?: "Unknown"
        val detail = when (signal) {
            is Signal.Terminated -> "watched=${signal.ref.name}"
            is Signal.ChildFailed -> "child=${signal.ref.name}, cause=${signal.cause.message}"
            else -> ""
        }
        recordSignalDelivered(signalType, detail)

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
                val prev = stateRef.get()
                stateRef.set(ActorState.STOPPING)
                recordStateChange(prev, ActorState.STOPPING)
                mailbox.close()
                job?.cancel()
            }
            Behavior.isSame(nextBehavior) -> {
                // Keep current behavior
            }
            else -> {
                currentBehavior = nextBehavior
                recordBehaviorChange(nextBehavior)
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

        // Record failure in flight recorder
        recordFailure(error, directive, restartCounter.get())

        when (directive) {
            SupervisorStrategy.Directive.RESUME -> {
                log.debug("Actor '{}' resuming after failure", name)
            }
            SupervisorStrategy.Directive.RESTART -> {
                val prevState = stateRef.get()
                stateRef.set(ActorState.RESTARTING)
                recordStateChange(prevState, ActorState.RESTARTING)
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
                    recordBehaviorChange(currentBehavior)
                }

                // Deliver PreStart to new behavior
                deliverSignal(Signal.PreStart)

                stateRef.set(ActorState.RUNNING)
                recordStateChange(ActorState.RESTARTING, ActorState.RUNNING)
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

        // Record child spawn in parent's flight recorder
        recordChildSpawned(childFullName, behavior::class.simpleName ?: "Unknown")

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
        recordChildStopped(child.name, "normal")
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

        // Record watch registration in flight recorder
        recordWatchRegistered(other.name)

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
        val prevState = stateRef.get()
        stateRef.set(ActorState.STOPPING)
        if (prevState != ActorState.STOPPING) {
            recordStateChange(prevState, ActorState.STOPPING)
        }

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
        recordStateChange(ActorState.STOPPING, ActorState.STOPPED)
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

        // Flight recorder invariants
        flightRecorder.checkAllInvariants()

        mailbox.checkAllInvariants()
    }

    // ─── Trace Recording Helpers (ActorTrace.tla bridge) ─────────

    /**
     * Record a state transition in the flight recorder.
     * TLA+ action: RecordEvent(a, "state_changed")
     */
    private fun recordStateChange(from: ActorState, to: ActorState) {
        flightRecorder.record(TraceEvent.StateChanged(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            fromState = from,
            toState = to
        ))
    }

    /**
     * Record a message receipt in the flight recorder.
     * Enriched with sender information from the MessageEnvelope.
     * TLA+ action: RecordEvent(a, "msg_received")
     */
    private fun recordMessageReceived(
        messageType: String,
        senderPath: String? = null,
        traceContext: TraceContext? = null,
        messageContent: String? = null
    ) {
        flightRecorder.record(TraceEvent.MessageReceived(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            messageType = messageType,
            mailboxSizeAfter = 0, // approximate — exact size requires experimental API
            senderPath = senderPath,
            traceContext = traceContext,
            messageContent = messageContent
        ))
    }

    /**
     * Record a signal delivery in the flight recorder.
     * TLA+ action: RecordEvent(a, "signal_delivered")
     */
    private fun recordSignalDelivered(signalType: String, detail: String) {
        flightRecorder.record(TraceEvent.SignalDelivered(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            signalType = signalType,
            detail = detail
        ))
    }

    /**
     * Record a behavior change in the flight recorder.
     * TLA+ action: RecordEvent(a, "behavior_changed")
     */
    private fun recordBehaviorChange(newBehavior: Behavior<M>) {
        flightRecorder.record(TraceEvent.BehaviorChanged(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            behaviorType = newBehavior::class.simpleName ?: "Anonymous"
        ))
    }

    /**
     * Record a child actor spawn in the flight recorder.
     * TLA+ action: RecordEvent(a, "child_spawned")
     */
    private fun recordChildSpawned(childPath: String, behaviorType: String) {
        flightRecorder.record(TraceEvent.ChildSpawned(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            childPath = childPath,
            childBehaviorType = behaviorType
        ))
    }

    /**
     * Record a child actor stop in the flight recorder.
     */
    private fun recordChildStopped(childPath: String, reason: String) {
        flightRecorder.record(TraceEvent.ChildStopped(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            childPath = childPath,
            reason = reason
        ))
    }

    /**
     * Record a watch registration in the flight recorder.
     */
    private fun recordWatchRegistered(watchedPath: String) {
        flightRecorder.record(TraceEvent.WatchRegistered(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            watchedPath = watchedPath
        ))
    }

    /**
     * Record a failure handling event in the flight recorder.
     */
    private fun recordFailure(error: Throwable, directive: SupervisorStrategy.Directive, restarts: Int) {
        flightRecorder.record(TraceEvent.FailureHandled(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            errorType = error::class.simpleName ?: "Unknown",
            errorMessage = error.message ?: "",
            directive = directive,
            restartCount = restarts
        ))
    }

    /**
     * Record a slow message warning in the flight recorder.
     */
    private fun recordSlowMessage(messageType: String, durationMs: Long) {
        flightRecorder.record(TraceEvent.SlowMessageWarning(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = flightRecorder.nextLamportTimestamp(),
            messageType = messageType,
            durationMs = durationMs,
            thresholdMs = slowMessageThresholdMs
        ))
    }

    override fun toString(): String = "ActorCell($name, state=${stateRef.get()}, children=${children.size})"
}

/**
 * Coroutine context element that identifies the currently executing actor.
 * Added to the actor's coroutine context on launch, enabling:
 *   - ActorRef.tell() to identify the sender (for trace correlation)
 *   - Cross-actor Lamport clock synchronization
 *   - Automatic TraceContext propagation
 *
 * This element is readable via `coroutineContext[ActorTraceElement]`
 * from any suspend function called within an actor's message handler.
 */
internal class ActorTraceElement(
    val actorPath: String,
    val cell: ActorCell<*>
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActorTraceElement>
}

