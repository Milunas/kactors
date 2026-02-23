package com.actors.examples

import com.actors.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ═══════════════════════════════════════════════════════════════════
 * EXAMPLE 1: Kafka-Like Event Pipeline
 * ═══════════════════════════════════════════════════════════════════
 *
 * Demonstrates how to build event streaming infrastructure on top of
 * the Actor Model library, mimicking Kafka's core patterns:
 *
 *   - Topics with publish/subscribe (EventBus)
 *   - Consumer groups with load balancing (Router + RoundRobin)
 *   - Partitioned processing (Router + ConsistentHash)
 *   - Dead letter queue for failed events
 *   - Backpressure via bounded mailboxes
 *
 * Architecture:
 * ```
 * ┌──────────────┐     ┌───────────────────────────────────────────┐
 * │  Producers   │     │              EventBus                     │
 * │  (publish)   │────▶│  "orders" topic ──▶ Consumer Group A      │
 * │              │     │                     [Worker1, Worker2]    │
 * │              │     │                     (RoundRobin)          │
 * │              │     │                                           │
 * │              │     │  "payments" topic ──▶ Payment Processor   │
 * │              │     │                      (ConsistentHash)     │
 * └──────────────┘     │                                           │
 *                      │  "dlq" topic ──▶ DLQ Handler              │
 *                      └───────────────────────────────────────────┘
 * ```
 *
 * This shows how the same Actor Model primitives that power Kafka
 * can be used in a single-process system, with the same semantics
 * that will later extend to a distributed cluster.
 */

// ─── Message Protocols ──────────────────────────────────────────

/** Events flowing through the pipeline */
sealed class OrderEvent {
    data class OrderPlaced(val orderId: String, val customerId: String, val amount: Double) : OrderEvent()
    data class OrderProcessed(val orderId: String, val workerId: String) : OrderEvent()
    data class OrderFailed(val orderId: String, val reason: String) : OrderEvent()
}

/** Dead letter events for failed processing */
data class DeadLetter(val originalEvent: Any, val error: String, val topic: String)

// ─── Consumer Group (Worker Pool with RoundRobin) ───────────────

/**
 * Creates a consumer group: a pool of workers processing events
 * from a topic using round-robin distribution.
 *
 * Equivalent to Kafka consumer group with N consumers.
 */
fun createOrderConsumerGroup(
    system: ActorSystem,
    eventBus: EventBus<OrderEvent>,
    workerCount: Int = 3
): Router<OrderEvent> {
    // Create worker pool with round-robin routing
    val router = Router.pool(
        system = system,
        poolSize = workerCount,
        namePrefix = "order-worker",
        behavior = orderWorkerBehavior(),
        strategy = RoutingStrategy.RoundRobin(),
        mailboxCapacity = 100 // Backpressure threshold
    )

    // Subscribe a dispatcher actor that routes to the pool
    val dispatcher = system.spawn("order-dispatcher", statelessBehavior<OrderEvent> { event ->
        router.route(event)
    })
    eventBus.subscribe("orders", dispatcher)

    return router
}

/**
 * Worker behavior that processes order events.
 * Simulates real work (database writes, external API calls).
 */
fun orderWorkerBehavior(): Behavior<OrderEvent> = statelessBehavior { event ->
    when (event) {
        is OrderEvent.OrderPlaced -> {
            // Simulate processing (e.g., saving to database)
            println("  [Worker] Processing order ${event.orderId} " +
                "for customer ${event.customerId} ($${"%.2f".format(event.amount)})")
        }
        is OrderEvent.OrderProcessed -> {
            println("  [Worker] Order ${event.orderId} completed by ${event.workerId}")
        }
        is OrderEvent.OrderFailed -> {
            println("  [Worker] Order ${event.orderId} failed: ${event.reason}")
        }
    }
}

// ─── Partitioned Processing (ConsistentHash by customerId) ──────

/**
 * Processes payments with ordering guarantee per customer.
 * All events for the same customer go to the same worker,
 * ensuring sequential processing (like Kafka partition keys).
 */
fun createPartitionedPaymentProcessor(
    system: ActorSystem,
    workerCount: Int = 4
): Router<OrderEvent> {
    return Router.pool(
        system = system,
        poolSize = workerCount,
        namePrefix = "payment-worker",
        behavior = statelessBehavior { event ->
            when (event) {
                is OrderEvent.OrderPlaced -> {
                    println("  [Payment] Charging customer ${event.customerId}: $${"%.2f".format(event.amount)}")
                }
                else -> { /* ignore non-relevant events */ }
            }
        },
        strategy = RoutingStrategy.ConsistentHash { event ->
            when (event) {
                is OrderEvent.OrderPlaced -> event.customerId.hashCode()
                is OrderEvent.OrderProcessed -> event.orderId.hashCode()
                is OrderEvent.OrderFailed -> event.orderId.hashCode()
            }
        }
    )
}

// ─── Main: Run the Pipeline ─────────────────────────────────────

fun main() = runBlocking {
    println("═══════════════════════════════════════════════════════════")
    println(" Kafka-Like Event Pipeline with Actor Model")
    println("═══════════════════════════════════════════════════════════")
    println()

    val system = ActorSystem.create("event-pipeline")
    val eventBus = EventBus<OrderEvent>("order-events")

    // 1. Create consumer groups (like Kafka consumer groups)
    println("Creating consumer groups...")
    val consumerGroup = createOrderConsumerGroup(system, eventBus, workerCount = 3)
    println("  → Order consumer group: ${consumerGroup.size} workers (RoundRobin)")

    // 2. Create partitioned processor (like Kafka partition key routing)
    val paymentRouter = createPartitionedPaymentProcessor(system, workerCount = 2)
    val paymentDispatcher = system.spawn("payment-dispatcher",
        statelessBehavior<OrderEvent> { event -> paymentRouter.route(event) })
    eventBus.subscribe("orders", paymentDispatcher)
    println("  → Payment processor: ${paymentRouter.size} workers (ConsistentHash)")

    // 3. Logging subscriber (like Kafka Connect sink)
    val logger = system.spawn("audit-logger", statelessBehavior<OrderEvent> { event ->
        println("  [Audit] Event logged: $event")
    })
    eventBus.subscribe("orders", logger)
    println("  → Audit logger: 1 subscriber")

    println()
    println("Publishing events to 'orders' topic...")
    println("─────────────────────────────────────────────────────────")

    // 4. Produce events (like Kafka producers)
    val orders = listOf(
        OrderEvent.OrderPlaced("ORD-001", "customer-A", 99.99),
        OrderEvent.OrderPlaced("ORD-002", "customer-B", 149.50),
        OrderEvent.OrderPlaced("ORD-003", "customer-A", 29.99),  // Same customer → same partition
        OrderEvent.OrderPlaced("ORD-004", "customer-C", 299.00),
        OrderEvent.OrderPlaced("ORD-005", "customer-B", 75.00),  // Same customer → same partition
    )

    for (order in orders) {
        val delivered = eventBus.publish("orders", order)
        println("Published ${order.orderId} → delivered to $delivered subscribers")
        delay(50.milliseconds) // Simulate real-time event stream
    }

    delay(1.seconds)

    println()
    println("─────────────────────────────────────────────────────────")
    println("Pipeline Statistics:")
    println("  Events published: ${eventBus.totalPublished}")
    println("  Total deliveries: ${eventBus.totalDelivered}")
    println("  Active topics: ${eventBus.topics}")
    println("  Subscribers on 'orders': ${eventBus.subscriberCount("orders")}")

    system.terminate()
    println()
    println("Pipeline shut down.")
}
