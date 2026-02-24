package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR DSL: Ergonomic API for Building Actor Systems
 * ═══════════════════════════════════════════════════════════════════
 *
 * Provides a Kotlin-idiomatic DSL for defining actors and behaviors.
 * Three styles, pick the one that fits:
 *
 * ```kotlin
 * // Style 1: Simple (no context needed)
 * val counter = behavior<Int> { msg ->
 *     println("Got $msg")
 *     Behavior.same()
 * }
 *
 * // Style 2: Context-aware (spawn children, watch, self)
 * val parent = receive<ParentMsg> { ctx, msg ->
 *     when (msg) {
 *         is SpawnWorker -> {
 *             val child = ctx.spawn("worker", workerBehavior)
 *             ctx.watch(child)
 *             Behavior.same()
 *         }
 *     }
 * }
 *
 * // Style 3: Setup (one-time init + context)
 * val dbActor = setup<DbMsg> { ctx ->
 *     val conn = Database.connect()
 *     receive<DbMsg> { ctx, msg -> ... }
 *         .onSignal { ctx, signal ->
 *             when (signal) {
 *                 is Signal.PostStop -> { conn.close(); Behavior.same() }
 *                 else -> Behavior.same()
 *             }
 *         }
 * }
 * ```
 */

// ─── Context-Aware Behaviors ─────────────────────────────────────

/**
 * Creates a behavior with access to the [ActorContext].
 * The context provides self, spawn, watch, and log.
 *
 * This is the primary API for behaviors that need to interact with
 * the actor system (spawning children, watching other actors, etc.)
 *
 * Equivalent to Akka Typed's `Behaviors.receive`.
 */
inline fun <M : Any> receive(crossinline handler: suspend (ActorContext<M>, M) -> Behavior<M>): Behavior<M> {
    return Behavior { ctx, msg -> handler(ctx, msg) }
}

/**
 * Setup: one-time initialization that produces a behavior.
 * The factory runs once when the actor starts.
 *
 * Use for: resource initialization, spawning initial children,
 * establishing watches, registering with a receptionist.
 *
 * Equivalent to Akka Typed's `Behaviors.setup`.
 */
fun <M : Any> setup(factory: (ActorContext<M>) -> Behavior<M>): Behavior<M> {
    return SetupBehavior(factory)
}

// ─── Simple Behaviors (backward compatible) ──────────────────────

/**
 * Creates a behavior from a simple message handler (no context needed).
 * Use this when the actor doesn't need to spawn children or watch others.
 */
inline fun <M : Any> behavior(crossinline handler: suspend (M) -> Behavior<M>): Behavior<M> {
    return Behavior { _, msg -> handler(msg) }
}

/**
 * Creates a stateless behavior that processes every message the same way.
 * The behavior is reused (same) after each message.
 */
inline fun <M : Any> statelessBehavior(crossinline handler: suspend (M) -> Unit): Behavior<M> {
    return Behavior { _, msg ->
        handler(msg)
        Behavior.same()
    }
}

/**
 * Creates a behavior with lifecycle hooks (onStart, onStop).
 * Implemented via [SetupBehavior] + [Signal] handling internally.
 *
 * ```kotlin
 * val actor = lifecycleBehavior<DbMsg>(
 *     onStart = { println("Connecting...") },
 *     onStop  = { println("Disconnecting...") }
 * ) { msg ->
 *     // handle messages
 *     Behavior.same()
 * }
 * ```
 */
inline fun <M : Any> lifecycleBehavior(
    crossinline onStart: suspend () -> Unit = {},
    crossinline onStop: suspend () -> Unit = {},
    crossinline handler: suspend (M) -> Behavior<M>
): Behavior<M> {
    return setup { _ ->
        onStart()
        behavior<M> { msg -> handler(msg) }
            .onSignal { _, signal ->
                when (signal) {
                    is Signal.PostStop -> {
                        onStop()
                        Behavior.same()
                    }
                    else -> Behavior.same()
                }
            }
    }
}

/**
 * Creates a stateful behavior using functional state transitions.
 * Each message handler receives the current state and returns a new
 * behavior — the state is captured in the closure, following the
 * immutable behavior pattern required by AGENTS.md §4.
 *
 * The returned Behavior<M> wraps the handler so that when a message
 * is processed, the handler can return either:
 *   - `statefulBehavior(newState) { ... }` to transition to new state
 *   - `Behavior.same()` to keep current state
 *   - `Behavior.stopped()` to terminate the actor
 *
 * Example:
 * ```kotlin
 * fun counter(initial: Int = 0): Behavior<CounterMsg> = statefulBehavior(initial) { count, msg ->
 *     when (msg) {
 *         is Increment -> statefulBehavior(count + msg.n) { s, m -> ... }
 *         is GetCount -> {
 *             msg.replyTo.tell(count)
 *             Behavior.same()
 *         }
 *     }
 * }
 * ```
 *
 * NOTE: For most use cases, the functional recursion pattern is clearer:
 * ```kotlin
 * fun counter(count: Int = 0): Behavior<CounterMsg> = receive { ctx, msg ->
 *     when (msg) {
 *         is Increment -> counter(count + msg.n)
 *         is GetCount -> { msg.replyTo.tell(count); Behavior.same() }
 *     }
 * }
 * ```
 */
inline fun <M : Any, S> statefulBehavior(
    initialState: S,
    crossinline handler: suspend (state: S, message: M) -> Behavior<M>
): Behavior<M> {
    return Behavior { _, msg ->
        handler(initialState, msg)
    }
}

