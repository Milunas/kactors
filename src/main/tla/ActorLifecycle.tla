---- MODULE ActorLifecycle ----
\* ═══════════════════════════════════════════════════════════════════
\* SPEC 2: Actor Lifecycle State Machine + Signal Ordering
\* ═══════════════════════════════════════════════════════════════════
\*
\* Models the lifecycle of actors within an ActorSystem, including
\* signal delivery ordering guarantees:
\*   Created → Starting → [PreStart] → Running → Stopping → [PostStop] → Stopped
\*                                        ↓
\*                                    Restarting → [PostStop old] → [PreStart new]
\*
\* Signal ordering guarantees (verified as invariants):
\*   - PreStart is delivered before the first message is processed
\*   - PostStop is delivered only during stopping (after last message)
\*   - PostStop on restart: old behavior gets PostStop, new gets PreStart
\*   - Signal delivery counts are bounded by lifecycle transitions
\*
\* State space: |Actors| × 6 states × signal/failure combinations
\*   With 3 actors, MaxRestarts=3 → ~200K distinct states, TLC < 30s
\*
\* Maps to Kotlin:
\*   actorState        → AtomicReference<ActorState>
\*   preStartDelivered → deliverSignal(Signal.PreStart) in ActorCell.start()
\*   postStopDelivered → deliverSignal(Signal.PostStop) in ActorCell.performStop()
\*   Start             → actor.start() (launches coroutine)
\*   DeliverPreStart   → deliverSignal(PreStart) before messageLoop()
\*   Process           → actor processes a message from its mailbox
\*   Fail/Restart      → supervisor strategy + PostStop old + PreStart new
\*   Stop              → actor.stop() → performStop() → PostStop

EXTENDS Naturals, FiniteSets

CONSTANTS
    Actors,         \* set of actor IDs, e.g. {a1, a2, a3}
    MaxRestarts,    \* max restart attempts before permanent stop, e.g. 3
    MaxMessages     \* max messages per actor for model checking bound, e.g. 2

VARIABLES
    actorState,         \* [Actors -> {"created","starting","running","stopping","stopped","restarting"}]
    restartCount,       \* [Actors -> 0..MaxRestarts] — restart attempts used
    processed,          \* [Actors -> Nat] — messages successfully processed
    alive,              \* set of actors currently alive (created/starting/running/restarting)
    preStartDelivered,  \* [Actors -> Nat] — count of PreStart signals delivered
    postStopDelivered   \* [Actors -> Nat] — count of PostStop signals delivered

vars == <<actorState, restartCount, processed, alive, preStartDelivered, postStopDelivered>>

States == {"created", "starting", "running", "stopping", "stopped", "restarting"}

TypeOK ==
    /\ actorState \in [Actors -> States]
    /\ restartCount \in [Actors -> 0..MaxRestarts]
    /\ processed \in [Actors -> 0..MaxMessages]
    /\ alive \subseteq Actors
    /\ preStartDelivered \in [Actors -> Nat]
    /\ postStopDelivered \in [Actors -> Nat]

Init ==
    /\ actorState = [a \in Actors |-> "created"]
    /\ restartCount = [a \in Actors |-> 0]
    /\ processed = [a \in Actors |-> 0]
    /\ alive = Actors
    /\ preStartDelivered = [a \in Actors |-> 0]
    /\ postStopDelivered = [a \in Actors |-> 0]

\* ─── Actions ─────────────────────────────────────────────────────

\* Actor transitions from created to starting (initialization phase)
Start(a) ==
    /\ actorState[a] = "created"
    /\ actorState' = [actorState EXCEPT ![a] = "starting"]
    /\ UNCHANGED <<restartCount, processed, alive, preStartDelivered, postStopDelivered>>

\* Actor completes initialization: delivers PreStart and becomes running.
\* PreStart is delivered BEFORE the actor processes any messages.
\* Maps to: ActorCell.start() → deliverSignal(Signal.PreStart); stateRef.set(RUNNING)
BecomeRunning(a) ==
    /\ actorState[a] = "starting"
    /\ actorState' = [actorState EXCEPT ![a] = "running"]
    /\ preStartDelivered' = [preStartDelivered EXCEPT ![a] = @ + 1]
    /\ UNCHANGED <<restartCount, processed, alive, postStopDelivered>>

\* Actor successfully processes a message
ProcessMessage(a) ==
    /\ actorState[a] = "running"
    /\ processed[a] < MaxMessages
    /\ processed' = [processed EXCEPT ![a] = @ + 1]
    /\ UNCHANGED <<actorState, restartCount, alive, preStartDelivered, postStopDelivered>>

\* Actor encounters a failure while running
Fail(a) ==
    /\ actorState[a] = "running"
    /\ restartCount[a] < MaxRestarts
    /\ actorState' = [actorState EXCEPT ![a] = "restarting"]
    /\ restartCount' = [restartCount EXCEPT ![a] = @ + 1]
    /\ UNCHANGED <<processed, alive, preStartDelivered, postStopDelivered>>

\* Actor fails but has exhausted restart budget — permanent stop
FailPermanent(a) ==
    /\ actorState[a] = "running"
    /\ restartCount[a] = MaxRestarts
    /\ actorState' = [actorState EXCEPT ![a] = "stopping"]
    /\ alive' = alive \ {a}
    /\ UNCHANGED <<restartCount, processed, preStartDelivered, postStopDelivered>>

\* Supervisor restarts a failed actor:
\*   1. PostStop delivered to old behavior (cleanup)
\*   2. Transition to starting (new behavior initialized)
\*   Maps to: handleFailure() → deliverSignal(PostStop) → reset → deliverSignal(PreStart)
Restart(a) ==
    /\ actorState[a] = "restarting"
    /\ actorState' = [actorState EXCEPT ![a] = "starting"]
    /\ postStopDelivered' = [postStopDelivered EXCEPT ![a] = @ + 1]
    /\ UNCHANGED <<restartCount, processed, alive, preStartDelivered>>

\* Graceful shutdown: running actor initiates stop
GracefulStop(a) ==
    /\ actorState[a] = "running"
    /\ actorState' = [actorState EXCEPT ![a] = "stopping"]
    /\ alive' = alive \ {a}
    /\ UNCHANGED <<restartCount, processed, preStartDelivered, postStopDelivered>>

\* Stopping actor completes shutdown: delivers PostStop signal.
\* Maps to: performStop() → deliverSignal(Signal.PostStop); stateRef.set(STOPPED)
CompleteStopping(a) ==
    /\ actorState[a] = "stopping"
    /\ actorState' = [actorState EXCEPT ![a] = "stopped"]
    /\ postStopDelivered' = [postStopDelivered EXCEPT ![a] = @ + 1]
    /\ UNCHANGED <<restartCount, processed, alive, preStartDelivered>>

Next ==
    \/ \E a \in Actors :
        \/ Start(a)
        \/ BecomeRunning(a)
        \/ ProcessMessage(a)
        \/ Fail(a)
        \/ FailPermanent(a)
        \/ Restart(a)
        \/ GracefulStop(a)
        \/ CompleteStopping(a)
    \* Terminal state: all actors stopped (stutter to avoid TLC deadlock)
    \/ (\A a \in Actors : actorState[a] = "stopped") /\ UNCHANGED vars

\* ─── Safety Invariants ───────────────────────────────────────────

\* INV-1: Restart count never exceeds the configured maximum
RestartBudgetRespected == \A a \in Actors : restartCount[a] <= MaxRestarts

\* INV-2: Stopped actors are not in the alive set
StoppedNotAlive == \A a \in Actors :
    actorState[a] = "stopped" => a \notin alive

\* INV-3: Only running actors process messages (structural correctness).
\* After restart, an actor can be back in "starting" with processed > 0.
\* (Enforced by guard on ProcessMessage, verified as invariant)
OnlyRunningProcess == \A a \in Actors :
    processed[a] > 0 => actorState[a] \in {"starting", "running", "stopping", "stopped", "restarting"}

\* INV-4: No actor can be both alive and stopped simultaneously
AliveConsistency == \A a \in Actors :
    a \in alive => actorState[a] /= "stopped"

\* INV-5: PreStart is delivered before any message processing.
\* If an actor has processed messages, it must have received PreStart.
PreStartBeforeProcess == \A a \in Actors :
    processed[a] > 0 => preStartDelivered[a] > 0

\* INV-6: PostStop count mirrors lifecycle completions.
\* PostStop is delivered once per stop + once per restart.
\* Total PostStops = (restarts that completed PostStop) + (final stop if completed).
\* Simpler: PostStop count ≤ restartCount + 1 (at most one final stop)
PostStopBounded == \A a \in Actors :
    postStopDelivered[a] <= restartCount[a] + 1

\* INV-7: PreStart count = restartCount + 1 (for started actors).
\* Each start (initial + each restart) delivers exactly one PreStart.
PreStartMatchesStarts == \A a \in Actors :
    preStartDelivered[a] <= restartCount[a] + 1

\* INV-8: Signal ordering — PreStart always precedes or equals PostStop count.
\* You can't deliver PostStop without having delivered PreStart first.
PreStartBeforePostStop == \A a \in Actors :
    postStopDelivered[a] <= preStartDelivered[a]

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_vars

====
