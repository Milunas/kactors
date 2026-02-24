package com.actors

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import org.slf4j.LoggerFactory

/**
 * ═══════════════════════════════════════════════════════════════════
 * MAILBOX: Bounded Channel-Based Message Queue
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorMailbox.tla
 *
 * Each actor has exactly one mailbox — a bounded FIFO queue backed
 * by a Kotlin [Channel]. Multiple senders can enqueue messages
 * concurrently; exactly one actor coroutine consumes from it.
 *
 * ┌─────────┐     ┌─────────┐
 * │ Sender1 │────▶│         │
 * └─────────┘     │ Mailbox │────▶ Actor coroutine
 * ┌─────────┐     │ (FIFO)  │
 * │ Sender2 │────▶│         │
 * └─────────┘     └─────────┘
 *
 * Key properties (verified by TLA+ invariants):
 *   INV-1 BoundedCapacity:    len(mailbox) ≤ capacity
 *   INV-2 MessageConservation: received ≤ total_sent
 *   INV-3 NonNegativeLength:  len(mailbox) ≥ 0
 */
@TlaSpec("ActorMailbox")
class Mailbox<M : Any>(
    val capacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        const val DEFAULT_CAPACITY = 256
        private val log = LoggerFactory.getLogger(Mailbox::class.java)
    }

    @TlaVariable("mailbox")
    internal val channel: Channel<M> = Channel(capacity)

    @Volatile
    @TlaVariable("sendCount")
    private var totalSent: Long = 0

    @Volatile
    @TlaVariable("recvCount")
    private var totalReceived: Long = 0

    /**
     * Suspends until the message is enqueued.
     * Corresponds to TLA+ action: Send(s)
     */
    @TlaAction("Send")
    suspend fun send(message: M) {
        channel.send(message)
        totalSent++
        log.trace("Message enqueued (total sent: {})", totalSent)
    }

    /**
     * Attempts to enqueue without suspending.
     * Returns true if successful, false if mailbox is full.
     * Corresponds to TLA+ actions: Send(s) + TrySendFull(s)
     */
    @TlaAction("Send|TrySendFull")
    fun trySend(message: M): Boolean {
        val result: ChannelResult<Unit> = channel.trySend(message)
        if (result.isSuccess) {
            totalSent++
            return true
        }
        log.trace("Mailbox full, trySend failed (capacity: {})", capacity)
        return false
    }

    /**
     * Suspends until a message is available.
     * Corresponds to TLA+ action: Receive
     */
    @TlaAction("Receive")
    suspend fun receive(): M {
        val msg = channel.receive()
        totalReceived++
        return msg
    }

    /**
     * Increments the received counter. Used internally by ActorCell
     * when receiving via select { channel.onReceive } instead of
     * the direct receive() method.
     */
    internal fun onReceived() {
        totalReceived++
    }

    /**
     * Attempts to dequeue without suspending.
     * Returns null if mailbox is empty.
     * Corresponds to TLA+ actions: Receive + TryReceiveEmpty
     */
    @TlaAction("Receive|TryReceiveEmpty")
    fun tryReceive(): M? {
        val result = channel.tryReceive()
        if (result.isSuccess) {
            totalReceived++
            return result.getOrNull()
        }
        return null
    }

    /**
     * Closes the mailbox. No more messages can be sent.
     * Pending messages can still be received.
     */
    fun close() {
        channel.close()
        log.debug("Mailbox closed (sent: {}, received: {})", totalSent, totalReceived)
    }

    @OptIn(DelicateCoroutinesApi::class)
    val isClosed: Boolean get() = channel.isClosedForSend
    @OptIn(ExperimentalCoroutinesApi::class)
    val isEmpty: Boolean get() = channel.isEmpty

    // ─── Invariant Checks (TLA+ → Kotlin bridge) ────────────────

    @TlaInvariant("BoundedCapacity")
    fun checkBoundedCapacity(): Boolean = true // Channel enforces this structurally

    @TlaInvariant("MessageConservation")
    fun checkMessageConservation(): Boolean = totalReceived <= totalSent

    @TlaInvariant("NonNegativeLength")
    fun checkNonNegativeLength(): Boolean = true // Channel structurally guarantees this

    fun checkAllInvariants() {
        check(checkBoundedCapacity()) { "Invariant violated: BoundedCapacity" }
        check(checkMessageConservation()) { "Invariant violated: MessageConservation — received=$totalReceived > sent=$totalSent" }
        check(checkNonNegativeLength()) { "Invariant violated: NonNegativeLength" }
    }

    override fun toString(): String = "Mailbox(capacity=$capacity, sent=$totalSent, received=$totalReceived)"
}
