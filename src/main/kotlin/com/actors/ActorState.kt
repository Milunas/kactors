package com.actors

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR STATE: Lifecycle State Machine
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorLifecycle.tla (actorState variable)
 *
 * Valid transitions (corresponding TLA+ actions):
 *
 *   CREATED ──Start──▶ STARTING ──BecomeRunning──▶ RUNNING
 *                                                    │
 *                                          ┌─────────┼──────────┐
 *                                          │         │          │
 *                                      ProcessMsg   Fail    GracefulStop
 *                                          │         │          │
 *                                          ▼         ▼          ▼
 *                                       RUNNING  RESTARTING  STOPPING
 *                                                    │          │
 *                                                 Restart  CompleteStopping
 *                                                    │          │
 *                                                    ▼          ▼
 *                                                 STARTING   STOPPED
 */
enum class ActorState {
    /** Actor created but not yet started. TLA+: "created" */
    CREATED,

    /** Actor is initializing (running preStart hook). TLA+: "starting" */
    STARTING,

    /** Actor is processing messages. TLA+: "running" */
    RUNNING,

    /** Actor is being restarted by supervisor. TLA+: "restarting" */
    RESTARTING,

    /** Actor is shutting down (running postStop hook). TLA+: "stopping" */
    STOPPING,

    /** Actor is terminated. No more messages will be processed. TLA+: "stopped" */
    STOPPED;

    fun isAlive(): Boolean = this in setOf(CREATED, STARTING, RUNNING, RESTARTING)
    fun canProcess(): Boolean = this == RUNNING
}
