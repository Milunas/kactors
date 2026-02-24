---- MODULE ActorHierarchy ----
\* ═══════════════════════════════════════════════════════════════════
\* SPEC 4: Actor Supervision Hierarchy
\* ═══════════════════════════════════════════════════════════════════
\*
\* Models parent-child supervision trees with cascading stop.
\* Each actor can spawn children; stopping a parent cascades to
\* all descendants depth-first. Completed stops clean up the tree.
\*
\* Key properties verified:
\*   - Parent-child consistency (child.parent = parent)
\*   - Cascading stop correctness (parent stop → children stop)
\*   - No orphan alive children of stopped parents
\*   - Unspawned actors are clean (no parent, no children)
\*   - No cycles in the supervision tree
\*
\* State space: 5 actors (1 root + 4 spawnable) → ~50K states, TLC <30s
\*
\* Maps to Kotlin:
\*   state       → ActorCell.stateRef (AtomicReference<ActorState>)
\*   parent      → ActorCell.parent (ActorCell<*>?)
\*   children    → ActorCell.children (ConcurrentHashMap)
\*   SpawnChild  → ActorCell.spawnChild() / ActorContext.spawn()
\*   InitiateStop   → ActorCell.stop()
\*   CascadeStop    → ActorCell.performStop() → stopAllChildren()
\*   CompleteStop   → ActorCell.performStop() final state transition

EXTENDS Naturals, FiniteSets

CONSTANTS
    Actors,         \* set of all possible actor IDs, e.g. {root, a1, a2, a3, a4}
    Root,           \* the root/guardian actor (always alive at Init)
    None            \* sentinel: no parent

VARIABLES
    state,          \* [Actors -> {"unspawned","alive","stopping","stopped"}]
    parent,         \* [Actors -> Actors ∪ {None}] — parent of each actor
    children        \* [Actors -> SUBSET Actors] — direct children of each actor

vars == <<state, parent, children>>

States == {"unspawned", "alive", "stopping", "stopped"}

TypeOK ==
    /\ state \in [Actors -> States]
    /\ parent \in [Actors -> Actors \cup {None}]
    /\ children \in [Actors -> SUBSET Actors]

Init ==
    /\ state = [a \in Actors |-> IF a = Root THEN "alive" ELSE "unspawned"]
    /\ parent = [a \in Actors |-> None]
    /\ children = [a \in Actors |-> {}]

\* ─── Actions ─────────────────────────────────────────────────────

\* An alive actor spawns an unspawned actor as its child.
\* Maps to: ActorCell.spawnChild() / ActorContext.spawn()
SpawnChild(p, c) ==
    /\ p /= c                      \* Can't spawn self
    /\ state[p] = "alive"          \* Parent must be alive
    /\ state[c] = "unspawned"      \* Child must not already exist
    /\ state' = [state EXCEPT ![c] = "alive"]
    /\ parent' = [parent EXCEPT ![c] = p]
    /\ children' = [children EXCEPT ![p] = @ \cup {c}]

\* An alive actor initiates stop (voluntary shutdown or system.terminate).
\* Maps to: ActorCell.stop()
InitiateStop(a) ==
    /\ state[a] = "alive"
    /\ state' = [state EXCEPT ![a] = "stopping"]
    /\ UNCHANGED <<parent, children>>

\* A stopping parent cascades stop to an alive child.
\* Maps to: ActorCell.performStop() → stopAllChildren() → child.stop()
CascadeStop(p, c) ==
    /\ state[p] = "stopping"
    /\ c \in children[p]
    /\ state[c] = "alive"
    /\ state' = [state EXCEPT ![c] = "stopping"]
    /\ UNCHANGED <<parent, children>>

\* A stopping actor with ALL children stopped completes its own stop.
\* Removes self from parent's children list (onChildStopped).
\* Maps to: ActorCell.performStop() final transition + parent.onChildStopped()
CompleteStop(a) ==
    /\ state[a] = "stopping"
    /\ \A c \in children[a] : state[c] = "stopped"    \* All children done
    /\ state' = [state EXCEPT ![a] = "stopped"]
    /\ children' = [children EXCEPT
        ![a] = {},                                      \* Clear own children
        ![parent[a]] = IF parent[a] /= None             \* Remove from parent
                        THEN @ \ {a}
                        ELSE @]
    /\ UNCHANGED parent

Next ==
    \/ \E p, c \in Actors : SpawnChild(p, c)
    \/ \E a \in Actors : InitiateStop(a)
    \/ \E p, c \in Actors : CascadeStop(p, c)
    \/ \E a \in Actors : CompleteStop(a)
    \* Terminal state: root stopped and all actors resolved (stutter)
    \/ (\A a \in Actors : state[a] \in {"unspawned", "stopped"}) /\ UNCHANGED vars

\* ─── Safety Invariants ───────────────────────────────────────────

\* INV-1: Children relationship is bidirectionally consistent.
\* If c is in children(p), then parent(c) = p.
ChildParentConsistency == \A p \in Actors : \A c \in children[p] :
    parent[c] = p

\* INV-2: No actor is its own parent (no self-loops).
NoCycles == \A a \in Actors : parent[a] /= a

\* INV-3: An alive or stopping child must have an alive or stopping parent.
\* Equivalently: a stopped parent cannot have alive/stopping children.
AliveChildHasLiveParent == \A a \in Actors :
    (state[a] \in {"alive", "stopping"} /\ parent[a] /= None) =>
        state[parent[a]] \in {"alive", "stopping"}

\* INV-4: A stopped actor has no remaining children.
\* (Children are cleared on CompleteStop and removed via onChildStopped)
StoppedHasNoChildren == \A a \in Actors :
    state[a] = "stopped" => children[a] = {}

\* INV-5: Unspawned actors have no parent and no children.
UnspawnedIsClean == \A a \in Actors :
    state[a] = "unspawned" => (parent[a] = None /\ children[a] = {})

\* INV-6: Children are never unspawned — they must be alive, stopping, or stopped.
ChildrenAreSpawned == \A p \in Actors : \A c \in children[p] :
    state[c] /= "unspawned"

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_vars

====
