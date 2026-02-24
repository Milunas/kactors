package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * MESSAGE PROTOCOLS: Common Actor Messaging Patterns
 * ═══════════════════════════════════════════════════════════════════
 *
 * Reusable message protocol types for common actor patterns.
 * These demonstrate idiomatic actor design and serve as building
 * blocks for actor-based applications.
 *
 * Note: Lifecycle events are delivered via [Signal], not messages.
 * Signals (PreStart, PostStop, Terminated, ChildFailed) are handled
 * separately via [Behavior.onSignal].
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
 * Status response used in health checks and monitoring.
 */
data class StatusResponse(
    val actorName: String,
    val state: ActorState,
    val processedMessages: Int,
    val restartCount: Int
)
