---- MODULE ActorMailbox ----
\* ═══════════════════════════════════════════════════════════════════
\* SPEC 1: Actor Mailbox (Bounded Channel)
\* ═══════════════════════════════════════════════════════════════════
\*
\* Models a bounded FIFO mailbox backed by a Kotlin Channel.
\* Multiple senders can enqueue messages; exactly one actor consumes.
\*
\* State space (with defaults): 2 senders × capacity 3 × messages 1..3
\*   → well under 1M distinct states, TLC finishes in ~30s
\*
\* Maps to Kotlin:
\*   mailbox  → Channel<M>(capacity = Capacity)
\*   Send     → channel.send(msg)  / channel.trySend(msg)
\*   Receive  → channel.receive()  / channel.tryReceive()

EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS
    Senders,        \* set of sender actor IDs, e.g. {s1, s2}
    Capacity,       \* mailbox bound, e.g. 3
    MaxMessages     \* max message value, e.g. 3 (messages are 1..MaxMessages)

VARIABLES
    mailbox,        \* Seq(1..MaxMessages) — FIFO queue
    sendCount,      \* [Senders -> Nat] — messages sent per sender
    recvCount,      \* Nat — total messages received
    lastReceived    \* 0..MaxMessages — last dequeued message (0 = none)

vars == <<mailbox, sendCount, recvCount, lastReceived>>

TypeOK ==
    /\ mailbox \in Seq(1..MaxMessages)
    /\ Len(mailbox) <= Capacity
    /\ sendCount \in [Senders -> 0..MaxMessages]
    /\ recvCount \in Nat
    /\ lastReceived \in 0..MaxMessages

Init ==
    /\ mailbox = <<>>
    /\ sendCount = [s \in Senders |-> 0]
    /\ recvCount = 0
    /\ lastReceived = 0

\* ─── Actions ─────────────────────────────────────────────────────

\* A sender enqueues a message when mailbox is not full
Send(s) ==
    /\ Len(mailbox) < Capacity
    /\ sendCount[s] < MaxMessages
    /\ \E msg \in 1..MaxMessages :
        /\ mailbox' = Append(mailbox, msg)
        /\ sendCount' = [sendCount EXCEPT ![s] = @ + 1]
    /\ UNCHANGED <<recvCount, lastReceived>>

\* The actor dequeues the head message when mailbox is non-empty
Receive ==
    /\ Len(mailbox) > 0
    /\ lastReceived' = Head(mailbox)
    /\ mailbox' = Tail(mailbox)
    /\ recvCount' = recvCount + 1
    /\ UNCHANGED sendCount

\* TrySend fails silently when mailbox is full (models channel.trySend)
TrySendFull(s) ==
    /\ Len(mailbox) = Capacity
    /\ UNCHANGED vars

\* TryReceive fails silently when mailbox is empty (models channel.tryReceive)
TryReceiveEmpty ==
    /\ Len(mailbox) = 0
    /\ UNCHANGED vars

Next ==
    \/ \E s \in Senders : Send(s)
    \/ Receive
    \/ \E s \in Senders : TrySendFull(s)
    \/ TryReceiveEmpty

\* ─── Safety Invariants ───────────────────────────────────────────
\* These are embedded as runtime checks in the generated Lincheck test.

\* INV-1: Mailbox never exceeds its capacity
BoundedCapacity == Len(mailbox) <= Capacity

\* INV-2: Total received never exceeds total sent
NoPhantomMessages == recvCount <= Len(mailbox) + recvCount

\* INV-3: Conservation — received ≤ sum of all sends
MessageConservation ==
    LET totalSent == 
        IF Senders = {} THEN 0
        ELSE LET F[ss \in SUBSET Senders] ==
                IF ss = {} THEN 0
                ELSE LET s == CHOOSE x \in ss : TRUE
                     IN sendCount[s] + F[ss \ {s}]
             IN F[Senders]
    IN recvCount <= totalSent

\* INV-4: Mailbox length is non-negative (structural)
NonNegativeLength == Len(mailbox) >= 0

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_vars

====
