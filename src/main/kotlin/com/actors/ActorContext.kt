package com.actors

import org.slf4j.LoggerFactory

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR CONTEXT: Actor's View of the World
 * ═══════════════════════════════════════════════════════════════════
 *
 * The ActorContext is passed to every behavior invocation, giving
 * the actor access to its own identity, the ability to spawn children,
 * watch other actors, and interact with the system.
 *
 * This is the equivalent of:
 *   - Erlang: self(), spawn_link/3, monitor/2
 *   - Akka Typed: ActorContext<T>
 *
 * Key difference from both: Kotlin coroutine integration allows
 * suspend functions in behaviors, enabling natural async composition
 * without callbacks or futures.
 *
 * The context is created once per actor and reused across all message
 * invocations. It is NOT thread-safe for external use — it should only
 * be used from within the actor's own coroutine (message handler).
 *
 * Example:
 * ```kotlin
 * receive<ParentMsg> { ctx, msg ->
 *     when (msg) {
 *         is SpawnChild -> {
 *             val child = ctx.spawn("worker", workerBehavior)
 *             ctx.watch(child)
 *             Behavior.same()
 *         }
 *     }
 * }.onSignal { ctx, signal ->
 *     when (signal) {
 *         is Signal.Terminated -> {
 *             ctx.log.info("Child {} died, spawning replacement", signal.ref)
 *             ctx.spawn("worker", workerBehavior)
 *             Behavior.same()
 *         }
 *         else -> Behavior.same()
 *     }
 * }
 * ```
 */
@TlaSpec("ActorHierarchy|DeathWatch")
class ActorContext<M : Any> internal constructor(
    /** This actor's own reference. Equivalent to Erlang's self(). */
    val self: ActorRef<M>,

    /** The ActorSystem this actor belongs to. */
    val system: ActorSystem,

    /** Internal cell — not exposed to users. */
    internal val cell: ActorCell<M>
) {
    /** Actor's fully qualified name (system/parent/.../name). */
    val name: String get() = self.name

    /** Logger scoped to this actor's name. */
    val log = LoggerFactory.getLogger("actor.${self.name}")

    /**
     * Spawn a child actor supervised by this actor.
     *
     * The child becomes part of this actor's supervision tree:
     *   - When this actor stops, the child stops too
     *   - When the child fails, this actor's supervisor strategy decides
     *   - The child's name is scoped under this actor's path
     *
     * Equivalent to Erlang's spawn_link or Akka's context.spawn.
     *
     * @param name Unique name within this actor's children
     * @param behavior The child's initial behavior
     * @param mailboxCapacity Bounded mailbox size
     * @param supervisorStrategy How to handle child failures
     * @return Type-safe reference to the spawned child
     */
    @TlaAction("SpawnChild")
    fun <C : Any> spawn(
        name: String,
        behavior: Behavior<C>,
        mailboxCapacity: Int = Mailbox.DEFAULT_CAPACITY,
        supervisorStrategy: SupervisorStrategy = SupervisorStrategy.restart()
    ): ActorRef<C> {
        return cell.spawnChild(name, behavior, mailboxCapacity, supervisorStrategy)
    }

    /**
     * Stop a child actor by its ref.
     * Only works for direct children of this actor.
     *
     * @throws IllegalArgumentException if ref is not a child of this actor
     */
    @TlaAction("InitiateStop")
    fun stop(child: ActorRef<*>) {
        cell.stopChild(child)
    }

    /**
     * Watch another actor for termination.
     *
     * When the watched actor stops (for any reason), this actor
     * receives a [Signal.Terminated] signal. This is the basis for
     * failure detection in actor systems.
     *
     * Equivalent to Erlang's erlang:monitor(process, Pid) or
     * Akka Typed's context.watch(ref).
     *
     * Returns the ref for fluent chaining:
     * ```kotlin
     * val child = ctx.watch(ctx.spawn("worker", workerBehavior))
     * ```
     *
     * @return The same ref, for convenience
     */
    @TlaAction("Watch")
    fun <C : Any> watch(ref: ActorRef<C>): ActorRef<C> {
        cell.watch(ref.actorCell)
        return ref
    }

    /**
     * Stop watching an actor for termination.
     * No [Signal.Terminated] will be delivered after this call.
     *
     * @return The same ref, for convenience
     */
    @TlaAction("Unwatch")
    fun <C : Any> unwatch(ref: ActorRef<C>): ActorRef<C> {
        cell.unwatch(ref.actorCell)
        return ref
    }

    /**
     * The set of this actor's direct children.
     * Useful for monitoring or broadcasting to children.
     */
    val children: Set<ActorRef<*>>
        get() = cell.childRefs

    /**
     * Access the actor's flight recorder for debugging and tracing.
     *
     * The flight recorder captures a bounded ring buffer of trace events
     * (messages received, signals delivered, state changes, behavior
     * transitions, child spawns, failures, slow messages).
     *
     * This is the coalgebraic observation of the actor's behavior:
     * a finite prefix of the final coalgebra over the observation functor.
     *
     * Example:
     * ```kotlin
     * receive<DebugMsg> { ctx, msg ->
     *     when (msg) {
     *         is DumpTrace -> {
     *             val trace = ctx.flightRecorder.snapshot()
     *             msg.replyTo.tell(trace.toString())
     *             Behavior.same()
     *         }
     *         is DumpTraceFormatted -> {
     *             msg.replyTo.tell(ctx.flightRecorder.dump())
     *             Behavior.same()
     *         }
     *     }
     * }
     * ```
     */
    val flightRecorder: ActorFlightRecorder
        get() = cell.flightRecorder

    /**
     * The current trace context for this actor.
     * Updated on each message receipt — reflects the trace of the currently
     * processing message. Use this to correlate application-level operations
     * with the actor's trace.
     *
     * Returns [TraceContext.EMPTY] if no trace is active (e.g., during startup).
     */
    val currentTraceContext: TraceContext
        get() = cell.currentTraceContext

    // ─── Structured Trace Logging ────────────────────────────────
    //
    // These methods simultaneously:
    //   1. Log to SLF4J at the appropriate level (for log aggregation)
    //   2. Record a CustomEvent in the flight recorder (for replay/analysis)
    //
    // This gives you a UNIFIED timeline: system events (messages, signals,
    // state changes) AND your application-level logs in one place.
    //
    // Example:
    //   ctx.info("Processing order", "orderId" to "12345", "amount" to "99.99")
    //   → SLF4J: actor.system/orders - Processing order
    //   → FlightRecorder: CustomEvent(INFO, "Processing order", {orderId=12345, amount=99.99})
    //

    /**
     * Log at TRACE level and record in the flight recorder.
     * Use for fine-grained debugging that you'd normally disable in production.
     *
     * @param message The log message
     * @param extra Optional key-value pairs for structured data
     */
    fun trace(message: String, vararg extra: Pair<String, String>) {
        log.trace(message)
        recordCustomEvent(TraceEvent.LogLevel.TRACE, message, extra.toMap())
    }

    /**
     * Log at DEBUG level and record in the flight recorder.
     * Use for developer-oriented information during development.
     *
     * @param message The log message
     * @param extra Optional key-value pairs for structured data
     */
    fun debug(message: String, vararg extra: Pair<String, String>) {
        log.debug(message)
        recordCustomEvent(TraceEvent.LogLevel.DEBUG, message, extra.toMap())
    }

    /**
     * Log at INFO level and record in the flight recorder.
     * Use for notable business events (order placed, user logged in).
     *
     * @param message The log message
     * @param extra Optional key-value pairs for structured data
     */
    fun info(message: String, vararg extra: Pair<String, String>) {
        log.info(message)
        recordCustomEvent(TraceEvent.LogLevel.INFO, message, extra.toMap())
    }

    /**
     * Log at WARN level and record in the flight recorder.
     * Use for potential issues that don't prevent operation.
     *
     * @param message The log message
     * @param extra Optional key-value pairs for structured data
     */
    fun warn(message: String, vararg extra: Pair<String, String>) {
        log.warn(message)
        recordCustomEvent(TraceEvent.LogLevel.WARN, message, extra.toMap())
    }

    /**
     * Log at ERROR level and record in the flight recorder.
     * Use for errors that need attention but don't crash the actor.
     *
     * @param message The log message
     * @param extra Optional key-value pairs for structured data
     */
    fun error(message: String, vararg extra: Pair<String, String>) {
        log.error(message)
        recordCustomEvent(TraceEvent.LogLevel.ERROR, message, extra.toMap())
    }

    /**
     * Record a custom event with structured data in the flight recorder
     * without logging to SLF4J. Use when you want replay data but not log noise.
     *
     * @param data Key-value pairs of structured data
     */
    fun record(vararg data: Pair<String, String>) {
        recordCustomEvent(TraceEvent.LogLevel.INFO, "", data.toMap())
    }

    private fun recordCustomEvent(level: TraceEvent.LogLevel, message: String, extra: Map<String, String>) {
        cell.flightRecorder.record(TraceEvent.CustomEvent(
            actorPath = name,
            timestamp = java.time.Instant.now(),
            lamportTimestamp = cell.flightRecorder.nextLamportTimestamp(),
            level = level,
            message = message,
            traceContext = cell.currentTraceContext.takeIf { it.isActive },
            extra = extra
        ))
    }

    override fun toString(): String = "ActorContext($name)"
}
