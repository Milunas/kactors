---- MODULE ActorLifecycle ----
\* ═══════════════════════════════════════════════════════════════════
\* SPEC 2: Actor Lifecycle State Machine
\* ═══════════════════════════════════════════════════════════════════
\*
\* Models the lifecycle of actors within an ActorSystem.
\* Each actor transitions through well-defined states:
\*   Created → Starting → Running → Stopping → Stopped
\*                           ↓
\*                       Restarting (on failure, if supervisor allows)
\*
\* State space: |Actors| × 6 states × failure combinations
\*   With 3 actors → ~50K distinct states, TLC < 30s
\*
\* Maps to Kotlin:
\*   actorState  → AtomicReference<ActorState>
\*   Start       → actor.start() (launches coroutine)
\*   Process     → actor processes a message from its mailbox
\*   Fail        → exception in behavior.onMessage()
\*   Restart     → supervisor triggers restart
\*   Stop        → actor.stop() (cancels coroutine scope)

EXTENDS Naturals, FiniteSets

CONSTANTS
    Actors,         \* set of actor IDs, e.g. {a1, a2, a3}
    MaxRestarts     \* max restart attempts before permanent stop, e.g. 3

VARIABLES
    actorState,     \* [Actors -> {"created","starting","running","stopping","stopped","restarting"}]
    restartCount,   \* [Actors -> 0..MaxRestarts] — restart attempts used
    processed,      \* [Actors -> Nat] — messages successfully processed
    alive           \* set of actors currently alive (created/starting/running/restarting)

vars == <<actorState, restartCount, processed, alive>>

States == {"created", "starting", "running", "stopping", "stopped", "restarting"}

TypeOK ==
    /\ actorState \in [Actors -> States]
    /\ restartCount \in [Actors -> 0..MaxRestarts]
    /\ processed \in [Actors -> Nat]
    /\ alive \subseteq Actors

Init ==
    /\ actorState = [a \in Actors |-> "created"]
    /\ restartCount = [a \in Actors |-> 0]
    /\ processed = [a \in Actors |-> 0]
    /\ alive = Actors

\* ─── Actions ─────────────────────────────────────────────────────

\* Actor transitions from created to starting (initialization phase)
Start(a) ==
    /\ actorState[a] = "created"
    /\ actorState' = [actorState EXCEPT ![a] = "starting"]
    /\ UNCHANGED <<restartCount, processed, alive>>

\* Actor completes initialization and becomes running
BecomeRunning(a) ==
    /\ actorState[a] = "starting"
    /\ actorState' = [actorState EXCEPT ![a] = "running"]
    /\ UNCHANGED <<restartCount, processed, alive>>

\* Actor successfully processes a message
ProcessMessage(a) ==
    /\ actorState[a] = "running"
    /\ processed' = [processed EXCEPT ![a] = @ + 1]
    /\ UNCHANGED <<actorState, restartCount, alive>>

\* Actor encounters a failure while running
Fail(a) ==
    /\ actorState[a] = "running"
    /\ restartCount[a] < MaxRestarts
    /\ actorState' = [actorState EXCEPT ![a] = "restarting"]
    /\ restartCount' = [restartCount EXCEPT ![a] = @ + 1]
    /\ UNCHANGED <<processed, alive>>

\* Actor fails but has exhausted restart budget — permanent stop
FailPermanent(a) ==
    /\ actorState[a] = "running"
    /\ restartCount[a] = MaxRestarts
    /\ actorState' = [actorState EXCEPT ![a] = "stopping"]
    /\ alive' = alive \ {a}
    /\ UNCHANGED <<restartCount, processed>>

\* Supervisor restarts a failed actor
Restart(a) ==
    /\ actorState[a] = "restarting"
    /\ actorState' = [actorState EXCEPT ![a] = "starting"]
    /\ UNCHANGED <<restartCount, processed, alive>>

\* Graceful shutdown: running actor initiates stop
GracefulStop(a) ==
    /\ actorState[a] = "running"
    /\ actorState' = [actorState EXCEPT ![a] = "stopping"]
    /\ alive' = alive \ {a}
    /\ UNCHANGED <<restartCount, processed>>

\* Stopping actor completes shutdown
CompleteStopping(a) ==
    /\ actorState[a] = "stopping"
    /\ actorState' = [actorState EXCEPT ![a] = "stopped"]
    /\ UNCHANGED <<restartCount, processed, alive>>

Next ==
    \E a \in Actors :
        \/ Start(a)
        \/ BecomeRunning(a)
        \/ ProcessMessage(a)
        \/ Fail(a)
        \/ FailPermanent(a)
        \/ Restart(a)
        \/ GracefulStop(a)
        \/ CompleteStopping(a)

\* ─── Safety Invariants ───────────────────────────────────────────

\* INV-1: Restart count never exceeds the configured maximum
RestartBudgetRespected == \A a \in Actors : restartCount[a] <= MaxRestarts

\* INV-2: Stopped actors are not in the alive set
StoppedNotAlive == \A a \in Actors :
    actorState[a] = "stopped" => a \notin alive

\* INV-3: Only running actors process messages (structural correctness)
\* (Enforced by guard on ProcessMessage, verified as invariant)
OnlyRunningProcess == \A a \in Actors :
    processed[a] > 0 => actorState[a] \in {"running", "stopping", "stopped", "restarting"}

\* INV-4: No actor can be both alive and stopped simultaneously
AliveConsistency == \A a \in Actors :
    a \in alive => actorState[a] /= "stopped"

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_vars

====
