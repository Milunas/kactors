package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * BEHAVIOR: Functional Actor Message Handler
 * ═══════════════════════════════════════════════════════════════════
 *
 * Defines how an actor processes messages. Behaviors are immutable
 * functions: each message returns the NEXT behavior, enabling
 * state machine patterns without mutable state.
 *
 * Design inspired by Akka Typed / Erlang gen_server, but with
 * Kotlin-specific improvements:
 *   - Suspend functions: actors can do async I/O mid-message
 *   - Sealed classes: exhaustive message matching
 *   - ActorContext: access to self, children, watch
 *
 * API styles:
 * ```kotlin
 * // Simple (no context needed)
 * behavior<CounterMsg> { msg -> ... }
 *
 * // Context-aware (spawn children, watch actors)
 * receive<CounterMsg> { ctx, msg -> ... }
 *
 * // One-time setup (resource init, then behavior)
 * setup<MyMsg> { ctx -> receive { ctx, msg -> ... } }
 *
 * // With signal handling (lifecycle events)
 * receive<MyMsg> { ctx, msg -> ... }
 *     .onSignal { ctx, signal -> ... }
 * ```
 */
@TlaSpec("ActorLifecycle")
fun interface Behavior<M : Any> {

    /**
     * Process a single message and return the next behavior.
     *
     * @param context The actor's context (self, spawn, watch, log)
     * @param message The incoming message
     * @return Next behavior:
     *   - A new Behavior: switches to that behavior for the next message
     *   - [Behavior.same]: reuses the current behavior (common case)
     *   - [Behavior.stopped]: signals the actor to stop
     */
    suspend fun onMessage(context: ActorContext<M>, message: M): Behavior<M>

    companion object {
        /**
         * Sentinel: keep the current behavior for the next message.
         */
        @Suppress("UNCHECKED_CAST")
        fun <M : Any> same(): Behavior<M> = SAME as Behavior<M>

        /**
         * Sentinel: stop the actor after processing this message.
         */
        @Suppress("UNCHECKED_CAST")
        fun <M : Any> stopped(): Behavior<M> = STOPPED as Behavior<M>

        /**
         * Creates a behavior that ignores all messages (useful for testing).
         */
        fun <M : Any> ignore(): Behavior<M> = Behavior { _, _ -> same() }

        /**
         * Setup behavior: runs a factory once when the actor starts,
         * producing the real behavior. The factory receives the ActorContext
         * for one-time initialization (resource setup, child spawning, etc.)
         *
         * Equivalent to Akka Typed's Behaviors.setup.
         *
         * ```kotlin
         * Behavior.setup<MyMsg> { ctx ->
         *     val db = connectToDatabase()
         *     ctx.watch(otherActorRef)
         *
         *     receive { ctx, msg ->
         *         db.query(msg.query)
         *         Behavior.same()
         *     }.onSignal { ctx, signal ->
         *         when (signal) {
         *             is Signal.PostStop -> { db.close(); Behavior.same() }
         *             else -> Behavior.same()
         *         }
         *     }
         * }
         * ```
         */
        fun <M : Any> setup(factory: (ActorContext<M>) -> Behavior<M>): Behavior<M> =
            SetupBehavior(factory)

        // Internal sentinel objects
        private val SAME = Behavior<Any> { _, _ -> throw IllegalStateException("SAME sentinel should never receive messages") }
        private val STOPPED = Behavior<Any> { _, _ -> throw IllegalStateException("STOPPED sentinel should never receive messages") }

        internal fun <M : Any> isSame(behavior: Behavior<M>): Boolean = behavior === SAME
        internal fun <M : Any> isStopped(behavior: Behavior<M>): Boolean = behavior === STOPPED
    }
}

/**
 * Setup behavior: wraps a factory that produces the real behavior.
 * The factory runs once when the actor starts, receiving the ActorContext.
 *
 * This allows one-time initialization (connecting to DB, spawning children,
 * registering watches) before the actor starts processing messages.
 */
class SetupBehavior<M : Any>(
    val factory: (ActorContext<M>) -> Behavior<M>
) : Behavior<M> {
    override suspend fun onMessage(context: ActorContext<M>, message: M): Behavior<M> {
        throw IllegalStateException("SetupBehavior.onMessage should never be called — it must be unwrapped first")
    }
}

/**
 * Signal-aware behavior: wraps an inner behavior with a signal handler.
 * Created via the [Behavior.onSignal] extension function.
 *
 * ```kotlin
 * receive<MyMsg> { ctx, msg -> ... }
 *     .onSignal { ctx, signal ->
 *         when (signal) {
 *             is Signal.PostStop -> { cleanup(); Behavior.same() }
 *             is Signal.Terminated -> { handleDeath(signal.ref); Behavior.same() }
 *             else -> Behavior.same()
 *         }
 *     }
 * ```
 */
class SignalBehavior<M : Any>(
    private val inner: Behavior<M>,
    private val signalHandler: suspend (ActorContext<M>, Signal) -> Behavior<M>
) : Behavior<M> {

    override suspend fun onMessage(context: ActorContext<M>, message: M): Behavior<M> =
        inner.onMessage(context, message)

    suspend fun onSignal(context: ActorContext<M>, signal: Signal): Behavior<M> =
        signalHandler(context, signal)
}

/**
 * Extension: attach a signal handler to any behavior.
 * Returns a [SignalBehavior] that delegates messages to the original
 * behavior and signals to the provided handler.
 *
 * ```kotlin
 * val myBehavior = receive<MyMsg> { ctx, msg ->
 *     Behavior.same()
 * }.onSignal { ctx, signal ->
 *     when (signal) {
 *         is Signal.Terminated -> { /* handle death */ Behavior.same() }
 *         else -> Behavior.same()
 *     }
 * }
 * ```
 */
fun <M : Any> Behavior<M>.onSignal(
    handler: suspend (ActorContext<M>, Signal) -> Behavior<M>
): Behavior<M> = SignalBehavior(this, handler)

