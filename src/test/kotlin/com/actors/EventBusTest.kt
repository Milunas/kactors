package com.actors

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * ═══════════════════════════════════════════════════════════════════
 * EVENT BUS TESTS
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: EventBus.tla
 *
 * Verifies the EventBus pub/sub system:
 *   - Subscribe/unsubscribe lifecycle
 *   - Publish delivers to all subscribers
 *   - Unsubscribed actors don't receive new events
 *   - Multiple topics work independently
 *   - Invariants from EventBus.tla hold
 *   - Statistics tracking is accurate
 */
class EventBusTest {

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.create("eventbus-test")
    }

    @AfterEach
    fun teardown() = runBlocking {
        system.terminate()
    }

    @Test
    fun `subscribe and publish delivers to subscriber`() = runBlocking {
        val received = ConcurrentLinkedQueue<String>()
        val eventBus = EventBus<String>("test-bus")

        val subscriber = system.spawn("sub-1", statelessBehavior<String> { msg ->
            received.add(msg)
        })

        eventBus.subscribe("topic-A", subscriber)
        eventBus.publish("topic-A", "hello")

        delay(100.milliseconds)
        assertThat(received).containsExactly("hello")
    }

    @Test
    fun `publish delivers to multiple subscribers`() = runBlocking {
        val received1 = AtomicInteger(0)
        val received2 = AtomicInteger(0)
        val eventBus = EventBus<String>("multi-sub-bus")

        val sub1 = system.spawn("sub-1", statelessBehavior<String> { received1.incrementAndGet() })
        val sub2 = system.spawn("sub-2", statelessBehavior<String> { received2.incrementAndGet() })

        eventBus.subscribe("events", sub1)
        eventBus.subscribe("events", sub2)

        val delivered = eventBus.publish("events", "event-1")

        delay(100.milliseconds)

        assertThat(delivered).isEqualTo(2)
        assertThat(received1.get()).isEqualTo(1)
        assertThat(received2.get()).isEqualTo(1)
    }

    @Test
    fun `unsubscribe stops delivery`() = runBlocking {
        val received = AtomicInteger(0)
        val eventBus = EventBus<String>("unsub-bus")

        val subscriber = system.spawn("sub", statelessBehavior<String> { received.incrementAndGet() })

        eventBus.subscribe("topic", subscriber)
        eventBus.publish("topic", "before-unsub")
        delay(50.milliseconds)

        eventBus.unsubscribe("topic", subscriber)
        eventBus.publish("topic", "after-unsub")
        delay(50.milliseconds)

        assertThat(received.get()).isEqualTo(1) // Only the first event
        eventBus.checkAllInvariants()
    }

    @Test
    fun `different topics are independent`() = runBlocking {
        val ordersReceived = AtomicInteger(0)
        val paymentsReceived = AtomicInteger(0)
        val eventBus = EventBus<String>("multi-topic-bus")

        val orderSub = system.spawn("order-sub", statelessBehavior<String> { ordersReceived.incrementAndGet() })
        val paymentSub = system.spawn("payment-sub", statelessBehavior<String> { paymentsReceived.incrementAndGet() })

        eventBus.subscribe("orders", orderSub)
        eventBus.subscribe("payments", paymentSub)

        eventBus.publish("orders", "order-1")
        eventBus.publish("orders", "order-2")
        eventBus.publish("payments", "payment-1")

        delay(200.milliseconds)

        assertThat(ordersReceived.get()).isEqualTo(2)
        assertThat(paymentsReceived.get()).isEqualTo(1)
    }

    @Test
    fun `subscribe is idempotent`() {
        val eventBus = EventBus<String>("idempotent-bus")
        val sub = system.spawn("sub", statelessBehavior<String> {})

        assertThat(eventBus.subscribe("topic", sub)).isTrue()
        assertThat(eventBus.subscribe("topic", sub)).isFalse() // Already subscribed
        assertThat(eventBus.subscriberCount("topic")).isEqualTo(1)
    }

    @Test
    fun `unsubscribeAll removes from all topics`() {
        val eventBus = EventBus<String>("unsub-all-bus")
        val sub = system.spawn("sub", statelessBehavior<String> {})

        eventBus.subscribe("topic-A", sub)
        eventBus.subscribe("topic-B", sub)
        eventBus.subscribe("topic-C", sub)

        val removed = eventBus.unsubscribeAll(sub)
        assertThat(removed).isEqualTo(3)
        assertThat(eventBus.subscriberCount("topic-A")).isEqualTo(0)
        assertThat(eventBus.subscriberCount("topic-B")).isEqualTo(0)
        assertThat(eventBus.subscriberCount("topic-C")).isEqualTo(0)
    }

    @Test
    fun `publish to empty topic returns 0`() = runBlocking {
        val eventBus = EventBus<String>("empty-bus")
        val delivered = eventBus.publish("nonexistent", "event")
        assertThat(delivered).isEqualTo(0)
    }

    @Test
    fun `statistics are tracked correctly`() = runBlocking {
        val eventBus = EventBus<String>("stats-bus")
        val sub1 = system.spawn("sub-1", statelessBehavior<String> {})
        val sub2 = system.spawn("sub-2", statelessBehavior<String> {})

        eventBus.subscribe("topic", sub1)
        eventBus.subscribe("topic", sub2)

        eventBus.publish("topic", "event-1") // 2 deliveries
        eventBus.publish("topic", "event-2") // 2 deliveries

        delay(100.milliseconds)

        assertThat(eventBus.totalPublished).isEqualTo(2)
        assertThat(eventBus.totalDelivered).isEqualTo(4)
        assertThat(eventBus.topics).containsExactly("topic")
        assertThat(eventBus.totalSubscriptions).isEqualTo(2)
    }

    @Test
    fun `tryPublish returns delivery count`() = runBlocking {
        val eventBus = EventBus<String>("try-bus")
        val sub = system.spawn("sub", statelessBehavior<String> {})

        eventBus.subscribe("topic", sub)

        val delivered = eventBus.tryPublish("topic", "event")
        assertThat(delivered).isEqualTo(1)

        val none = eventBus.tryPublish("empty-topic", "event")
        assertThat(none).isEqualTo(0)
    }

    @Test
    fun `invariants hold under normal operation`() = runBlocking {
        val eventBus = EventBus<String>("invariant-bus", maxSubscribersPerTopic = 5)

        val subscribers = (1..5).map { i ->
            system.spawn("sub-$i", statelessBehavior<String> {})
        }

        subscribers.forEach { eventBus.subscribe("topic", it) }

        repeat(10) { eventBus.publish("topic", "event-$it") }
        delay(200.milliseconds)

        eventBus.checkAllInvariants()
        assertThat(eventBus.checkBoundedSubscribers()).isTrue()
        assertThat(eventBus.checkPublishCountConsistency()).isTrue()
    }

    @Test
    fun `concurrent publish and subscribe`() = runBlocking {
        val eventBus = EventBus<String>("concurrent-bus")
        val totalReceived = AtomicInteger(0)

        // Create subscribers
        val subscribers = (1..5).map { i ->
            system.spawn("concurrent-sub-$i", statelessBehavior<String> {
                totalReceived.incrementAndGet()
            })
        }

        // Subscribe all
        subscribers.forEach { eventBus.subscribe("topic", it) }

        // Publish many events
        repeat(20) { eventBus.publish("topic", "event-$it") }

        delay(500.milliseconds)

        // 20 events × 5 subscribers = 100 deliveries
        assertThat(totalReceived.get()).isEqualTo(100)
        eventBus.checkAllInvariants()
    }
}
