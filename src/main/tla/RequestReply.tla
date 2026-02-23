---- MODULE RequestReply ----
\* ═══════════════════════════════════════════════════════════════════
\* SPEC 3: Request-Reply Pattern (Ask Pattern)
\* ═══════════════════════════════════════════════════════════════════
\*
\* Models the Ask pattern: a client actor sends a request to a
\* server actor and waits for a reply via a temporary reply-to channel.
\*
\* This is foundational for distributed actor systems where
\* request-response semantics are built on top of one-way messaging.
\*
\* State space: 2 clients × 1 server × 3 pending slots
\*   → ~200K distinct states, TLC finishes in ~1-2min
\*
\* Maps to Kotlin:
\*   pendingRequests → ConcurrentHashMap<RequestId, CompletableDeferred<R>>
\*   SendRequest    → actorRef.ask(msg) → creates deferred + sends
\*   ProcessRequest → server behavior processes and sends reply
\*   DeliverReply   → reply arrives, completes the deferred
\*   Timeout        → deferred.await() with timeout

EXTENDS Naturals, FiniteSets

CONSTANTS
    Clients,        \* set of client IDs, e.g. {c1, c2}
    MaxPending      \* max concurrent pending requests per client, e.g. 2

VARIABLES
    pendingRequests,    \* [Clients -> SUBSET (1..MaxPending)] — active request IDs
    serverQueue,        \* Seq of <<client, requestId>> — server's inbox
    replies,            \* [Clients -> SUBSET (1..MaxPending)] — delivered reply IDs
    timedOut,           \* [Clients -> SUBSET (1..MaxPending)] — timed-out request IDs
    nextRequestId       \* [Clients -> 1..(MaxPending+1)] — next request ID to issue

vars == <<pendingRequests, serverQueue, replies, timedOut, nextRequestId>>

TypeOK ==
    /\ pendingRequests \in [Clients -> SUBSET (1..MaxPending)]
    /\ replies \in [Clients -> SUBSET (1..MaxPending)]
    /\ timedOut \in [Clients -> SUBSET (1..MaxPending)]
    /\ nextRequestId \in [Clients -> 1..(MaxPending + 1)]

Init ==
    /\ pendingRequests = [c \in Clients |-> {}]
    /\ serverQueue = <<>>
    /\ replies = [c \in Clients |-> {}]
    /\ timedOut = [c \in Clients |-> {}]
    /\ nextRequestId = [c \in Clients |-> 1]

\* ─── Actions ─────────────────────────────────────────────────────

\* Client sends a new request (ask pattern)
SendRequest(c) ==
    /\ nextRequestId[c] <= MaxPending
    /\ LET rid == nextRequestId[c]
       IN /\ pendingRequests' = [pendingRequests EXCEPT ![c] = @ \cup {rid}]
          /\ serverQueue' = Append(serverQueue, <<c, rid>>)
          /\ nextRequestId' = [nextRequestId EXCEPT ![c] = @ + 1]
    /\ UNCHANGED <<replies, timedOut>>

\* Server processes the head request and prepares a reply
\* (reply delivery is a separate step to model async)
ProcessRequest ==
    /\ Len(serverQueue) > 0
    /\ LET req == Head(serverQueue)
           client == req[1]
           rid == req[2]
       IN /\ rid \in pendingRequests[client]  \* not already timed out
          /\ serverQueue' = Tail(serverQueue)
    /\ UNCHANGED <<pendingRequests, replies, timedOut, nextRequestId>>

\* Reply is delivered to the client (completes the deferred)
DeliverReply(c, rid) ==
    /\ rid \in pendingRequests[c]
    /\ rid \notin timedOut[c]
    /\ pendingRequests' = [pendingRequests EXCEPT ![c] = @ \ {rid}]
    /\ replies' = [replies EXCEPT ![c] = @ \cup {rid}]
    /\ UNCHANGED <<serverQueue, timedOut, nextRequestId>>

\* Request times out before reply arrives
Timeout(c, rid) ==
    /\ rid \in pendingRequests[c]
    /\ rid \notin replies[c]
    /\ pendingRequests' = [pendingRequests EXCEPT ![c] = @ \ {rid}]
    /\ timedOut' = [timedOut EXCEPT ![c] = @ \cup {rid}]
    /\ UNCHANGED <<serverQueue, replies, nextRequestId>>

Next ==
    \/ \E c \in Clients : SendRequest(c)
    \/ ProcessRequest
    \/ \E c \in Clients, rid \in 1..MaxPending : DeliverReply(c, rid)
    \/ \E c \in Clients, rid \in 1..MaxPending : Timeout(c, rid)

\* ─── Safety Invariants ───────────────────────────────────────────

\* INV-1: A request is either pending, replied, or timed out — never in multiple states
MutualExclusion == \A c \in Clients, rid \in 1..MaxPending :
    Cardinality(
        {x \in {"pending", "replied", "timedOut"} :
            \/ (x = "pending" /\ rid \in pendingRequests[c])
            \/ (x = "replied" /\ rid \in replies[c])
            \/ (x = "timedOut" /\ rid \in timedOut[c])
        }
    ) <= 1

\* INV-2: Replied and timed-out sets are disjoint
NoReplyAfterTimeout == \A c \in Clients :
    replies[c] \cap timedOut[c] = {}

\* INV-3: Total resolved requests never exceed total sent
NoPhantomReplies == \A c \in Clients :
    Cardinality(replies[c] \cup timedOut[c]) <= nextRequestId[c] - 1

\* INV-4: Pending requests are bounded by MaxPending
BoundedPending == \A c \in Clients :
    Cardinality(pendingRequests[c]) <= MaxPending

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_vars

====
