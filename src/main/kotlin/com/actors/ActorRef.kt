package com.actors

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR REF: Type-Safe Handle to an Actor
 * ═══════════════════════════════════════════════════════════════════
 *
 * An ActorRef is the ONLY way to communicate with an actor.
 * It encapsulates the actor's mailbox and provides:
 *   - tell (fire-and-forget): enqueues without waiting
 *   - ask  (request-reply):   enqueues and awaits a response
 *
 * TLA+ Spec: RequestReply.tla (ask pattern)
 *
 * ActorRefs are:
 *   - Thread-safe: backed by a Channel (MPSC)
 *   - Location-transparent: in distributed mode, the ref can
 *     route to a remote node (future extension)
 *   - Serializable (future): for actor discovery across nodes
 */
@TlaSpec("RequestReply|ActorMailbox")
class ActorRef<M : Any> internal constructor(
    val name: String,
    internal val mailbox: Mailbox<M>,
    internal val actorCell: ActorCell<M>
) {
    companion object {
        private val log = LoggerFactory.getLogger(ActorRef::class.java)
    }

    /**
     * Fire-and-forget: enqueue a message into the actor's mailbox.
     * Suspends if the mailbox is full (backpressure).
     *
     * Automatically captures the sender's identity from the coroutine context
     * (if called from within an actor's message handler) and propagates:
     *   - Sender path (for "who sent what to whom" tracing)
     *   - Sender's Lamport timestamp (for causal ordering)
     *   - TraceContext (for distributed trace correlation)
     *   - Optional message snapshot (if enabled on sender)
     *
     * TLA+ action: Send(s) in ActorMailbox.tla
     */
    @TlaAction("Send")
    suspend fun tell(message: M) {
        // Capture sender info from coroutine context (if inside an actor)
        val senderElement = coroutineContext[ActorTraceElement]
        val senderPath = senderElement?.actorPath
        val senderCell = senderElement?.cell
        val senderLamport = senderCell?.flightRecorder?.currentLamportTime ?: 0
        val traceCtx = senderCell?.currentTraceContext
        val snapshot = if (senderCell?.enableMessageSnapshots == true) message.toString() else null

        // Record MessageSent in sender's flight recorder
        if (senderCell != null) {
            val childSpan = traceCtx?.newChildSpan()
            senderCell.flightRecorder.record(TraceEvent.MessageSent(
                actorPath = senderPath ?: "",
                timestamp = Instant.now(),
                lamportTimestamp = senderCell.flightRecorder.nextLamportTimestamp(),
                targetPath = name,
                messageType = message::class.simpleName ?: "Unknown",
                traceContext = childSpan,
                messageContent = snapshot
            ))
            // Send with trace context
            mailbox.send(message, childSpan, senderPath, senderLamport, snapshot)
        } else {
            // External sender (test code, main thread) — no trace context
            mailbox.send(message)
        }
    }

    /**
     * Non-blocking tell: attempts to enqueue without suspension.
     * Returns false if the mailbox is full.
     *
     * Note: since this is non-suspend, it cannot capture the sender's
     * coroutine context. Trace metadata will be absent for trySend calls.
     * For full tracing, prefer the suspend `tell()` method.
     *
     * TLA+ action: Send(s) | TrySendFull(s) in ActorMailbox.tla
     */
    fun tryTell(message: M): Boolean = mailbox.trySend(message)

    /**
     * Request-Reply (Ask Pattern): sends a message and waits for a response.
     *
     * The caller provides a factory that creates the message given a reply-to ref.
     * The actor must send exactly one reply to complete the deferred.
     *
     * TLA+ Spec: RequestReply.tla
     *   - SendRequest(c):     this function creates the deferred + enqueues
     *   - DeliverReply(c,rid): the actor completes the deferred via replyTo.tell()
     *   - Timeout(c,rid):      withTimeout cancels the deferred
     *
     * Example:
     * ```kotlin
     * val count: Int = counterRef.ask { replyTo -> GetCount(replyTo) }
     * ```
     *
     * @param timeout Maximum time to wait for a reply
     * @param messageFactory Creates the request message given a reply-to ActorRef
     * @return The reply value
     * @throws kotlinx.coroutines.TimeoutCancellationException if timeout expires
     */
    @TlaAction("SendRequest|DeliverReply|Timeout")
    suspend fun <R : Any> ask(
        timeout: Duration = 5.seconds,
        messageFactory: (replyTo: ActorRef<R>) -> M
    ): R {
        val deferred = CompletableDeferred<R>()
        val replyRef = createReplyRef<R>(deferred)
        val message = messageFactory(replyRef)

        // Capture sender info for the ask request (same as tell)
        val senderElement = coroutineContext[ActorTraceElement]
        val senderCell = senderElement?.cell
        val senderPath = senderElement?.actorPath
        val senderLamport = senderCell?.flightRecorder?.currentLamportTime ?: 0
        val traceCtx = senderCell?.currentTraceContext
        val snapshot = if (senderCell?.enableMessageSnapshots == true) message.toString() else null

        if (senderCell != null) {
            val childSpan = traceCtx?.newChildSpan()
            senderCell.flightRecorder.record(TraceEvent.MessageSent(
                actorPath = senderPath ?: "",
                timestamp = Instant.now(),
                lamportTimestamp = senderCell.flightRecorder.nextLamportTimestamp(),
                targetPath = name,
                messageType = message::class.simpleName ?: "Unknown",
                traceContext = childSpan,
                messageContent = snapshot
            ))
            mailbox.send(message, childSpan, senderPath, senderLamport, snapshot)
        } else {
            mailbox.send(message)
        }

        return withTimeout(timeout) {
            deferred.await()
        }
    }

    /**
     * Creates a temporary one-shot ActorRef that completes a deferred.
     * This is the "reply-to channel" from RequestReply.tla.
     */
    private fun <R : Any> createReplyRef(deferred: CompletableDeferred<R>): ActorRef<R> {
        val replyMailbox = Mailbox<R>(capacity = 1)
        val replyBehavior = Behavior<R> { _, reply ->
            deferred.complete(reply)
            Behavior.stopped()
        }
        val replyCell = ActorCell(
            name = "$name/reply-${System.nanoTime()}",
            initialBehavior = replyBehavior,
            mailbox = replyMailbox,
            supervisorStrategy = SupervisorStrategy.stop()
        )
        // Reply ref auto-processes — launch inline
        replyCell.startInline(actorCell.system)
        return replyCell.ref
    }

    val isAlive: Boolean get() = actorCell.state != ActorState.STOPPED

    override fun toString(): String = "ActorRef($name)"
    override fun equals(other: Any?): Boolean = other is ActorRef<*> && name == other.name
    override fun hashCode(): Int = name.hashCode()
}
