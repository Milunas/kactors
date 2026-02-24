package com.actors

import java.time.Instant

/**
 * ═══════════════════════════════════════════════════════════════════
 * TRACE EVENT: Coalgebraic Observable Event
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorTrace.tla (EventTypes constant)
 *
 * Represents a single observable event in an actor's lifetime.
 * Formally, this is an element of the observation functor F in
 * the coalgebraic model (B, δ: B → M → B × TraceEvent).
 *
 * The full trace τ = ⟨e₁, e₂, ..., eₙ⟩ is a finite prefix of
 * the final coalgebra (terminal F-coalgebra), captured by the
 * [ActorFlightRecorder] as a bounded ring buffer.
 *
 * Every event carries:
 *   - actorPath: hierarchical actor identity (the distributed trace ID)
 *   - timestamp: wall-clock time (for human debugging)
 *   - lamportTimestamp: logical time (for causal ordering)
 *   - event-specific payload
 *
 * The sealed hierarchy ensures exhaustive matching and enables
 * pattern-based filtering in the flight recorder dump.
 *
 * Kotlin mapping to TLA+ EventTypes:
 *   MessageReceived  → "msg_received"
 *   MessageSent      → "msg_sent"      (extension: not in minimal TLA+ spec)
 *   SignalDelivered   → "signal_delivered"
 *   StateChanged      → "state_changed"
 *   BehaviorChanged   → "behavior_changed"
 *   ChildSpawned      → "child_spawned"
 *   ChildStopped      → "child_stopped" (extension)
 *   WatchRegistered   → "watch_registered" (extension)
 *   FailureHandled    → "failure_handled" (extension)
 *   SlowMessageWarning → "slow_message" (extension: debuggability)
 */
@TlaSpec("ActorTrace")
sealed class TraceEvent {

    /** Hierarchical actor path — the primary trace correlation ID. */
    abstract val actorPath: String

    /** Wall-clock timestamp for human-readable debugging. */
    abstract val timestamp: Instant

    /**
     * Lamport logical timestamp for causal ordering.
     * Corresponds to TLA+ variable: lamportClock
     *
     * In a single actor, Lamport timestamps coincide with event count.
     * In a distributed setting, they establish happens-before between
     * events on different actors via message passing.
     */
    abstract val lamportTimestamp: Long

    // ─── Message Events ──────────────────────────────────────────

    /**
     * An actor received and is about to process a message.
     * TLA+ EventType: "msg_received"
     *
     * @param messageType Simple class name of the message (never the content — no PII leakage)
     * @param mailboxSizeAfter Mailbox occupancy after dequeue (backpressure indicator)
     */
    data class MessageReceived(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val messageType: String,
        val mailboxSizeAfter: Int
    ) : TraceEvent()

    /**
     * An actor sent a message to another actor via tell() or ask().
     * Not in the minimal TLA+ spec but essential for distributed tracing.
     *
     * @param targetPath The destination actor's path
     * @param messageType Simple class name of the message
     */
    data class MessageSent(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val targetPath: String,
        val messageType: String
    ) : TraceEvent()

    // ─── Signal Events ───────────────────────────────────────────

    /**
     * A signal was delivered to the actor's behavior.
     * TLA+ EventType: "signal_delivered"
     *
     * @param signalType The signal class (PreStart, PostStop, Terminated, ChildFailed)
     * @param detail Additional signal-specific info (e.g., terminated actor name)
     */
    data class SignalDelivered(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val signalType: String,
        val detail: String = ""
    ) : TraceEvent()

    // ─── Lifecycle Events ────────────────────────────────────────

    /**
     * The actor's lifecycle state changed.
     * TLA+ EventType: "state_changed"
     *
     * @param fromState Previous state
     * @param toState New state
     */
    data class StateChanged(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val fromState: ActorState,
        val toState: ActorState
    ) : TraceEvent()

    /**
     * The actor's behavior was replaced (state machine transition).
     * TLA+ EventType: "behavior_changed"
     *
     * In coalgebraic terms, this is an observation of the internal
     * state transition δ: B → B. We record the behavior class name
     * (never the closure contents — those are opaque).
     *
     * @param behaviorType Simple class name of the new behavior
     */
    data class BehaviorChanged(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val behaviorType: String
    ) : TraceEvent()

    // ─── Hierarchy Events ────────────────────────────────────────

    /**
     * A child actor was spawned under this actor.
     * TLA+ EventType: "child_spawned"
     *
     * @param childPath Full path of the spawned child
     * @param childBehaviorType Simple class name of the child's initial behavior
     */
    data class ChildSpawned(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val childPath: String,
        val childBehaviorType: String
    ) : TraceEvent()

    /**
     * A child actor stopped (normally or via failure).
     *
     * @param childPath Full path of the stopped child
     * @param reason Why the child stopped (normal, failure, cascade)
     */
    data class ChildStopped(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val childPath: String,
        val reason: String
    ) : TraceEvent()

    // ─── DeathWatch Events ───────────────────────────────────────

    /**
     * This actor registered a watch on another actor.
     *
     * @param watchedPath The path of the actor being watched
     */
    data class WatchRegistered(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val watchedPath: String
    ) : TraceEvent()

    // ─── Fault Tolerance Events ──────────────────────────────────

    /**
     * A failure was handled by the supervisor strategy.
     *
     * @param errorType Simple class name of the exception
     * @param errorMessage The exception message
     * @param directive The supervisor decision (STOP, RESTART, RESUME, ESCALATE)
     * @param restartCount Current restart count after this failure
     */
    data class FailureHandled(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val errorType: String,
        val errorMessage: String,
        val directive: SupervisorStrategy.Directive,
        val restartCount: Int
    ) : TraceEvent()

    // ─── Debuggability Events ────────────────────────────────────

    /**
     * Message processing took longer than the configured threshold.
     * This is the "slow actor detection" feature from the roadmap.
     *
     * @param messageType The message that was slow to process
     * @param durationMs How long processing took in milliseconds
     * @param thresholdMs The configured threshold that was exceeded
     */
    data class SlowMessageWarning(
        override val actorPath: String,
        override val timestamp: Instant,
        override val lamportTimestamp: Long,
        val messageType: String,
        val durationMs: Long,
        val thresholdMs: Long
    ) : TraceEvent()
}
