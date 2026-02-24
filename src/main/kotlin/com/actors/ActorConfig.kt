package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR CONFIG: User-Facing Configuration Bundle
 * ═══════════════════════════════════════════════════════════════════
 *
 * Immutable configuration for spawning actors. Bundles all tunable
 * parameters into a single data class so users can define and reuse
 * actor configurations without repeating parameters.
 *
 * Design rationale:
 *   Instead of N optional parameters on every spawn() call, users
 *   build an ActorConfig once and pass it. Defaults are production-
 *   safe. Override only what you need.
 *
 * Example:
 * ```kotlin
 * // Define once
 * val workerConfig = ActorConfig(
 *     mailboxCapacity = 64,
 *     supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 5),
 *     traceCapacity = 256,
 *     enableMessageSnapshots = true
 * )
 *
 * // Reuse across spawns
 * val w1 = system.spawn("worker-1", workerBehavior(), workerConfig)
 * val w2 = system.spawn("worker-2", workerBehavior(), workerConfig)
 *
 * // Or use defaults
 * val monitor = system.spawn("monitor", monitorBehavior())
 * ```
 */
data class ActorConfig(
    /**
     * Bounded mailbox capacity.
     * Controls backpressure: when full, senders suspend (tell) or get false (trySend).
     * Must be > 0. Default: [Mailbox.DEFAULT_CAPACITY] (256).
     */
    val mailboxCapacity: Int = Mailbox.DEFAULT_CAPACITY,

    /**
     * Fault tolerance policy for this actor.
     * Determines what happens when the actor's behavior throws.
     * Default: restart up to 3 times, then stop.
     */
    val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.restart(),

    /**
     * Flight recorder ring buffer capacity.
     * Controls how many trace events are retained per actor.
     * Larger = more history at the cost of memory.
     * Default: [ActorFlightRecorder.DEFAULT_CAPACITY] (128).
     */
    val traceCapacity: Int = ActorFlightRecorder.DEFAULT_CAPACITY,

    /**
     * Slow message warning threshold in milliseconds.
     * When a message takes longer than this to process, a
     * SlowMessageWarning trace event is recorded and a warning is logged.
     * Set to 0 to disable slow message detection.
     * Default: 100ms.
     */
    val slowMessageThresholdMs: Long = ActorCell.DEFAULT_SLOW_MESSAGE_THRESHOLD_MS,

    /**
     * Whether to capture message.toString() snapshots in trace events.
     * Useful for debugging but may expose sensitive data and uses memory.
     * Default: false (production-safe).
     */
    val enableMessageSnapshots: Boolean = false
) {
    init {
        require(mailboxCapacity > 0) { "mailboxCapacity must be > 0, got $mailboxCapacity" }
        require(traceCapacity > 0) { "traceCapacity must be > 0, got $traceCapacity" }
        require(slowMessageThresholdMs >= 0) { "slowMessageThresholdMs must be >= 0, got $slowMessageThresholdMs" }
    }

    companion object {
        /** Production-safe defaults. */
        val DEFAULT = ActorConfig()

        /**
         * Debug-oriented configuration: larger trace buffer, message snapshots enabled.
         * NOT for production — message snapshots may expose sensitive data.
         */
        val DEBUG = ActorConfig(
            traceCapacity = 1024,
            enableMessageSnapshots = true,
            slowMessageThresholdMs = 50L
        )

        /**
         * High-throughput configuration: larger mailbox, no slow message detection.
         */
        val HIGH_THROUGHPUT = ActorConfig(
            mailboxCapacity = 1024,
            slowMessageThresholdMs = 0L
        )
    }
}
