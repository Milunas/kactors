package com.actors

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
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
     * TLA+ action: Send(s) in ActorMailbox.tla
     */
    @TlaAction("Send")
    suspend fun tell(message: M) {
        mailbox.send(message)
    }

    /**
     * Non-blocking tell: attempts to enqueue without suspension.
     * Returns false if the mailbox is full.
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
        tell(message)
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
        val replyBehavior = Behavior<R> { reply ->
            deferred.complete(reply)
            Behavior.stopped()
        }
        val replyCell = ActorCell(
            name = "$name/reply-${System.nanoTime()}",
            initialBehavior = replyBehavior,
            mailbox = replyMailbox,
            supervisorStrategy = SupervisorStrategy.stop()
        )
        return ActorRef("$name/reply", replyMailbox, replyCell).also {
            // Reply ref auto-processes — launch inline
            replyCell.startInline()
        }
    }

    val isAlive: Boolean get() = actorCell.state != ActorState.STOPPED

    override fun toString(): String = "ActorRef($name)"
    override fun equals(other: Any?): Boolean = other is ActorRef<*> && name == other.name
    override fun hashCode(): Int = name.hashCode()
}
