# Actor Model Library

**Kotlin Coroutines + Channels · TLA+ Verified · Lincheck Tested**

A formally specified Actor Model library built on Kotlin Coroutines and Channels, designed as a foundation for a distributed actor system. Every core component is specified in TLA+, and linearizability is verified using JetBrains Lincheck with invariants auto-generated via tla2lincheck.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              ActorSystem                                    │
│                     CoroutineScope (SupervisorJob)                           │
│                                                                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐          │
│  │    ActorCell      │  │    ActorCell      │  │    ActorCell     │  ...    │
│  │  ┌────────────┐  │  │  ┌────────────┐  │  │  ┌────────────┐  │         │
│  │  │  Mailbox   │  │  │  │  Mailbox   │  │  │  │  Mailbox   │  │         │
│  │  │ (Channel)  │  │  │  │ (Channel)  │  │  │  │ (Channel)  │  │         │
│  │  └────────────┘  │  │  └────────────┘  │  │  └────────────┘  │         │
│  │  Behavior<M>     │  │  Behavior<M>     │  │  Behavior<M>     │         │
│  │  Supervisor      │  │  Supervisor      │  │  Supervisor      │         │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘         │
│         ▲                      ▲                      ▲                    │
│     ActorRef              ActorRef                ActorRef                 │
│         │                      │                      │                    │
│  ┌──────┴──────────────────────┴──────────────────────┘                    │
│  │                                                                         │
│  │  ┌─────────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────┐      │
│  │  │   Router    │  │ EventBus │  │  Stash   │  │TimerScheduler  │      │
│  │  │ (RR/BC/CH)  │  │ (PubSub) │  │ (Buffer) │  │ (Delay/Repeat) │      │
│  │  └─────────────┘  └──────────┘  └──────────┘  └────────────────┘      │
│  │                                                                         │
└──┴─────────────────────────────────────────────────────────────────────────┘
```

### Core Components

| Component | File | TLA+ Spec | Description |
|-----------|------|-----------|-------------|
| **Mailbox** | `Mailbox.kt` | `ActorMailbox.tla` | Bounded FIFO channel (send/receive/trySend/tryReceive) |
| **ActorCell** | `ActorCell.kt` | `ActorLifecycle.tla` | Runtime container: lifecycle FSM + message loop |
| **ActorRef** | `ActorRef.kt` | `RequestReply.tla` | Location-transparent handle: tell + ask pattern |
| **Behavior** | `Behavior.kt` | — | Functional message handler (immutable state machine) |
| **SupervisorStrategy** | `SupervisorStrategy.kt` | `ActorLifecycle.tla` | Fault tolerance: stop/restart/resume/escalate |
| **ActorSystem** | `ActorSystem.kt` | — | Top-level container with SupervisorJob scope |

### Advanced Components

| Component | File | TLA+ Spec | Description |
|-----------|------|-----------|-------------|
| **Router** | `Router.kt` | — | Message distribution: RoundRobin, Broadcast, ConsistentHash, Random |
| **EventBus** | `EventBus.kt` | `EventBus.tla` | Typed pub/sub: topics with multiple subscribers |
| **TimerScheduler** | `TimerScheduler.kt` | — | Scheduled & periodic messages: timeouts, retries, heartbeats |
| **Stash** | `Stash.kt` | — | Message buffer for state transitions (init pattern) |

---

## TLA+ Specifications

Four modular specs, each focused and small enough for TLC model checking in under 3–4 minutes:

### 1. ActorMailbox.tla — Bounded Channel
```
State: mailbox (Seq), sendCount, recvCount, lastReceived
Actions: Send(s), Receive, TrySendFull(s), TryReceiveEmpty
Invariants: BoundedCapacity, MessageConservation, NonNegativeLength
Config: 2 senders, capacity 3, messages 1..3 → ~30s TLC
```

### 2. ActorLifecycle.tla — Lifecycle State Machine
```
State: actorState, restartCount, processed, alive
Actions: Start, BecomeRunning, ProcessMessage, Fail, FailPermanent, Restart, GracefulStop, CompleteStopping
Invariants: RestartBudgetRespected, StoppedNotAlive, OnlyRunningProcess, AliveConsistency
Config: 3 actors, MaxRestarts=3 → ~30s TLC
```

### 3. RequestReply.tla — Ask Pattern
```
State: pendingRequests, serverQueue, replies, timedOut, nextRequestId
Actions: SendRequest(c), ProcessRequest, DeliverReply(c,rid), Timeout(c,rid)
Invariants: MutualExclusion, NoReplyAfterTimeout, NoPhantomReplies, BoundedPending
Config: 2 clients, MaxPending=2 → ~1-2min TLC
```

### 4. EventBus.tla — Typed Publish/Subscribe
```
State: subscriptions, published, delivered, unsubscribed
Actions: Subscribe(t,s), Unsubscribe(t,s), Publish(t)
Invariants: DeliveryToSubscribersOnly, NoDeliveryAfterUnsubscribe, BoundedSubscribers, PublishCountConsistency
Config: 2 subscribers, 2 topics, 2 events → ~1min TLC
```

---

## Formal Verification Pipeline

```
┌──────────┐     ┌────────────┐     ┌────────────────┐     ┌──────────────┐
│  TLA+    │     │ tla2lincheck│     │  Generated     │     │  Lincheck    │
│  Spec    │────▶│  Parser +   │────▶│  Kotlin Test   │────▶│  Model Check │
│ (.tla)   │     │  Generator  │     │  (invariants   │     │  + Stress    │
└──────────┘     └────────────┘     │   embedded)    │     └──────────────┘
                                     └────────────────┘
```

The invariants from each TLA+ spec are embedded as `checkInvariants()` calls inside every `@Operation` method, so any invariant violation in any interleaving is immediately caught.

---

## Quick Start

### Build & Test

```bash
cd actors
../gradlew test
```

### Run TLC Model Checker

```bash
java -cp src/main/tla/tla2tools.jar tlc2.TLC src/main/tla/ActorMailbox.tla -config src/main/tla/ActorMailbox.cfg
java -cp src/main/tla/tla2tools.jar tlc2.TLC src/main/tla/ActorLifecycle.tla -config src/main/tla/ActorLifecycle.cfg
java -cp src/main/tla/tla2tools.jar tlc2.TLC src/main/tla/RequestReply.tla -config src/main/tla/RequestReply.cfg
java -cp src/main/tla/tla2tools.jar tlc2.TLC src/main/tla/EventBus.tla -config src/main/tla/EventBus.cfg
```

### Using tla2lincheck (Gradle Plugin)

```bash
./gradlew generateLincheckTests test
```

---

## Usage Examples

### Basic Actor with Tell & Ask

```kotlin
sealed class CounterMsg {
    data class Increment(val n: Int) : CounterMsg()
    data class GetCount(val replyTo: ActorRef<Int>) : CounterMsg(), Request<Int>
}

val system = ActorSystem.create("my-app")

fun counter(count: Int = 0): Behavior<CounterMsg> = behavior { msg ->
    when (msg) {
        is CounterMsg.Increment -> counter(count + msg.n)
        is CounterMsg.GetCount -> {
            msg.replyTo.tell(count)
            Behavior.same()
        }
    }
}

val ref = system.spawn("counter", counter())
ref.tell(CounterMsg.Increment(5))
val count: Int = ref.ask { replyTo -> CounterMsg.GetCount(replyTo) }
```

### Lifecycle Hooks

```kotlin
val actor = system.spawn("db-actor", lifecycleBehavior<DbMsg>(
    onStart = { println("Connecting to database...") },
    onStop  = { println("Closing connection...") }
) { msg ->
    Behavior.same()
})
```

### Fault Tolerance (Supervisor Strategy)

```kotlin
// Restart up to 3 times, then stop
val ref = system.spawn("resilient", myBehavior,
    supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 3))

// Custom decider based on exception type
val ref2 = system.spawn("smart", myBehavior,
    supervisorStrategy = SupervisorStrategy.custom(maxRestarts = 5) { error ->
        when (error) {
            is IllegalArgumentException -> SupervisorStrategy.Directive.RESUME
            is IllegalStateException    -> SupervisorStrategy.Directive.RESTART
            else                        -> SupervisorStrategy.Directive.STOP
        }
    })
```

### Router — Load Balancing Worker Pool

```kotlin
// Round-robin pool (like Kafka consumer group)
val router = Router.pool(
    system = system,
    poolSize = 4,
    namePrefix = "worker",
    behavior = myWorkerBehavior,
    strategy = RoutingStrategy.RoundRobin()
)
router.route(MyMessage("process this"))

// Consistent hash (like Kafka partition key)
val partitioned = Router.consistentHash(workers) { msg -> msg.userId.hashCode() }
partitioned.route(UserEvent(userId = "user-123", action = "login"))

// Broadcast (fan-out to all)
val fanOut = Router.broadcast(workers)
fanOut.route(CacheInvalidation(key = "users"))
```

### EventBus — Pub/Sub (Kafka-like Topics)

```kotlin
val eventBus = EventBus<OrderEvent>("order-events")

// Subscribe actors to topics
eventBus.subscribe("orders", orderProcessorRef)
eventBus.subscribe("orders", auditLoggerRef)
eventBus.subscribe("payments", paymentProcessorRef)

// Publish events (delivered to all subscribers)
eventBus.publish("orders", OrderPlaced(orderId = "ORD-001", amount = 99.99))

// Unsubscribe when done
eventBus.unsubscribe("orders", auditLoggerRef)
```

### TimerScheduler — Timeouts & Heartbeats

```kotlin
val timers = TimerScheduler<MyMsg>(coroutineScope)

// Timeout: send message after delay
timers.startSingleTimer("request-timeout", RequestTimedOut, 5.seconds, myRef)

// Heartbeat: send periodic pings
timers.startPeriodicTimer("heartbeat", Ping, 10.seconds, healthMonitorRef)

// Cancel when no longer needed
timers.cancel("heartbeat")
```

### Stash — Buffer During Initialization

```kotlin
val stash = Stash<MyMsg>(capacity = 100)

fun initializing(): Behavior<MyMsg> = behavior { msg ->
    when (msg) {
        is ConfigLoaded -> {
            // Transition to ready state, replay stashed messages
            stash.unstashAll(ready(msg.config))
        }
        else -> {
            stash.stash(msg)  // Buffer until ready
            Behavior.same()
        }
    }
}

fun ready(config: Config): Behavior<MyMsg> = behavior { msg ->
    // Process messages normally
    Behavior.same()
}
```

---

## Example Applications

Three complete examples showing how to build real systems on top of this library:

### 1. Kafka-Like Event Pipeline (`examples/EventPipelineExample.kt`)
```
Producers → EventBus("orders") → Consumer Group (RoundRobin workers)
                                → Partitioned Processor (ConsistentHash)
                                → Audit Logger (single subscriber)
```
Demonstrates: topics, consumer groups, partition key routing, backpressure.

### 2. Actor-Based Web Framework (`examples/HttpHandlerExample.kt`)
```
HTTP Request → Rate Limiter (stateful actor)
             → Request Router (pattern matching)
             → Handler actors (CRUD with in-memory store, health check)
```
Demonstrates: middleware pipeline, stateful handlers, ask pattern for request-reply, fault isolation.

### 3. Saga Pattern — Transaction Orchestrator (`examples/SagaExample.kt`)
```
Order Request → Saga Orchestrator (state machine actor)
              → Inventory Service → Payment Service → Shipping Service
              → Compensation on failure (release inventory, refund payment)
```
Demonstrates: behavior switching, stash, fault tolerance, ask-pattern service coordination.

---

## Test Suite

| Test Class | Strategy | TLA+ Spec | What it verifies |
|-----------|----------|-----------|-----------------|
| `MailboxLincheckTest` | Lincheck model check + stress | `ActorMailbox.tla` | Mailbox linearizability + invariants |
| `ActorLifecycleLincheckTest` | Lincheck model check + stress | `ActorLifecycle.tla` | Lifecycle FSM linearizability |
| `RequestReplyLincheckTest` | Lincheck model check + stress | `RequestReply.tla` | Ask pattern linearizability |
| `EventBusLincheckTest` | Lincheck model check + stress | `EventBus.tla` | Pub/sub linearizability + invariants |
| `ActorSystemTest` | JUnit 5 unit tests | All specs | Core functionality + invariant checks |
| `ActorConcurrencyTest` | Concurrent stress tests | All specs | Real actor system under load |
| `TimerSchedulerTest` | JUnit 5 unit tests | — | Timer firing, cancellation, replacement |
| `StashTest` | JUnit 5 unit tests | — | FIFO buffering, unstash, capacity |
| `RouterTest` | JUnit 5 unit tests | — | All routing strategies, pool creation |
| `EventBusTest` | JUnit 5 unit tests | `EventBus.tla` | Pub/sub, multi-topic, statistics |

---

## Design Decisions

### Why Kotlin Coroutines + Channels?

1. **One coroutine per actor** — sequential message processing without locks
2. **Channel as mailbox** — built-in backpressure, bounded capacity, thread-safe MPSC
3. **SupervisorJob** — child failure isolation (one actor crash doesn't kill siblings)
4. **Structured concurrency** — system shutdown cancels all actor coroutines cleanly

### Why Four Small TLA+ Specs?

1. **Modularity** — each spec is independently checkable (<1M states)
2. **Composability** — specs can be combined for larger system verification
3. **Educational** — each spec teaches one concept (mailbox, lifecycle, request-reply, pub/sub)
4. **Fast feedback** — TLC finishes in 30s–2min per spec

### TLA+ ↔ Kotlin Traceability

Every Kotlin class, method, and field is annotated with `@TlaSpec`, `@TlaAction`, `@TlaVariable`, or `@TlaInvariant` linking it to the corresponding TLA+ element. This creates a machine-readable traceability matrix for the PhD thesis.

### Building Higher-Level Systems

The library is designed so that each component composes naturally:

| System | Built With |
|--------|-----------|
| **Kafka-like streaming** | EventBus + Router(ConsistentHash) + Stash |
| **Web framework** | Router(RoundRobin) + ask pattern + SupervisorStrategy |
| **Saga orchestrator** | Behavior switching + Stash + TimerScheduler |
| **Cache** | EventBus(Broadcast) + stateful Behavior |
| **Rate limiter** | Stateful Behavior + TimerScheduler |
| **Circuit breaker** | SupervisorStrategy + TimerScheduler + Stash |

---

## Project Structure

```
src/
├── main/
│   ├── kotlin/com/actors/
│   │   ├── ActorCell.kt          # Runtime container (lifecycle + message loop)
│   │   ├── ActorRef.kt           # Type-safe handle (tell + ask)
│   │   ├── ActorState.kt         # Lifecycle state enum
│   │   ├── ActorSystem.kt        # Top-level container
│   │   ├── Behavior.kt           # Functional message handler
│   │   ├── Dsl.kt                # Ergonomic builders
│   │   ├── EventBus.kt           # Typed pub/sub
│   │   ├── Mailbox.kt            # Bounded channel wrapper
│   │   ├── Messages.kt           # Common message protocols
│   │   ├── Router.kt             # Message routing strategies
│   │   ├── Stash.kt              # Message buffer
│   │   ├── SupervisorStrategy.kt # Fault tolerance
│   │   ├── TimerScheduler.kt     # Scheduled messages
│   │   ├── TlaAnnotations.kt     # TLA+ bridge annotations
│   │   └── examples/
│   │       ├── EventPipelineExample.kt   # Kafka-like pipeline
│   │       ├── HttpHandlerExample.kt     # Web framework
│   │       └── SagaExample.kt            # Transaction orchestrator
│   └── tla/
│       ├── ActorMailbox.tla + .cfg       # Bounded channel spec
│       ├── ActorLifecycle.tla + .cfg     # Lifecycle FSM spec
│       ├── RequestReply.tla + .cfg       # Ask pattern spec
│       └── EventBus.tla + .cfg           # Pub/sub spec
└── test/
    └── kotlin/com/actors/
        ├── ActorSystemTest.kt            # Unit tests
        ├── ActorConcurrencyTest.kt       # Stress tests
        ├── ActorLifecycleLincheckTest.kt # Lincheck
        ├── MailboxLincheckTest.kt        # Lincheck
        ├── RequestReplyLincheckTest.kt   # Lincheck
        ├── EventBusLincheckTest.kt       # Lincheck
        ├── EventBusTest.kt              # Unit tests
        ├── RouterTest.kt                # Unit tests
        ├── StashTest.kt                 # Unit tests
        └── TimerSchedulerTest.kt        # Unit tests
```

---

## Future: Distributed Actor Model

The current single-node design is structured for distributed extension:

| Feature | Current | Future |
|---------|---------|--------|
| ActorRef | Local (direct mailbox) | Remote (network routing) |
| Location | Single JVM | Multi-node cluster |
| Discovery | By name (HashMap) | Distributed registry |
| Supervision | Local supervisor tree | Cross-node supervision |
| EventBus | In-process pub/sub | Distributed event streaming |
| Router | Local worker pool | Cluster-aware routing |
| Timers | Coroutine delays | Distributed scheduler |
| TLA+ | Safety invariants | + Network partition specs |

```
Node 1                     Node 2
┌──────────────┐           ┌──────────────┐
│ ActorSystem  │◀─────────▶│ ActorSystem  │
│  Actor A     │  Cluster   │  Actor C     │
│  Actor B     │  Protocol  │  Actor D     │
│  EventBus    │           │  EventBus    │
│  Router      │           │  Router      │
└──────────────┘           └──────────────┘
```

---

## Tech Stack

- **Kotlin 2.0.21** / JVM 21
- **Kotlin Coroutines 1.9.0** (core + test)
- **Lincheck 2.34** (model checking + stress testing)
- **JUnit 5.11.2** + **AssertJ 3.26.3**
- **jqwik 1.9.2** (property-based testing)
- **tla2lincheck** (TLA+ → Lincheck test generation)
- **TLC Model Checker** (TLA+ exhaustive state exploration)
