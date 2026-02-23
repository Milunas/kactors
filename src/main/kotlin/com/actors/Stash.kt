package com.actors

import org.slf4j.LoggerFactory
import java.util.*

/**
 * ═══════════════════════════════════════════════════════════════════
 * STASH: Message Buffer for State Transitions
 * ═══════════════════════════════════════════════════════════════════
 *
 * Allows actors to temporarily buffer messages that cannot be
 * processed in the current state. When the actor transitions to a
 * new state, stashed messages are replayed in FIFO order.
 *
 * Essential patterns:
 *
 *   1. **Initialization**: stash messages while loading config/data
 *      from a database, then unstash when ready
 *   2. **Busy states**: stash requests while processing a long
 *      operation, then unstash when idle
 *   3. **Coordination**: stash messages until a dependency actor
 *      signals it's ready
 *
 * Architecture:
 * ```
 *   Messages arrive → Actor in "initializing" state
 *                     ┌───────────────┐
 *        msg1 ──────▶│    STASH      │
 *        msg2 ──────▶│  [msg1,msg2]  │
 *        msg3 ──────▶│  [msg1..msg3] │
 *                     └───────┬───────┘
 *                             │ unstashAll()
 *                             ▼
 *              Actor transitions to "ready" state
 *              msg1 → processed
 *              msg2 → processed
 *              msg3 → processed
 * ```
 *
 * Thread Safety:
 *   - Stash is designed to be used within a single actor (single-threaded)
 *   - No synchronization needed because actors process messages sequentially
 *   - The bounded capacity prevents unbounded memory growth
 *
 * Usage:
 * ```kotlin
 * val stash = Stash<MyMsg>(capacity = 100)
 *
 * fun initializing(): Behavior<MyMsg> = behavior { msg ->
 *     when (msg) {
 *         is ConfigLoaded -> {
 *             stash.unstashAll(ready(msg.config))
 *         }
 *         else -> {
 *             stash.stash(msg)
 *             Behavior.same()
 *         }
 *     }
 * }
 * ```
 */
class Stash<M : Any>(
    val capacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        const val DEFAULT_CAPACITY = 1000
        private val log = LoggerFactory.getLogger(Stash::class.java)
    }

    private val buffer: Deque<M> = ArrayDeque(capacity)

    /**
     * Buffer a message for later processing.
     *
     * @param message The message to stash
     * @throws IllegalStateException if the stash is full (prevents OOM)
     */
    fun stash(message: M) {
        check(buffer.size < capacity) {
            "Stash is full (capacity=$capacity). " +
                "Consider increasing capacity or ensuring unstashAll() is called."
        }
        buffer.addLast(message)
        log.trace("Stashed message (size: {})", buffer.size)
    }

    /**
     * Replay all stashed messages through a new behavior, then return
     * that behavior for subsequent messages.
     *
     * Messages are replayed in FIFO order (oldest first).
     * If processing a stashed message returns a new behavior, subsequent
     * stashed messages are processed with the new behavior.
     *
     * @param behavior The behavior to process stashed messages with
     * @return The final behavior after processing all stashed messages
     */
    suspend fun unstashAll(behavior: Behavior<M>): Behavior<M> {
        if (buffer.isEmpty()) {
            log.trace("unstashAll called with empty stash")
            return behavior
        }

        val count = buffer.size
        log.debug("Unstashing {} messages", count)

        var currentBehavior = behavior
        while (buffer.isNotEmpty()) {
            val message = buffer.pollFirst()
            val nextBehavior = currentBehavior.onMessage(message)

            when {
                Behavior.isStopped(nextBehavior) -> {
                    buffer.clear()
                    return nextBehavior
                }
                Behavior.isSame(nextBehavior) -> {
                    // Keep current behavior
                }
                else -> {
                    currentBehavior = nextBehavior
                }
            }
        }

        log.debug("Unstashed {} messages, stash now empty", count)
        return currentBehavior
    }

    /**
     * Replay stashed messages and wrap the result behavior to
     * become the actor's next behavior. This is a convenience
     * for the common "transition + unstash" pattern.
     *
     * @param behavior The target behavior for the new state
     * @return A behavior that first processes stashed messages
     */
    fun unstashAllWrapped(behavior: Behavior<M>): Behavior<M> {
        if (buffer.isEmpty()) return behavior

        val stashedMessages = buffer.toList()
        buffer.clear()

        return Behavior { firstMessage ->
            var currentBehavior = behavior
            // Process stashed messages first
            for (msg in stashedMessages) {
                val next = currentBehavior.onMessage(msg)
                when {
                    Behavior.isStopped(next) -> return@Behavior Behavior.stopped()
                    Behavior.isSame(next) -> { /* keep current */ }
                    else -> currentBehavior = next
                }
            }
            // Then process the current message
            currentBehavior.onMessage(firstMessage)
        }
    }

    /**
     * Discard all stashed messages.
     */
    fun clear() {
        val count = buffer.size
        buffer.clear()
        if (count > 0) {
            log.debug("Stash cleared ({} messages discarded)", count)
        }
    }

    /**
     * Number of currently stashed messages.
     */
    val size: Int get() = buffer.size

    /**
     * Whether the stash is empty.
     */
    val isEmpty: Boolean get() = buffer.isEmpty()

    /**
     * Whether the stash is full.
     */
    val isFull: Boolean get() = buffer.size >= capacity

    override fun toString(): String = "Stash(size=$size, capacity=$capacity)"
}
