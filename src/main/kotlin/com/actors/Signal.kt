package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * SIGNAL: Actor Lifecycle Events
 * ═══════════════════════════════════════════════════════════════════
 *
 * Signals are system events delivered to actors alongside regular
 * messages. Unlike user messages (typed M), signals are universal
 * to all actors and represent lifecycle transitions.
 *
 * Inspired by Erlang process signals and Akka Typed's Signal trait,
 * but integrated with Kotlin sealed classes for exhaustive matching.
 *
 * Signal delivery:
 *   - PreStart / PostStop: delivered synchronously during lifecycle
 *   - Terminated: delivered asynchronously when a watched actor dies
 *   - ChildFailed: delivered asynchronously when a child actor fails
 *
 * Actors handle signals via [Behavior.onSignal] extension:
 * ```kotlin
 * receive<MyMsg> { ctx, msg ->
 *     // handle messages
 *     Behavior.same()
 * }.onSignal { ctx, signal ->
 *     when (signal) {
 *         is Signal.Terminated -> {
 *             ctx.log.info("Watched actor {} died", signal.ref)
 *             Behavior.same()
 *         }
 *         is Signal.PostStop -> {
 *             cleanup()
 *             Behavior.same()
 *         }
 *         else -> Behavior.same()
 *     }
 * }
 * ```
 */
@TlaSpec("ActorLifecycle")
sealed class Signal {

    /**
     * Delivered once before the actor processes its first message.
     * Equivalent to Erlang gen_server:init/1 or Akka Typed PreRestart.
     *
     * Use for: resource initialization, subscribing to events,
     * spawning initial children, starting timers.
     *
     * TLA+ variable: preStartDelivered (ActorLifecycle.tla)
     */
    data object PreStart : Signal()

    /**
     * Delivered during actor shutdown, after the last message.
     * Equivalent to Erlang gen_server:terminate/2 or Akka Typed PostStop.
     *
     * Use for: resource cleanup, unsubscribing, closing connections.
     * The actor's children have already been stopped when this fires.
     *
     * TLA+ variable: postStopDelivered (ActorLifecycle.tla)
     */
    data object PostStop : Signal()

    /**
     * Delivered when a watched actor terminates (for any reason).
     * Equivalent to Erlang {'DOWN', ...} monitor message or Akka Typed Terminated.
     *
     * The watcher receives this after calling [ActorContext.watch].
     * Contains the dead actor's ref for identification.
     *
     * TLA+ spec: DeathWatch.tla — Die(a) → terminated'[w] ∪= {a}
     *
     * @param ref The ActorRef that terminated
     */
    data class Terminated(val ref: ActorRef<*>) : Signal()

    /**
     * Delivered to a parent when a child actor fails with an exception.
     * Equivalent to Erlang EXIT signal or Akka Typed ChildFailed.
     *
     * The parent's [SupervisorStrategy] determines what happens next.
     * This signal is delivered BEFORE the strategy is applied, giving
     * the parent a chance to react (e.g., log, update state).
     *
     * TLA+ spec: ActorHierarchy.tla — child failure propagation pattern
     *
     * @param ref The child ActorRef that failed
     * @param cause The exception that caused the failure
     */
    data class ChildFailed(val ref: ActorRef<*>, val cause: Throwable) : Signal()
}
