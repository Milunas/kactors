package com.actors

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════
 * ROUTER: Message Distribution Patterns
 * ═══════════════════════════════════════════════════════════════════
 *
 * Routes messages to a pool of worker actors using different
 * strategies. Essential for building scalable systems:
 *
 *   - **Round-Robin**: even load distribution (Kafka consumer groups)
 *   - **Broadcast**: fan-out to all workers (pub/sub, cache invalidation)
 *   - **Consistent Hash**: sticky routing for stateful processing
 *   - **Random**: simple probabilistic load balancing
 *   - **SmallestMailbox**: route to least-loaded worker
 *
 * Architecture:
 * ```
 *                    ┌──────────────────────┐
 *   messages ──────▶│       Router          │
 *                    │  RoutingStrategy<M>   │
 *                    └──────┬───────┬───────┬┘
 *                           │       │       │
 *                    ┌──────▼──┐ ┌──▼────┐ ┌▼───────┐
 *                    │Worker 1 │ │Worker 2│ │Worker 3│
 *                    └─────────┘ └───────┘ └────────┘
 * ```
 *
 * Kafka-like patterns:
 *   - Router(RoundRobin) → Kafka consumer group (parallel processing)
 *   - Router(Broadcast) → Kafka with all-consumer-groups subscription
 *   - Router(ConsistentHash) → Kafka partition key routing
 *
 * Web Framework patterns:
 *   - Router(RoundRobin) → Load balancer across request handlers
 *   - Router(ConsistentHash) → Session-sticky routing
 *
 * Thread Safety:
 *   - Router is thread-safe for concurrent sends
 *   - AtomicInteger for round-robin counter
 *   - Immutable routee list (add/remove creates a new Router)
 *
 * Future (Distributed):
 *   - Routees can be remote ActorRefs on different nodes
 *   - Cluster-aware routing with health checks
 *   - Adaptive routing based on response times
 */
class Router<M : Any> private constructor(
    val strategy: RoutingStrategy<M>,
    val routees: List<ActorRef<M>>
) {
    companion object {
        private val log = LoggerFactory.getLogger(Router::class.java)

        /**
         * Create a router with the given strategy and worker actors.
         */
        fun <M : Any> create(
            strategy: RoutingStrategy<M>,
            routees: List<ActorRef<M>>
        ): Router<M> {
            require(routees.isNotEmpty()) { "Router requires at least one routee" }
            return Router(strategy, routees.toList())
        }

        /**
         * Create a round-robin router.
         * Messages are distributed evenly across workers in order.
         */
        fun <M : Any> roundRobin(routees: List<ActorRef<M>>): Router<M> =
            create(RoutingStrategy.RoundRobin(), routees)

        /**
         * Create a broadcast router.
         * Every message is sent to ALL workers.
         */
        fun <M : Any> broadcast(routees: List<ActorRef<M>>): Router<M> =
            create(RoutingStrategy.Broadcast(), routees)

        /**
         * Create a consistent hash router.
         * Messages with the same hash key go to the same worker.
         *
         * @param hashFunction Extracts a hash key from each message
         */
        fun <M : Any> consistentHash(
            routees: List<ActorRef<M>>,
            hashFunction: (M) -> Int
        ): Router<M> = create(RoutingStrategy.ConsistentHash(hashFunction), routees)

        /**
         * Create a random router.
         */
        fun <M : Any> random(routees: List<ActorRef<M>>): Router<M> =
            create(RoutingStrategy.Random(), routees)

        /**
         * Spawn a pool of worker actors with the same behavior and wrap
         * them in a round-robin router.
         *
         * @param system The actor system to spawn workers in
         * @param poolSize Number of workers to create
         * @param namePrefix Prefix for worker actor names
         * @param behavior The behavior for each worker
         * @param strategy Routing strategy (defaults to RoundRobin)
         */
        fun <M : Any> pool(
            system: ActorSystem,
            poolSize: Int,
            namePrefix: String,
            behavior: Behavior<M>,
            strategy: RoutingStrategy<M> = RoutingStrategy.RoundRobin(),
            mailboxCapacity: Int = Mailbox.DEFAULT_CAPACITY,
            supervisorStrategy: SupervisorStrategy = SupervisorStrategy.restart()
        ): Router<M> {
            require(poolSize > 0) { "Pool size must be positive" }

            val routees = (1..poolSize).map { i ->
                system.spawn(
                    name = "$namePrefix-$i",
                    behavior = behavior,
                    mailboxCapacity = mailboxCapacity,
                    supervisorStrategy = supervisorStrategy
                )
            }

            log.info("Created router pool '{}' with {} workers (strategy={})",
                namePrefix, poolSize, strategy)

            return create(strategy, routees)
        }
    }

    /**
     * Route a message to one or more workers based on the strategy.
     * Suspends if the target mailbox is full (backpressure).
     */
    suspend fun route(message: M) {
        strategy.select(message, routees).forEach { target ->
            target.tell(message)
        }
    }

    /**
     * Non-blocking route: attempt to send without suspending.
     * Returns true if at least one routee accepted the message.
     */
    fun tryRoute(message: M): Boolean {
        val targets = strategy.select(message, routees)
        return targets.any { it.tryTell(message) }
    }

    /**
     * Create a new router with an additional routee.
     * (Immutable — returns a new Router instance.)
     */
    fun withRoutee(routee: ActorRef<M>): Router<M> =
        Router(strategy, routees + routee)

    /**
     * Create a new router without a specific routee.
     * (Immutable — returns a new Router instance.)
     */
    fun withoutRoutee(routee: ActorRef<M>): Router<M> {
        val remaining = routees.filter { it != routee }
        require(remaining.isNotEmpty()) { "Cannot remove last routee from router" }
        return Router(strategy, remaining)
    }

    val size: Int get() = routees.size

    override fun toString(): String = "Router(strategy=$strategy, routees=${routees.size})"
}

/**
 * ═══════════════════════════════════════════════════════════════════
 * ROUTING STRATEGIES
 * ═══════════════════════════════════════════════════════════════════
 */
sealed class RoutingStrategy<M : Any> {

    /**
     * Select the target routees for a given message.
     * Returns a list (single element for point-to-point, all for broadcast).
     */
    abstract fun select(message: M, routees: List<ActorRef<M>>): List<ActorRef<M>>

    /**
     * Round-Robin: distributes messages evenly in circular order.
     *
     * Message flow: 1→W1, 2→W2, 3→W3, 4→W1, 5→W2, ...
     *
     * Use case: Even load distribution like Kafka consumer groups.
     */
    class RoundRobin<M : Any> : RoutingStrategy<M>() {
        private val counter = AtomicInteger(0)

        override fun select(message: M, routees: List<ActorRef<M>>): List<ActorRef<M>> {
            val index = abs(counter.getAndIncrement() % routees.size)
            return listOf(routees[index])
        }

        override fun toString(): String = "RoundRobin"
    }

    /**
     * Broadcast: sends every message to ALL routees.
     *
     * Use case: Fan-out for cache invalidation, event notifications,
     * or Kafka-style all-consumer-group delivery.
     */
    class Broadcast<M : Any> : RoutingStrategy<M>() {
        override fun select(message: M, routees: List<ActorRef<M>>): List<ActorRef<M>> = routees

        override fun toString(): String = "Broadcast"
    }

    /**
     * Consistent Hash: routes messages with the same hash to the same worker.
     * Ensures ordering guarantees for messages with the same key.
     *
     * Use case: Kafka partition key routing, session-sticky load balancing,
     * stateful stream processing.
     *
     * @param hashFunction Extracts a hash value from the message
     */
    class ConsistentHash<M : Any>(
        private val hashFunction: (M) -> Int
    ) : RoutingStrategy<M>() {
        override fun select(message: M, routees: List<ActorRef<M>>): List<ActorRef<M>> {
            val hash = hashFunction(message)
            val index = abs(hash % routees.size)
            return listOf(routees[index])
        }

        override fun toString(): String = "ConsistentHash"
    }

    /**
     * Random: selects a routee at random.
     *
     * Use case: Simple probabilistic load balancing when ordering
     * doesn't matter.
     */
    class Random<M : Any> : RoutingStrategy<M>() {
        private val random = java.util.concurrent.ThreadLocalRandom.current()

        override fun select(message: M, routees: List<ActorRef<M>>): List<ActorRef<M>> {
            val index = random.nextInt(routees.size)
            return listOf(routees[index])
        }

        override fun toString(): String = "Random"
    }
}
