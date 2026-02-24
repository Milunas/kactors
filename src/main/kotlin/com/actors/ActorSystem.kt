package com.actors

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR SYSTEM: Top-Level Actor Container
 * ═══════════════════════════════════════════════════════════════════
 *
 * The ActorSystem is the entry point for creating and managing actors.
 * It owns the coroutine scope in which all actors run and provides:
 *
 *   1. Actor spawning with type-safe refs
 *   2. Actor lookup by name
 *   3. Graceful system shutdown
 *   4. Root of the supervision tree
 *
 * Architecture (with hierarchy):
 * ```
 * ┌─────────────────────────────────────────────────┐
 * │                   ActorSystem                    │
 * │          CoroutineScope (SupervisorJob)          │
 * │                                                  │
 * │  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
 * │  │ ActorCell │  │ ActorCell │  │ ActorCell │     │
 * │  │ (parent)  │  │ (parent)  │  │ (parent)  │     │
 * │  │  ┌─────┐  │  │          │  │  ┌─────┐  │     │
 * │  │  │child│  │  │          │  │  │child│  │     │
 * │  │  └─────┘  │  │          │  │  └─────┘  │     │
 * │  └──────────┘  └──────────┘  └──────────┘      │
 * └─────────────────────────────────────────────────┘
 * ```
 *
 * Top-level actors spawned here have no parent (parent = null).
 * They can spawn children via [ActorContext.spawn], forming a tree.
 *
 * Future (Distributed Actor System):
 *   - ActorSystem per node
 *   - Cluster membership protocol
 *   - Remote ActorRef routing
 *   - Actor migration between nodes
 */
class ActorSystem(
    val name: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    companion object {
        private val log = LoggerFactory.getLogger(ActorSystem::class.java)

        /**
         * Create and return a new ActorSystem.
         */
        fun create(name: String, dispatcher: CoroutineDispatcher = Dispatchers.Default): ActorSystem {
            return ActorSystem(name, dispatcher)
        }
    }

    // SupervisorJob: child failure doesn't cancel siblings
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + supervisorJob + CoroutineName("actor-system-$name"))
    private val actors = ConcurrentHashMap<String, ActorCell<*>>()
    private val terminated = AtomicBoolean(false)

    /**
     * Spawn a new top-level actor with the given behavior and return its ref.
     *
     * Top-level actors have no parent — they are directly supervised
     * by the system. Use [ActorContext.spawn] within a behavior to
     * create child actors that form a supervision tree.
     *
     * @param name Unique name for the actor (used for lookup and logging)
     * @param behavior The initial message handling behavior
     * @param mailboxCapacity Bounded mailbox size (backpressure threshold)
     * @param supervisorStrategy Fault tolerance policy
     * @return Type-safe reference to the spawned actor
     * @throws IllegalStateException if system is terminated or name is taken
     */
    fun <M : Any> spawn(
        name: String,
        behavior: Behavior<M>,
        mailboxCapacity: Int = Mailbox.DEFAULT_CAPACITY,
        supervisorStrategy: SupervisorStrategy = SupervisorStrategy.restart()
    ): ActorRef<M> {
        check(!terminated.get()) { "Cannot spawn actor in terminated ActorSystem '${this.name}'" }
        check(!actors.containsKey(name)) { "Actor with name '$name' already exists" }

        val mailbox = Mailbox<M>(mailboxCapacity)
        val cell = ActorCell(
            name = "${this.name}/$name",
            initialBehavior = behavior,
            mailbox = mailbox,
            supervisorStrategy = supervisorStrategy,
            parent = null  // Top-level: no parent
        )

        actors[name] = cell
        cell.start(scope, this)
        log.info("Spawned actor '{}/{}' (mailbox={}, supervisor={})",
            this.name, name, mailboxCapacity, supervisorStrategy)

        return cell.ref
    }

    /**
     * Stop a specific actor by name.
     */
    fun stop(actorName: String) {
        val cell = actors[actorName]
            ?: throw IllegalArgumentException("No actor named '$actorName' in system '$name'")
        cell.stop()
        log.info("Stopped actor '{}/{}'", name, actorName)
    }

    /**
     * Gracefully shut down the entire actor system.
     * Stops all actors and cancels the supervisor scope.
     */
    suspend fun terminate() {
        if (terminated.getAndSet(true)) return

        log.info("Terminating ActorSystem '{}' ({} actors)", name, actors.size)

        // Stop all actors
        actors.values.forEach { it.stop() }

        // Wait for all actors to complete
        actors.values.forEach { it.awaitTermination() }

        // Cancel the scope
        supervisorJob.cancelAndJoin()

        actors.clear()
        log.info("ActorSystem '{}' terminated", name)
    }

    /**
     * Check if the system is still running.
     */
    val isTerminated: Boolean get() = terminated.get()

    /**
     * Number of top-level actors currently managed by this system.
     */
    val actorCount: Int get() = actors.size

    /**
     * Get actor names (for debugging/monitoring).
     */
    val actorNames: Set<String> get() = actors.keys.toSet()

    // ─── Observability & Tracing ─────────────────────────────────

    /**
     * Dump the full supervision tree as ASCII art.
     * Includes per-actor state, message counts, and flight recorder stats.
     *
     * Example output:
     * ```
     * ActorSystem: my-system
     * ├── [RUNNING] my-system/guardian  msgs=42 restarts=0 mailbox=../256 trace=128/128
     * │   ├── [RUNNING] my-system/guardian/worker-1  msgs=20 restarts=1
     * │   └── [STOPPED] my-system/guardian/worker-2  msgs=10 restarts=3
     * └── [RUNNING] my-system/monitor  msgs=5 restarts=0
     * ```
     */
    fun dumpTree(): String = ActorTreeDumper.dumpAscii(this)

    /**
     * Dump the full supervision tree as JSON.
     * Useful for tooling, web UIs, and programmatic analysis.
     */
    fun dumpTreeJson(): String = ActorTreeDumper.dumpJson(this)

    /**
     * Dump the flight recorder of a specific actor by name.
     *
     * @param actorName Local name of the top-level actor
     * @return Formatted flight recorder dump, or null if actor not found
     */
    fun dumpActorTrace(actorName: String): String? {
        return actors[actorName]?.flightRecorder?.dump()
    }

    /**
     * Dump all actors' flight recorders.
     * Useful for post-mortem debugging of the entire system.
     */
    fun dumpAllTraces(): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════════════")
        sb.appendLine("SYSTEM TRACE DUMP: $name")
        sb.appendLine("═══════════════════════════════════════════════════")
        actors.values.sortedBy { it.name }.forEach { cell ->
            sb.appendLine(cell.flightRecorder.dump())
        }
        return sb.toString()
    }

    override fun toString(): String = "ActorSystem($name, actors=${actors.size})"
}
