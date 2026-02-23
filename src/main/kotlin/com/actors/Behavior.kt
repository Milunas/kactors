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
 * Design inspired by Akka Typed / Erlang gen_server.
 *
 * Example:
 * ```kotlin
 * sealed class CounterMsg {
 *     data class Increment(val n: Int) : CounterMsg()
 *     data class GetCount(val replyTo: ActorRef<Int>) : CounterMsg()
 * }
 *
 * fun counter(count: Int = 0): Behavior<CounterMsg> = Behavior { msg ->
 *     when (msg) {
 *         is CounterMsg.Increment -> counter(count + msg.n)
 *         is CounterMsg.GetCount -> {
 *             msg.replyTo.tell(count)
 *             Behavior.same()
 *         }
 *     }
 * }
 * ```
 */
fun interface Behavior<M : Any> {

    /**
     * Process a single message and return the next behavior.
     *
     * Returning:
     *   - A new Behavior: switches to that behavior for the next message
     *   - [Behavior.same]: reuses the current behavior (common case)
     *   - [Behavior.stopped]: signals the actor to stop
     */
    suspend fun onMessage(message: M): Behavior<M>

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
        fun <M : Any> ignore(): Behavior<M> = Behavior { same() }

        /**
         * Wraps a behavior with lifecycle hooks.
         */
        fun <M : Any> withLifecycle(
            onStart: suspend () -> Unit = {},
            onStop: suspend () -> Unit = {},
            behavior: Behavior<M>
        ): LifecycleBehavior<M> = LifecycleBehavior(onStart, onStop, behavior)

        // Internal sentinel objects
        private val SAME = Behavior<Any> { throw IllegalStateException("SAME sentinel should never receive messages") }
        private val STOPPED = Behavior<Any> { throw IllegalStateException("STOPPED sentinel should never receive messages") }

        internal fun <M : Any> isSame(behavior: Behavior<M>): Boolean = behavior === SAME
        internal fun <M : Any> isStopped(behavior: Behavior<M>): Boolean = behavior === STOPPED
    }
}

/**
 * A behavior with lifecycle hooks (preStart, postStop).
 * Corresponds to actor lifecycle transitions in ActorLifecycle.tla.
 */
class LifecycleBehavior<M : Any>(
    val onStart: suspend () -> Unit,
    val onStop: suspend () -> Unit,
    private val inner: Behavior<M>
) : Behavior<M> {

    override suspend fun onMessage(message: M): Behavior<M> = inner.onMessage(message)
}
