package com.actors

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════
 * TIMER SCHEDULER: Scheduled & Periodic Messages
 * ═══════════════════════════════════════════════════════════════════
 *
 * Allows actors to schedule messages to themselves (or others) with
 * delays and periodic intervals. Essential building block for:
 *
 *   - **Timeouts**: "if no response in 5s, send TimeoutMsg"
 *   - **Retries**: "retry this operation every 2s up to 3 times"
 *   - **Heartbeats**: "send Ping every 10s to check liveness"
 *   - **Batching**: "flush accumulated writes every 100ms"
 *
 * Each timer is identified by a String key. Starting a timer with
 * an existing key cancels the previous timer (idempotent replacement).
 *
 * Architecture:
 * ┌──────────────────────────────────────────────┐
 * │              TimerScheduler<M>               │
 * │                                              │
 * │  ┌─────────┐  ┌─────────┐  ┌─────────┐     │
 * │  │ Timer A  │  │ Timer B  │  │ Timer C  │    │
 * │  │ (single) │  │(periodic)│  │ (single) │    │
 * │  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
 * │       │              │              │         │
 * │       ▼              ▼              ▼         │
 * │            target.tell(message)               │
 * └──────────────────────────────────────────────┘
 *
 * Thread Safety:
 *   - Timer jobs are coroutines launched on the provided scope
 *   - ConcurrentHashMap ensures safe concurrent key management
 *   - Each timer is an independent coroutine (cancellation is safe)
 *
 * Usage in an actor:
 * ```kotlin
 * val timers = TimerScheduler<MyMsg>(scope)
 *
 * // Schedule a single delayed message
 * timers.startSingleTimer("timeout", TimeoutOccurred, 5.seconds, myRef)
 *
 * // Schedule periodic heartbeats
 * timers.startPeriodicTimer("heartbeat", Ping, 10.seconds, healthRef)
 *
 * // Cancel when no longer needed
 * timers.cancel("heartbeat")
 * ```
 *
 * Future (Distributed): Timers will be backed by a distributed
 * scheduler with cluster-wide deduplication.
 */
class TimerScheduler<M : Any>(
    private val scope: CoroutineScope
) {
    companion object {
        private val log = LoggerFactory.getLogger(TimerScheduler::class.java)
    }

    /**
     * Active timers: key → coroutine Job.
     * Replacing a key cancels the previous timer automatically.
     */
    private val timers = ConcurrentHashMap<String, Job>()

    /**
     * Schedule a single message to be sent after a delay.
     * If a timer with the same key exists, it is cancelled first.
     *
     * @param key Unique identifier for this timer (for cancellation)
     * @param message The message to send when the timer fires
     * @param delay How long to wait before sending
     * @param target The ActorRef to send the message to
     */
    fun startSingleTimer(
        key: String,
        message: M,
        delay: kotlin.time.Duration,
        target: ActorRef<M>
    ) {
        cancel(key) // Cancel existing timer with same key

        val job = scope.launch(CoroutineName("timer-$key")) {
            try {
                delay(delay)
                target.tell(message)
                log.debug("Timer '{}' fired (single, delay={})", key, delay)
            } catch (e: CancellationException) {
                log.trace("Timer '{}' cancelled", key)
            } finally {
                timers.remove(key)
            }
        }
        timers[key] = job
        log.debug("Timer '{}' started (single, delay={})", key, delay)
    }

    /**
     * Schedule a message to be sent repeatedly at a fixed interval.
     * The first message is sent after the initial delay.
     *
     * @param key Unique identifier for this timer
     * @param message The message to send on each tick
     * @param interval Time between successive sends
     * @param target The ActorRef to send the message to
     * @param initialDelay Delay before the first send (defaults to interval)
     */
    fun startPeriodicTimer(
        key: String,
        message: M,
        interval: kotlin.time.Duration,
        target: ActorRef<M>,
        initialDelay: kotlin.time.Duration = interval
    ) {
        cancel(key)

        val job = scope.launch(CoroutineName("timer-$key-periodic")) {
            try {
                delay(initialDelay)
                while (isActive) {
                    target.tell(message)
                    log.trace("Timer '{}' tick (periodic, interval={})", key, interval)
                    delay(interval)
                }
            } catch (e: CancellationException) {
                log.trace("Timer '{}' cancelled", key)
            } finally {
                timers.remove(key)
            }
        }
        timers[key] = job
        log.debug("Timer '{}' started (periodic, interval={})", key, interval)
    }

    /**
     * Cancel a specific timer by key.
     * No-op if the timer doesn't exist or already fired.
     */
    fun cancel(key: String) {
        timers.remove(key)?.let { job ->
            job.cancel()
            log.debug("Timer '{}' cancelled", key)
        }
    }

    /**
     * Cancel all active timers.
     */
    fun cancelAll() {
        val keys = timers.keys.toList()
        keys.forEach { cancel(it) }
        log.debug("All timers cancelled ({})", keys.size)
    }

    /**
     * Check if a timer with the given key is currently active.
     */
    fun isTimerActive(key: String): Boolean = timers[key]?.isActive == true

    /**
     * Number of currently active timers.
     */
    val activeCount: Int get() = timers.count { it.value.isActive }

    override fun toString(): String = "TimerScheduler(active=${activeCount})"
}
