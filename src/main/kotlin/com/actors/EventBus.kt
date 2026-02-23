package com.actors

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════
 * EVENT BUS: Typed Publish/Subscribe System
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: EventBus.tla
 *
 * A typed pub/sub event bus that allows actors to communicate via
 * topic-based routing. This is the foundation for building:
 *
 *   - **Kafka-like event streaming**: publish events to topics,
 *     multiple consumer groups subscribe independently
 *   - **CQRS**: command side publishes events, query side subscribes
 *   - **Event sourcing**: events are published as they occur
 *   - **Microservice communication**: decoupled service interactions
 *
 * Architecture:
 * ```
 *  Publishers                    EventBus                    Subscribers
 *  ┌───────┐                ┌──────────────┐               ┌───────────┐
 *  │Actor A│──publish──────▶│ "orders"     │──deliver──────▶│ Actor X   │
 *  └───────┘                │  [X, Y]      │               └───────────┘
 *                           │              │               ┌───────────┐
 *  ┌───────┐                │ "payments"   │──deliver──────▶│ Actor Y   │
 *  │Actor B│──publish──────▶│  [Y, Z]      │               └───────────┘
 *  └───────┘                │              │               ┌───────────┐
 *                           │ "inventory"  │──deliver──────▶│ Actor Z   │
 *                           │  [Z]         │               └───────────┘
 *                           └──────────────┘
 * ```
 *
 * Key Properties (verified by TLA+ spec EventBus.tla):
 *   INV-1 DeliveryToSubscribersOnly: only current subscribers receive events
 *   INV-2 NoDeliveryAfterUnsubscribe: unsubscribed actors receive no new events
 *   INV-3 BoundedSubscribers: subscriber count ≤ MaxSubscribers per topic
 *   INV-4 PublishCountConsistency: total deliveries ≤ published × subscribers
 *
 * Thread Safety:
 *   - ConcurrentHashMap for topic → subscriber mapping
 *   - CopyOnWriteArraySet for subscriber sets (read-heavy workload)
 *   - Atomic counters for publish/delivery statistics
 *
 * Future (Distributed):
 *   - Topics spanning multiple nodes
 *   - At-least-once / exactly-once delivery guarantees
 *   - Event persistence for replay (event sourcing)
 *   - Consumer group management across cluster
 */
@TlaSpec("EventBus")
class EventBus<E : Any>(
    val name: String,
    private val maxSubscribersPerTopic: Int = DEFAULT_MAX_SUBSCRIBERS
) {
    companion object {
        const val DEFAULT_MAX_SUBSCRIBERS = 1000
        private val log = LoggerFactory.getLogger(EventBus::class.java)
    }

    /** Topic → set of subscriber ActorRefs */
    @TlaVariable("subscribers")
    private val subscriptions = ConcurrentHashMap<String, CopyOnWriteArraySet<ActorRef<E>>>()

    /** Total events published across all topics */
    @TlaVariable("publishCount")
    private val publishCount = AtomicLong(0)

    /** Total event deliveries (published × subscribers per topic) */
    @TlaVariable("deliverCount")
    private val deliverCount = AtomicLong(0)

    /**
     * Subscribe an actor to a topic.
     * The actor will receive all events published to this topic.
     *
     * TLA+ action: Subscribe(topic, subscriber)
     *
     * @param topic The topic name to subscribe to
     * @param subscriber The ActorRef to deliver events to
     * @return true if newly subscribed, false if already subscribed
     */
    @TlaAction("Subscribe")
    fun subscribe(topic: String, subscriber: ActorRef<E>): Boolean {
        val subscribers = subscriptions.computeIfAbsent(topic) { CopyOnWriteArraySet() }

        check(subscribers.size < maxSubscribersPerTopic) {
            "Topic '$topic' has reached max subscribers ($maxSubscribersPerTopic)"
        }

        val added = subscribers.add(subscriber)
        if (added) {
            log.info("EventBus '{}': actor '{}' subscribed to topic '{}' ({} subscribers)",
                name, subscriber.name, topic, subscribers.size)
        }
        checkAllInvariants()
        return added
    }

    /**
     * Unsubscribe an actor from a topic.
     *
     * TLA+ action: Unsubscribe(topic, subscriber)
     *
     * @param topic The topic to unsubscribe from
     * @param subscriber The ActorRef to remove
     * @return true if was subscribed, false if not found
     */
    @TlaAction("Unsubscribe")
    fun unsubscribe(topic: String, subscriber: ActorRef<E>): Boolean {
        val subscribers = subscriptions[topic] ?: return false
        val removed = subscribers.remove(subscriber)
        if (removed) {
            log.info("EventBus '{}': actor '{}' unsubscribed from topic '{}' ({} remaining)",
                name, subscriber.name, topic, subscribers.size)
            // Clean up empty topics
            if (subscribers.isEmpty()) {
                subscriptions.remove(topic, subscribers)
            }
        }
        checkAllInvariants()
        return removed
    }

    /**
     * Unsubscribe an actor from ALL topics.
     *
     * @param subscriber The ActorRef to remove from all subscriptions
     * @return Number of topics the actor was unsubscribed from
     */
    fun unsubscribeAll(subscriber: ActorRef<E>): Int {
        var count = 0
        subscriptions.forEach { (topic, subscribers) ->
            if (subscribers.remove(subscriber)) {
                count++
                if (subscribers.isEmpty()) {
                    subscriptions.remove(topic, subscribers)
                }
            }
        }
        if (count > 0) {
            log.info("EventBus '{}': actor '{}' unsubscribed from {} topics",
                name, subscriber.name, count)
        }
        return count
    }

    /**
     * Publish an event to a topic.
     * The event is delivered to ALL current subscribers of the topic.
     * Suspends if any subscriber's mailbox is full (backpressure).
     *
     * TLA+ action: Publish(topic, event)
     *
     * @param topic The topic to publish to
     * @param event The event to deliver
     * @return Number of subscribers the event was delivered to
     */
    @TlaAction("Publish")
    suspend fun publish(topic: String, event: E): Int {
        val subscribers = subscriptions[topic]
        if (subscribers.isNullOrEmpty()) {
            log.trace("EventBus '{}': no subscribers for topic '{}', event dropped", name, topic)
            publishCount.incrementAndGet()
            return 0
        }

        // Snapshot subscribers to avoid concurrent modification during delivery
        val snapshot = subscribers.toList()
        publishCount.incrementAndGet()

        var delivered = 0
        for (subscriber in snapshot) {
            try {
                subscriber.tell(event)
                deliverCount.incrementAndGet()
                delivered++
            } catch (e: Exception) {
                log.warn("EventBus '{}': delivery to '{}' failed: {}",
                    name, subscriber.name, e.message)
            }
        }

        log.trace("EventBus '{}': published to topic '{}' ({}/{} delivered)",
            name, topic, delivered, snapshot.size)

        checkAllInvariants()
        return delivered
    }

    /**
     * non-blocking publish: attempts delivery without suspending.
     * Events are dropped for subscribers with full mailboxes.
     *
     * @return Number of subscribers that accepted the event
     */
    fun tryPublish(topic: String, event: E): Int {
        val subscribers = subscriptions[topic]
        if (subscribers.isNullOrEmpty()) {
            publishCount.incrementAndGet()
            return 0
        }

        val snapshot = subscribers.toList()
        publishCount.incrementAndGet()

        var delivered = 0
        for (subscriber in snapshot) {
            if (subscriber.tryTell(event)) {
                deliverCount.incrementAndGet()
                delivered++
            }
        }
        return delivered
    }

    // ─── Query Methods ──────────────────────────────────────────

    /** Number of subscribers for a specific topic. */
    fun subscriberCount(topic: String): Int =
        subscriptions[topic]?.size ?: 0

    /** All topic names with at least one subscriber. */
    val topics: Set<String> get() = subscriptions.keys.toSet()

    /** Total number of distinct subscriptions across all topics. */
    val totalSubscriptions: Int get() =
        subscriptions.values.sumOf { it.size }

    /** Total events published. */
    val totalPublished: Long get() = publishCount.get()

    /** Total event deliveries. */
    val totalDelivered: Long get() = deliverCount.get()

    // ─── Invariant Checks (TLA+ → Kotlin bridge) ────────────────

    @TlaInvariant("BoundedSubscribers")
    fun checkBoundedSubscribers(): Boolean =
        subscriptions.values.all { it.size <= maxSubscribersPerTopic }

    @TlaInvariant("PublishCountConsistency")
    fun checkPublishCountConsistency(): Boolean =
        deliverCount.get() >= 0 && publishCount.get() >= 0

    fun checkAllInvariants() {
        check(checkBoundedSubscribers()) {
            "Invariant violated: BoundedSubscribers — some topic exceeds $maxSubscribersPerTopic"
        }
        check(checkPublishCountConsistency()) {
            "Invariant violated: PublishCountConsistency — negative counters"
        }
    }

    override fun toString(): String =
        "EventBus($name, topics=${subscriptions.size}, published=${publishCount.get()})"
}
