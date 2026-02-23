package com.actors

import org.slf4j.LoggerFactory

/**
 * ═══════════════════════════════════════════════════════════════════
 * SUPERVISOR STRATEGY: Fault Tolerance Policy
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorLifecycle.tla (Fail, FailPermanent, Restart actions)
 *
 * Determines what happens when an actor's behavior throws an exception.
 * Inspired by Erlang's "let it crash" philosophy and Akka's supervision.
 *
 * Strategies:
 *   - Stop:    actor transitions to Stopped (TLA+: FailPermanent)
 *   - Restart: actor resets to initial behavior (TLA+: Restart)
 *              with a bounded restart budget (TLA+: MaxRestarts)
 *   - Resume:  skip the failed message, keep current behavior
 *   - Escalate: propagate failure to parent (future: supervision trees)
 */
class SupervisorStrategy private constructor(
    val directive: Directive,
    val maxRestarts: Int = 0,
    val decider: (Throwable) -> Directive = { directive }
) {
    enum class Directive {
        STOP,
        RESTART,
        RESUME,
        ESCALATE
    }

    companion object {
        private val log = LoggerFactory.getLogger(SupervisorStrategy::class.java)

        /** Stop the actor on any failure. Safest default. */
        fun stop(): SupervisorStrategy = SupervisorStrategy(Directive.STOP)

        /**
         * Restart the actor up to [maxRestarts] times, then stop.
         * Corresponds to TLA+ variables: restartCount, MaxRestarts
         */
        fun restart(maxRestarts: Int = 3): SupervisorStrategy =
            SupervisorStrategy(Directive.RESTART, maxRestarts)

        /** Skip the failed message and continue with the same behavior. */
        fun resume(): SupervisorStrategy = SupervisorStrategy(Directive.RESUME)

        /**
         * Custom decider: inspect the exception to choose a directive.
         *
         * Example:
         * ```kotlin
         * SupervisorStrategy.custom(maxRestarts = 5) { error ->
         *     when (error) {
         *         is IllegalArgumentException -> Directive.RESUME
         *         is IllegalStateException    -> Directive.RESTART
         *         else                        -> Directive.STOP
         *     }
         * }
         * ```
         */
        fun custom(
            maxRestarts: Int = 3,
            decider: (Throwable) -> Directive
        ): SupervisorStrategy = SupervisorStrategy(Directive.RESTART, maxRestarts, decider)
    }

    /**
     * Decide what to do given the exception and current restart count.
     * Returns the directive to apply.
     */
    @TlaAction("Fail|FailPermanent|Restart")
    fun decide(error: Throwable, currentRestarts: Int): Directive {
        val decision = decider(error)
        return when {
            decision == Directive.RESTART && currentRestarts >= maxRestarts -> {
                log.warn("Restart budget exhausted ({}/{}), stopping", currentRestarts, maxRestarts)
                Directive.STOP
            }
            else -> decision
        }
    }

    override fun toString(): String = "SupervisorStrategy($directive, maxRestarts=$maxRestarts)"
}
