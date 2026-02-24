package com.actors

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════
 * TRACE CONTEXT: Distributed Tracing Correlation
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorTrace.tla (extends: sender/causality variables)
 *
 * A TraceContext carries the distributed tracing identifiers that
 * flow WITH messages between actors, enabling full causal chain
 * reconstruction. This is the actor-model equivalent of OpenTelemetry's
 * SpanContext / W3C Trace Context.
 *
 * Three identifiers form the causal chain:
 *   - traceId:      unique per end-to-end operation (e.g., one user request)
 *   - spanId:       unique per message send (one hop in the chain)
 *   - parentSpanId: links this span to the sender's span (causal parent)
 *
 * Together, these enable reconstruction of a complete DAG of actor
 * interactions from a flat event log. Unlike OpenTelemetry, which
 * requires external infrastructure (Jaeger, Zipkin), this tracing
 * is built into the actor runtime — zero external dependencies.
 *
 * Causal chain example:
 * ```
 * [External Request]
 *   traceId=abc, spanId=s1, parent=""
 *       └── Actor A processes, sends to B
 *           traceId=abc, spanId=s2, parent=s1
 *               └── Actor B processes, sends to C
 *                   traceId=abc, spanId=s3, parent=s2
 * ```
 *
 * The TraceContext is stored in the [MessageEnvelope] that wraps
 * every message in the actor's mailbox. The actor's coroutine
 * context carries the current TraceContext as an [ActorTraceElement].
 *
 * Design note: traceId uses UUID for uniqueness across JVM restarts.
 * spanId uses a compact counter (per-actor) for lower overhead.
 * The parent link is what makes the DAG reconstructible.
 *
 * Example:
 * ```kotlin
 * // System automatically propagates trace context:
 * // When actor A's behavior calls ref.tell(msg),
 * // the framework captures A's current span and creates a child span.
 * // Actor B receives the message with the full trace context.
 *
 * // For external entry points, create a new trace:
 * val ctx = TraceContext.create()
 * ```
 */
@TlaSpec("ActorTrace")
data class TraceContext(
    /** Unique identifier for the entire operation chain (UUID). */
    val traceId: String,

    /** Unique identifier for this specific span (message hop). */
    val spanId: String,

    /** Span ID of the causal parent (empty for root spans). */
    val parentSpanId: String
) {
    companion object {
        /** Thread-safe monotonic span counter for compact span IDs within a JVM. */
        private val spanCounter = AtomicLong(0)

        /** Empty trace context — used when no trace is active. */
        val EMPTY = TraceContext(traceId = "", spanId = "", parentSpanId = "")

        /**
         * Create a new root trace context (new operation chain).
         * Generates a fresh traceId and spanId.
         */
        fun create(): TraceContext = TraceContext(
            traceId = UUID.randomUUID().toString().take(8),
            spanId = nextSpanId(),
            parentSpanId = ""
        )

        /**
         * Generate a compact, unique span ID.
         * Uses a monotonic counter for minimal overhead (vs UUID).
         * Format: "s" + counter value (e.g., "s42").
         */
        private fun nextSpanId(): String = "s${spanCounter.incrementAndGet()}"
    }

    /** Whether this is a non-empty trace context. */
    val isActive: Boolean get() = traceId.isNotEmpty()

    /**
     * Create a child span within the same trace.
     * The new span has this span as its parent, establishing the causal link.
     * Called when an actor sends a message to another actor.
     *
     * @return A new TraceContext with the same traceId, a new spanId,
     *         and this span's ID as the parentSpanId.
     */
    fun newChildSpan(): TraceContext = TraceContext(
        traceId = traceId,
        spanId = nextSpanId(),
        parentSpanId = spanId
    )

    override fun toString(): String =
        if (isActive) "Trace($traceId/$spanId←$parentSpanId)"
        else "Trace(none)"
}
