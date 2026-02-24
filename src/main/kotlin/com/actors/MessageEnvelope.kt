package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * MESSAGE ENVELOPE: Internal Metadata Wrapper
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorTrace.tla (sender variable), ActorMailbox.tla
 *
 * An internal wrapper around user messages that carries tracing
 * metadata through the mailbox channel. The user never sees this;
 * it is unwrapped by ActorCell before delivering to the behavior.
 *
 * The envelope enables:
 *   1. Cross-actor tracing: sender path + Lamport timestamp flow with the message
 *   2. Distributed trace correlation: TraceContext (traceId/spanId) propagation
 *   3. Optional message snapshot: toString() capture for replay debugging
 *
 * Why an envelope instead of a parallel metadata queue?
 *   A parallel queue (ConcurrentLinkedQueue alongside the Channel) would
 *   get out of sync under concurrent sends because channel.send() is a
 *   suspend point where another sender can interleave. Bundling metadata
 *   with the message in the same Channel guarantees ordering consistency.
 *
 * Memory overhead: ~48 bytes per envelope (3 references + 1 long + 1 nullable String).
 * At 256 mailbox capacity, this adds ~12KB per actor — negligible.
 *
 * This class is internal to the library. Users interact only with
 * [ActorRef.tell] and [ActorRef.ask], which create envelopes transparently.
 *
 * Example (internal flow):
 * ```
 * ActorRef.tell(msg)
 *   → creates MessageEnvelope(msg, traceCtx, senderPath, lamportTime)
 *   → mailbox.send(envelope)
 *   → ActorCell.messageLoop() receives envelope
 *   → unwraps: message = envelope.message
 *   → records TraceEvent with sender info from envelope
 *   → delivers message to behavior.onMessage(ctx, message)
 * ```
 */
internal data class MessageEnvelope<M : Any>(
    /** The actual user message. */
    val message: M,

    /**
     * Trace context from the sender — enables distributed trace correlation.
     * Null when sent from outside an actor context (e.g., test code, main thread).
     */
    val traceContext: TraceContext? = null,

    /**
     * Full path of the sending actor (e.g., "system/parent/child").
     * Null when sent from outside an actor context.
     */
    val senderPath: String? = null,

    /**
     * Sender's Lamport timestamp at send time.
     * Used by the receiver to update its own Lamport clock:
     *   receiver.clock = max(receiver.clock, senderLamportTime) + 1
     * This establishes causal ordering across actors.
     */
    val senderLamportTime: Long = 0,

    /**
     * Optional string snapshot of the message for replay debugging.
     * Captured via toString() when message snapshotting is enabled.
     * Null by default to avoid memory overhead in production.
     */
    val messageSnapshot: String? = null
)
