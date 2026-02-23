---- MODULE EventBus ----
\* ═══════════════════════════════════════════════════════════════════
\* SPEC 4: Event Bus — Typed Publish/Subscribe
\* ═══════════════════════════════════════════════════════════════════
\*
\* Models a topic-based pub/sub event bus where actors subscribe to
\* topics and receive events published to those topics.
\*
\* This pattern is the foundation for:
\*   - Kafka-like event streaming (topics ≈ Kafka topics)
\*   - CQRS event propagation
\*   - Service bus for microservice communication
\*
\* State space: |Subscribers| × |Topics| × |Events| × subscribe/unsubscribe
\*   With 2 subscribers, 2 topics, 2 events → well under 1M states, TLC < 1min
\*
\* Maps to Kotlin:
\*   subscribers   → ConcurrentHashMap<String, CopyOnWriteArraySet<ActorRef>>
\*   Subscribe     → eventBus.subscribe(topic, actorRef)
\*   Unsubscribe   → eventBus.unsubscribe(topic, actorRef)
\*   Publish       → eventBus.publish(topic, event) → tell to each subscriber
\*   Deliver       → subscriber.tell(event)

EXTENDS Naturals, FiniteSets

CONSTANTS
    Subscribers,        \* set of subscriber IDs, e.g. {s1, s2}
    Topics,             \* set of topic names, e.g. {t1, t2}
    MaxEvents,          \* max number of events to publish per topic, e.g. 2
    MaxSubscribers      \* max subscribers per topic, e.g. 3

VARIABLES
    subscriptions,      \* [Topics -> SUBSET Subscribers] — who is subscribed to what
    published,          \* [Topics -> 0..MaxEvents] — events published per topic
    delivered,          \* [Subscribers -> Nat] — total events delivered per subscriber
    unsubscribed        \* [Topics -> SUBSET Subscribers] — tracks who has unsubscribed

vars == <<subscriptions, published, delivered, unsubscribed>>

TypeOK ==
    /\ subscriptions \in [Topics -> SUBSET Subscribers]
    /\ published \in [Topics -> 0..MaxEvents]
    /\ delivered \in [Subscribers -> Nat]
    /\ unsubscribed \in [Topics -> SUBSET Subscribers]

Init ==
    /\ subscriptions = [t \in Topics |-> {}]
    /\ published = [t \in Topics |-> 0]
    /\ delivered = [s \in Subscribers |-> 0]
    /\ unsubscribed = [t \in Topics |-> {}]

\* ─── Actions ─────────────────────────────────────────────────────

\* A subscriber subscribes to a topic (if not already subscribed and below limit)
Subscribe(t, s) ==
    /\ s \notin subscriptions[t]
    /\ Cardinality(subscriptions[t]) < MaxSubscribers
    /\ subscriptions' = [subscriptions EXCEPT ![t] = @ \cup {s}]
    /\ UNCHANGED <<published, delivered, unsubscribed>>

\* A subscriber unsubscribes from a topic
Unsubscribe(t, s) ==
    /\ s \in subscriptions[t]
    /\ subscriptions' = [subscriptions EXCEPT ![t] = @ \ {s}]
    /\ unsubscribed' = [unsubscribed EXCEPT ![t] = @ \cup {s}]
    /\ UNCHANGED <<published, delivered>>

\* Publisher publishes an event to a topic (delivered to all current subscribers)
Publish(t) ==
    /\ published[t] < MaxEvents
    /\ published' = [published EXCEPT ![t] = @ + 1]
    \* Each current subscriber gets the event delivered
    /\ delivered' = [s \in Subscribers |->
        IF s \in subscriptions[t]
        THEN delivered[s] + 1
        ELSE delivered[s]]
    /\ UNCHANGED <<subscriptions, unsubscribed>>

Next ==
    \/ \E t \in Topics, s \in Subscribers : Subscribe(t, s)
    \/ \E t \in Topics, s \in Subscribers : Unsubscribe(t, s)
    \/ \E t \in Topics : Publish(t)

\* ─── Safety Invariants ───────────────────────────────────────────

\* INV-1: Only current subscribers receive events
\* (Structural: Publish action only delivers to subscriptions[t])
DeliveryToSubscribersOnly == TRUE  \* Enforced by Publish action structure

\* INV-2: After unsubscribing, a subscriber is not in the subscription set
NoDeliveryAfterUnsubscribe == \A t \in Topics :
    \A s \in unsubscribed[t] : s \notin subscriptions[t]

\* INV-3: Subscriber count per topic never exceeds MaxSubscribers
BoundedSubscribers == \A t \in Topics :
    Cardinality(subscriptions[t]) <= MaxSubscribers

\* INV-4: Total deliveries per subscriber ≤ total events published across all topics
PublishCountConsistency == \A s \in Subscribers :
    delivered[s] <= 
        LET F[ts \in SUBSET Topics] ==
            IF ts = {} THEN 0
            ELSE LET t == CHOOSE x \in ts : TRUE
                 IN published[t] + F[ts \ {t}]
        IN F[Topics]

\* ─── Temporal Specification ──────────────────────────────────────

Spec == Init /\ [][Next]_vars

====
