package com.actors

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

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
 *
 * Internally, messages are wrapped in [MessageEnvelope] to carry
 * trace metadata (sender path, Lamport timestamp, TraceContext)
 * without changing the user-facing API. The envelope is unwrapped
 * by [ActorCell] before delivering to the behavior.
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
    internal val channel: Channel<MessageEnvelope<M>> = Channel(capacity)

    @TlaVariable("sendCount")
    private val totalSent = AtomicLong(0)

    @TlaVariable("recvCount")
    private val totalReceived = AtomicLong(0)

    /**
     * Suspends until the message is enqueued.
     * Corresponds to TLA+ action: Send(s)
     *
     * @param message The message to send
     * @param traceContext Trace context from the sender (null if external)
     * @param senderPath Path of the sending actor (null if external)
     * @param senderLamportTime Sender's Lamport timestamp at send time
     * @param messageSnapshot Optional toString() capture for replay
     */
    @TlaAction("Send")
    suspend fun send(
        message: M,
        traceContext: TraceContext? = null,
        senderPath: String? = null,
        senderLamportTime: Long = 0,
        messageSnapshot: String? = null
    ) {
        val envelope = MessageEnvelope(message, traceContext, senderPath, senderLamportTime, messageSnapshot)
        channel.send(envelope)
        totalSent.incrementAndGet()
        log.trace("Message enqueued (total sent: {})", totalSent.get())
    }

    /**
     * Attempts to enqueue without suspending.
     * Returns true if successful, false if mailbox is full.
     * Corresponds to TLA+ actions: Send(s) + TrySendFull(s)
     *
     * @param message The message to send
     * @param traceContext Trace context from the sender (null if external)
     * @param senderPath Path of the sending actor (null if external)
     * @param senderLamportTime Sender's Lamport timestamp at send time
     * @param messageSnapshot Optional toString() capture for replay
     */
    @TlaAction("Send|TrySendFull")
    fun trySend(
        message: M,
        traceContext: TraceContext? = null,
        senderPath: String? = null,
        senderLamportTime: Long = 0,
        messageSnapshot: String? = null
    ): Boolean {
        val envelope = MessageEnvelope(message, traceContext, senderPath, senderLamportTime, messageSnapshot)
        val result: ChannelResult<Unit> = channel.trySend(envelope)
        if (result.isSuccess) {
            totalSent.incrementAndGet()
            return true
        }
        log.trace("Mailbox full, trySend failed (capacity: {})", capacity)
        return false
    }

    /**
     * Suspends until a message is available.
     * Unwraps the internal envelope — callers get the raw message.
     * Corresponds to TLA+ action: Receive
     */
    @TlaAction("Receive")
    suspend fun receive(): M {
        val envelope = channel.receive()
        totalReceived.incrementAndGet()
        return envelope.message
    }

    /**
     * Increments the received counter. Used internally by ActorCell
     * when receiving via select { channel.onReceive } instead of
     * the direct receive() method.
     */
    internal fun onReceived() {
        totalReceived.incrementAndGet()
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
            totalReceived.incrementAndGet()
            return result.getOrNull()?.message
        }
        return null
    }

    /**
     * Closes the mailbox. No more messages can be sent.
     * Pending messages can still be received.
     */
    fun close() {
        channel.close()
        log.debug("Mailbox closed (sent: {}, received: {})", totalSent.get(), totalReceived.get())
    }

    @OptIn(DelicateCoroutinesApi::class)
    val isClosed: Boolean get() = channel.isClosedForSend
    @OptIn(ExperimentalCoroutinesApi::class)
    val isEmpty: Boolean get() = channel.isEmpty

    // ─── Invariant Checks (TLA+ → Kotlin bridge) ────────────────

    @TlaInvariant("BoundedCapacity")
    fun checkBoundedCapacity(): Boolean = true // Channel enforces this structurally

    @TlaInvariant("MessageConservation")
    fun checkMessageConservation(): Boolean = totalReceived.get() <= totalSent.get()

    @TlaInvariant("NonNegativeLength")
    fun checkNonNegativeLength(): Boolean = true // Channel structurally guarantees this

    fun checkAllInvariants() {
        check(checkBoundedCapacity()) { "Invariant violated: BoundedCapacity" }
        check(checkMessageConservation()) { "Invariant violated: MessageConservation — received=${totalReceived.get()} > sent=${totalSent.get()}" }
        check(checkNonNegativeLength()) { "Invariant violated: NonNegativeLength" }
    }

    override fun toString(): String = "Mailbox(capacity=$capacity, sent=${totalSent.get()}, received=${totalReceived.get()})"
}
