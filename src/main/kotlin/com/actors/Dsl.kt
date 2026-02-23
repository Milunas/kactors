package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR DSL: Ergonomic API for Building Actor Systems
 * ═══════════════════════════════════════════════════════════════════
 *
 * Provides a Kotlin-idiomatic DSL for defining actors and behaviors.
 *
 * Example:
 * ```kotlin
 * val system = actorSystem("my-system") {
 *     val counter = spawn<CounterMsg>("counter") {
 *         var count = 0
 *         onMessage { msg ->
 *             when (msg) {
 *                 is Increment -> {
 *                     count += msg.n
 *                     sameBehavior()
 *                 }
 *                 is GetCount -> {
 *                     msg.replyTo.tell(count)
 *                     sameBehavior()
 *                 }
 *             }
 *         }
 *     }
 *
 *     counter.tell(Increment(5))
 *     val result: Int = counter.ask { replyTo -> GetCount(replyTo) }
 * }
 * ```
 */

/**
 * Creates a behavior from a simple message handler.
 * The returned behavior reuses itself (same) unless explicitly changed.
 */
inline fun <M : Any> behavior(crossinline handler: suspend (M) -> Behavior<M>): Behavior<M> {
    return Behavior { msg -> handler(msg) }
}

/**
 * Creates a stateless behavior that processes every message the same way.
 * The behavior is reused (same) after each message.
 */
inline fun <M : Any> statelessBehavior(crossinline handler: suspend (M) -> Unit): Behavior<M> {
    return Behavior { msg ->
        handler(msg)
        Behavior.same()
    }
}

/**
 * Creates a behavior with lifecycle hooks.
 */
inline fun <M : Any> lifecycleBehavior(
    crossinline onStart: suspend () -> Unit = {},
    crossinline onStop: suspend () -> Unit = {},
    crossinline handler: suspend (M) -> Behavior<M>
): Behavior<M> {
    return Behavior.withLifecycle(
        onStart = { onStart() },
        onStop = { onStop() },
        behavior = Behavior { msg -> handler(msg) }
    )
}

/**
 * Creates a stateful behavior using a closure for state.
 * Each call to the factory creates a fresh behavior with its own state.
 *
 * Example:
 * ```kotlin
 * fun counter(initial: Int = 0): Behavior<CounterMsg> = statefulBehavior(initial) { count, msg ->
 *     when (msg) {
 *         is Increment -> counter(count + msg.n)
 *         is GetCount -> {
 *             msg.replyTo.tell(count)
 *             Behavior.same()
 *         }
 *     }
 * }
 * ```
 */
inline fun <M : Any, S> statefulBehavior(
    initialState: S,
    crossinline handler: suspend (state: S, message: M) -> Behavior<M>
): Behavior<M> {
    var state = initialState
    return Behavior { msg ->
        val next = handler(state, msg)
        next
    }
}
