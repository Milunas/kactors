package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * MESSAGE PROTOCOLS: Common Actor Messaging Patterns
 * ═══════════════════════════════════════════════════════════════════
 *
 * Reusable message protocol types for common actor patterns.
 * These demonstrate idiomatic actor design and serve as building
 * blocks for actor-based applications.
 */

/**
 * A message that expects a reply (Ask pattern).
 * Carries the reply-to ActorRef for type-safe responses.
 *
 * TLA+ Spec: RequestReply.tla (SendRequest → DeliverReply)
 */
interface Request<R : Any> {
    val replyTo: ActorRef<R>
}

/**
 * Lifecycle signal messages sent by the system (not by users).
 * Future extension: watch/unwatch, terminated signals.
 */
sealed class SystemMessage {
    /** Sent when a watched actor terminates. */
    data class Terminated(val actorName: String) : SystemMessage()

    /** Poison pill: requests graceful shutdown. */
    data object PoisonPill : SystemMessage()
}

/**
 * Status response used in health checks and monitoring.
 */
data class StatusResponse(
    val actorName: String,
    val state: ActorState,
    val processedMessages: Int,
    val restartCount: Int
)
