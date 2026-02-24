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

    override fun toString(): String = "ActorContext($name)"
}
