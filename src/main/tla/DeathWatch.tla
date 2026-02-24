---- MODULE DeathWatch ----
\* ═══════════════════════════════════════════════════════════════════
\* SPEC 5: DeathWatch (Actor Monitoring)
\* ═══════════════════════════════════════════════════════════════════
\*
\* Models the watch/unwatch/Terminated pattern for failure detection.
\* An actor can watch another; when the watched actor dies, the
\* watcher receives a Terminated signal.
\*
\* Key properties verified:
\*   - No phantom Terminated (only for actually dead actors)
\*   - No self-watching
\*   - Alive actors never appear in terminated sets
\*   - Watch symmetry holds for alive actors
\*   - Unwatch prevents Terminated delivery (by construction)
\*   - Watching a dead actor delivers Terminated immediately
\*
\* State space: 4 actors → ~100K states, TLC <1min
\*
\* Maps to Kotlin:
\*   alive       → ActorCell.stateRef != STOPPED
\*   watching    → ActorCell.watching (CopyOnWriteArraySet)
\*   watchers    → ActorCell.watchers (CopyOnWriteArraySet)
\*   terminated  → Signal.Terminated delivered via signalChannel
\*   Watch       → ActorCell.watch() / ActorContext.watch()
\*   Unwatch     → ActorCell.unwatch() / ActorContext.unwatch()
\*   Die         → ActorCell.performStop() → notifyWatchers()

EXTENDS Naturals, FiniteSets

CONSTANTS
    Actors          \* set of actor IDs, e.g. {a1, a2, a3, a4}

VARIABLES
    alive,          \* SUBSET Actors — set of currently alive actors
    watchers,       \* [Actors -> SUBSET Actors] — actors watching this actor
    watching,       \* [Actors -> SUBSET Actors] — actors this actor is watching
    terminated      \* [Actors -> SUBSET Actors] — Terminated signals received

vars == <<alive, watchers, watching, terminated>>

TypeOK ==
    /\ alive \subseteq Actors
    /\ watchers \in [Actors -> SUBSET Actors]
    /\ watching \in [Actors -> SUBSET Actors]
    /\ terminated \in [Actors -> SUBSET Actors]

Init ==
    /\ alive = Actors               \* All actors start alive
    /\ watchers = [a \in Actors |-> {}]
    /\ watching = [a \in Actors |-> {}]
    /\ terminated = [a \in Actors |-> {}]

\* ─── Actions ─────────────────────────────────────────────────────

\* Actor w starts watching target.
\* If target is alive: registers bidirectionally.
\* If target is already dead: delivers Terminated immediately.
\* Maps to: ActorCell.watch() (single code path handles both cases)
Watch(w, target) ==
    /\ w /= target                      \* No self-watching
    /\ w \in alive                      \* Watcher must be alive
    /\ target \notin watching[w]        \* Not already watching
    /\ watchers' = [watchers EXCEPT ![target] = @ \cup {w}]
    /\ watching' = [watching EXCEPT ![w] = @ \cup {target}]
    /\ IF target \notin alive
       THEN terminated' = [terminated EXCEPT ![w] = @ \cup {target}]
       ELSE UNCHANGED terminated
    /\ UNCHANGED alive

\* Actor w stops watching target.
\* Removes bidirectional registration. No more signals.
\* Maps to: ActorCell.unwatch() / ActorContext.unwatch()
Unwatch(w, target) ==
    /\ w \in alive                      \* Watcher must be alive
    /\ target \in watching[w]           \* Must currently be watching
    /\ watchers' = [watchers EXCEPT ![target] = @ \ {w}]
    /\ watching' = [watching EXCEPT ![w] = @ \ {target}]
    /\ UNCHANGED <<alive, terminated>>

\* Actor dies. Delivers Terminated to all alive watchers.
\* Cleans up: removes self from watchers of things it was watching.
\* Maps to: ActorCell.performStop() → notifyWatchers() + watching cleanup
Die(a) ==
    /\ a \in alive
    /\ alive' = alive \ {a}
    \* Deliver Terminated to all alive watchers of 'a'
    /\ terminated' = [w \in Actors |->
        IF w \in watchers[a] /\ w \in alive     \* Only alive watchers receive
        THEN terminated[w] \cup {a}
        ELSE terminated[w]]
    \* Remove 'a' from watchers of everything 'a' was watching
    /\ watchers' = [t \in Actors |->
        IF t \in watching[a]                     \* 'a' was watching 't'
        THEN watchers[t] \ {a}                   \* Remove 'a' from t's watchers
        ELSE IF t = a                            \* Clear a's own watchers (optional)
        THEN {}
        ELSE watchers[t]]
    \* Clear 'a's watching set
    /\ watching' = [watching EXCEPT ![a] = {}]

Next ==
    \/ \E w, t \in Actors : Watch(w, t)
    \/ \E w, t \in Actors : Unwatch(w, t)
    \/ \E a \in Actors : Die(a)
    \* Terminal state: all actors dead (stutter)
    \/ alive = {} /\ UNCHANGED vars

\* ─── Safety Invariants ───────────────────────────────────────────

\* INV-1: Terminated signals are only for dead actors.
\* You never get a Terminated for someone who is still alive.
NoPhantomTerminated == \A w \in Actors : \A t \in terminated[w] :
    t \notin alive

\* INV-2: No actor watches itself.
NoSelfWatch == \A a \in Actors : a \notin watching[a]

\* INV-3: An alive actor has never received Terminated for another alive actor.
\* (Redundant with INV-1 but more specific to alive watchers)
AliveNotTerminatedByAlive == \A w \in alive : \A t \in alive :
    t \notin terminated[w]

\* INV-4: Watch symmetry for alive actors — if alive w watches alive t,
\* then w appears in t's watchers set.
WatchSymmetryAlive == \A w \in alive : \A t \in watching[w] :
    t \in alive => w \in watchers[t]

\* INV-5: Terminated set is bounded — you can only have Terminated
\* for actors in the system (no phantom IDs).
TerminatedBounded == \A w \in Actors : terminated[w] \subseteq Actors

\* INV-6: A dead actor has empty watching set (cleaned up on death).
DeadNotWatching == \A a \in Actors :
    a \notin alive => watching[a] = {}

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_vars

====
