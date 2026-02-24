# Actor Model Library

**Typed · Fault-Tolerant · Traceable · Built on Kotlin Coroutines**

A formally specified Actor Model library for the JVM, built as part of a PhD research project on formal verification of concurrent systems. Define actors as pure functions, get supervision trees, backpressure, and full tracing for free. Every component is specified in TLA+, verified with TLC model checking, and tested with JetBrains Lincheck.

```kotlin
// Define messages
sealed class Greeter {
    data class Hello(val name: String) : Greeter()
    data class GetCount(val replyTo: ActorRef<Int>) : Greeter(), Request<Int>
}

// Define behavior (state encoded as function parameter — no mutable fields)
fun greeter(count: Int = 0): Behavior<Greeter> = receive { ctx, msg ->
    when (msg) {
        is Greeter.Hello -> {
            ctx.info("Hello, ${msg.name}!", "count" to "${count + 1}")
            greeter(count + 1)                       // next behavior = new state
        }
        is Greeter.GetCount -> {
            msg.replyTo.tell(count)
            Behavior.same()                          // keep current state
        }
    }
}

// Run it
val system = ActorSystem.create("my-app")
val ref = system.spawn("greeter", greeter())

ref.tell(Greeter.Hello("World"))                     // fire-and-forget
val n: Int = ref.ask { Greeter.GetCount(it) }        // request-reply
```

---

## Table of Contents

1. [Project Purpose](#project-purpose)
2. [Installation](#installation)
3. [Core Concepts](#core-concepts)
4. [API Reference](#api-reference)
5. [Patterns & Recipes](#patterns--recipes)
6. [Configuration](#configuration)
7. [Tracing & Debugging](#tracing--debugging)
8. [Fault Tolerance](#fault-tolerance)
9. [Formal Verification](#formal-verification)
10. [tla2lincheck — Auto-Generated Lincheck Tests](#tla2lincheck--auto-generated-lincheck-tests)
11. [Test Suite](#test-suite)
12. [Architecture](#architecture)
13. [Repository Layout](#repository-layout)
14. [Comparison with Akka & Erlang](#comparison-with-akka--erlang)
15. [Contributing](#contributing)

---

## Project Purpose

This library is part of a **PhD research project** exploring three interconnected topics:

1. **Formal verification of concurrent systems** — using TLA+ specifications and TLC model checking to prove safety properties of actor-based systems
2. **Traceability between specifications and implementations** — a machine-readable annotation system (`@TlaSpec`, `@TlaAction`, `@TlaVariable`, `@TlaInvariant`) that links every Kotlin class, method, and field to its TLA+ counterpart
3. **Actor model semantics for fault-tolerant systems** — demonstrating that backpressure, supervision, and lifecycle management can be formally specified and verified

### Goals

| Goal | What it means |
|------|---------------|
| **Fault tolerant** | Better backpressure and supervision than Akka Typed or Erlang/OTP on the JVM |
| **Traceable & debuggable** | Every message, signal, and lifecycle event is observable and linked to a TLA+ spec |
| **Academic / PhD-grade** | Every design decision is provable at the TLA+ level and publishable |

### What This Is — and Isn't

This is a **library**, not an application framework. It provides actor primitives that applications build on top of. Application-level concerns (Kafka consumers, HTTP handlers, database actors) belong in your code, not in this library.

### Inspiration

```
Erlang/OTP  ──▶  Process links, monitors, let-it-crash, supervision trees
Akka Typed  ──▶  Typed behaviors, ActorContext as parameter, signal handling
This Library ──▶  TLA+-verified, better backpressure, suspendable behaviors,
                  machine-readable annotations, modular specs
```

---

## Installation

### Gradle (Kotlin DSL)

```kotlin
// settings.gradle.kts
includeBuild("path/to/actors")

// build.gradle.kts
dependencies {
    implementation("com.actors:actors:0.1.0-SNAPSHOT")
}
```

### Requirements

- **JVM 21+**
- **Kotlin 2.0+**
- **kotlinx-coroutines 1.9+**

---

## Core Concepts

### Actors

An **actor** is a lightweight unit of computation that:
- Processes one message at a time (no locks needed)
- Has private state (no shared mutable state)
- Communicates only via message passing
- Can spawn child actors (forming a supervision tree)

### Behaviors

A **behavior** defines how an actor handles messages. Behaviors are immutable functions — state changes by returning a *new* behavior:

```kotlin
// State is the function parameter, not a mutable field
fun counter(count: Int = 0): Behavior<CounterMsg> = receive { ctx, msg ->
    when (msg) {
        is Increment -> counter(count + msg.n)    // new state
        is GetCount  -> { msg.replyTo.tell(count); Behavior.same() }
    }
}
```

### Messages

Messages are **sealed classes** (exhaustive matching, type safety):

```kotlin
sealed class WorkerMsg {
    data class Process(val task: Task) : WorkerMsg()
    data class Status(val replyTo: ActorRef<StatusResponse>) : WorkerMsg(), Request<StatusResponse>
    data object Shutdown : WorkerMsg()
}
```

Implement `Request<R>` on messages that expect a reply (enables the `ask` pattern).

### ActorRef

An `ActorRef<M>` is a type-safe, location-transparent handle to an actor:

```kotlin
// Fire-and-forget (suspends if mailbox is full = backpressure)
ref.tell(MyMsg.DoWork("task-1"))

// Non-blocking try (returns false if mailbox full)
val accepted: Boolean = ref.tryTell(MyMsg.DoWork("task-2"))

// Request-reply with timeout (default 5s)
val result: Response = ref.ask(timeout = 3.seconds) { replyTo ->
    MyMsg.Query("id-123", replyTo)
}
```

### ActorSystem

The `ActorSystem` is the top-level container. It owns the coroutine scope and manages all actors:

```kotlin
val system = ActorSystem.create("my-app")

// Spawn top-level actors
val ref = system.spawn("worker", workerBehavior())

// Shut down everything
system.terminate()  // stops all actors, cancels all coroutines
```

---

## API Reference

### DSL Functions — Creating Behaviors

| Function | Use When | Signature |
|----------|----------|-----------|
| `receive { ctx, msg -> }` | You need `ActorContext` (spawn, watch, log) | `(ActorContext<M>, M) -> Behavior<M>` |
| `behavior { msg -> }` | Simple handler, no context needed | `(M) -> Behavior<M>` |
| `setup { ctx -> behavior }` | One-time init (connect DB, spawn children) | `(ActorContext<M>) -> Behavior<M>` |
| `statelessBehavior { msg -> }` | Every message handled the same way | `(M) -> Unit` |
| `lifecycleBehavior(onStart, onStop) { msg -> }` | Need start/stop hooks without signals | `(M) -> Behavior<M>` |

### Behavior Return Values

| Return | Meaning |
|--------|---------|
| `Behavior.same()` | Keep current behavior and state |
| `Behavior.stopped()` | Stop this actor gracefully |
| `counter(count + 1)` | Transition to new behavior (new state) |

### ActorContext — The Actor's Toolbox

Available inside `receive {}` and `setup {}`:

| Method / Property | What it does |
|-------------------|-------------|
| `ctx.self` | This actor's own `ActorRef` |
| `ctx.name` | Fully qualified path (`system/parent/child`) |
| `ctx.spawn(name, behavior)` | Spawn a supervised child actor |
| `ctx.spawn(name, behavior, config)` | Spawn with explicit `ActorConfig` |
| `ctx.stop(childRef)` | Stop a child actor |
| `ctx.watch(ref)` | Get notified when `ref` stops |
| `ctx.unwatch(ref)` | Cancel watch |
| `ctx.children` | Set of child `ActorRef`s |
| `ctx.log` | SLF4J logger scoped to actor path |
| `ctx.info(msg, ...)` | Log + record in flight recorder |
| `ctx.debug / warn / error / trace` | Same, at different levels |
| `ctx.flightRecorder` | Access trace buffer directly |
| `ctx.currentTraceContext` | Current distributed trace (traceId/spanId) |

### Signals — Lifecycle Events

Attach signal handlers with `.onSignal {}`:

```kotlin
receive<MyMsg> { ctx, msg ->
    // handle messages
    Behavior.same()
}.onSignal { ctx, signal ->
    when (signal) {
        is Signal.PreStart   -> { /* actor started */ Behavior.same() }
        is Signal.PostStop   -> { /* actor stopping, clean up */ Behavior.same() }
        is Signal.Terminated -> { /* watched actor died */ Behavior.same() }
        is Signal.ChildFailed -> {
            ctx.log.error("Child {} failed: {}", signal.ref, signal.cause)
            Behavior.same()
        }
    }
}
```

### ActorSystem API

| Method | Description |
|--------|------------|
| `ActorSystem.create(name)` | Create a new system |
| `system.spawn(name, behavior)` | Spawn a top-level actor |
| `system.spawn(name, behavior, config)` | Spawn with `ActorConfig` |
| `system.stop(actorName)` | Stop a top-level actor by name |
| `system.terminate()` | Graceful shutdown of all actors |
| `system.isTerminated` | Check if system is shut down |
| `system.actorCount` | Number of top-level actors |
| `system.dumpTree()` | ASCII supervision tree |
| `system.dumpTreeJson()` | JSON supervision tree |
| `system.dumpActorTrace(name)` | One actor's flight recorder |
| `system.dumpAllTraces()` | All actors' flight recorders |
| `system.exportAllTracesNdjson()` | Export all traces as NDJSON |

---

## Patterns & Recipes

### 1. Stateful Actor (Functional State)

State is encoded as function parameters. Each message returns a new behavior with updated state:

```kotlin
sealed class CartMsg {
    data class AddItem(val item: String) : CartMsg()
    data class RemoveItem(val item: String) : CartMsg()
    data class GetItems(val replyTo: ActorRef<List<String>>) : CartMsg(), Request<List<String>>
    data object Checkout : CartMsg()
}

fun shoppingCart(items: List<String> = emptyList()): Behavior<CartMsg> = receive { ctx, msg ->
    when (msg) {
        is CartMsg.AddItem    -> shoppingCart(items + msg.item)
        is CartMsg.RemoveItem -> shoppingCart(items - msg.item)
        is CartMsg.GetItems   -> { msg.replyTo.tell(items); Behavior.same() }
        is CartMsg.Checkout   -> { ctx.info("Checkout: $items"); Behavior.stopped() }
    }
}

val cart = system.spawn("cart-user-42", shoppingCart())
cart.tell(CartMsg.AddItem("Book"))
cart.tell(CartMsg.AddItem("Pen"))
val items = cart.ask<List<String>> { CartMsg.GetItems(it) }  // ["Book", "Pen"]
```

### 2. Parent-Child Hierarchy (Supervisor Pattern)

Parents spawn children and supervise them. When a parent stops, all children stop too:

```kotlin
sealed class PoolMsg {
    data class Submit(val task: String) : PoolMsg()
}

sealed class WorkerMsg {
    data class Execute(val task: String) : WorkerMsg()
}

fun workerBehavior(): Behavior<WorkerMsg> = receive { ctx, msg ->
    when (msg) {
        is WorkerMsg.Execute -> {
            ctx.info("Processing: ${msg.task}")
            Behavior.same()
        }
    }
}

fun workerPool(size: Int): Behavior<PoolMsg> = setup { ctx ->
    // Spawn N workers as children
    val workers = (1..size).map { i ->
        ctx.spawn("worker-$i", workerBehavior())
    }
    var next = 0

    // Round-robin dispatch
    receive { ctx, msg ->
        when (msg) {
            is PoolMsg.Submit -> {
                workers[next % workers.size].tell(WorkerMsg.Execute(msg.task))
                next++
                Behavior.same()
            }
        }
    }
}

val pool = system.spawn("pool", workerPool(4))
pool.tell(PoolMsg.Submit("task-1"))
pool.tell(PoolMsg.Submit("task-2"))
```

### 3. DeathWatch — Monitoring Other Actors

Watch actors to detect failures and spawn replacements:

```kotlin
fun resilientSupervisor(): Behavior<SupervisorMsg> = setup { ctx ->
    var worker = ctx.watch(ctx.spawn("worker", workerBehavior()))

    receive<SupervisorMsg> { ctx, msg ->
        Behavior.same()
    }.onSignal { ctx, signal ->
        when (signal) {
            is Signal.Terminated -> {
                ctx.warn("Worker died, spawning replacement")
                worker = ctx.watch(ctx.spawn("worker", workerBehavior()))
                Behavior.same()
            }
            else -> Behavior.same()
        }
    }
}
```

### 4. Request-Reply (Ask Pattern)

For actors that need to respond to queries:

```kotlin
sealed class DbMsg {
    data class Query(
        val sql: String,
        val replyTo: ActorRef<QueryResult>
    ) : DbMsg(), Request<QueryResult>
}

data class QueryResult(val rows: List<Map<String, Any>>)

fun dbActor(connection: Connection): Behavior<DbMsg> = receive { ctx, msg ->
    when (msg) {
        is DbMsg.Query -> {
            val result = connection.execute(msg.sql)
            msg.replyTo.tell(QueryResult(result))
            Behavior.same()
        }
    }
}

// Caller side:
val result: QueryResult = dbRef.ask(timeout = 10.seconds) { replyTo ->
    DbMsg.Query("SELECT * FROM users", replyTo)
}
```

### 5. Setup with Resource Cleanup

Use `setup` for initialization and `PostStop` for cleanup:

```kotlin
fun dbActor(): Behavior<DbMsg> = setup { ctx ->
    val connection = Database.connect()
    ctx.info("Connected to database")

    receive<DbMsg> { ctx, msg ->
        when (msg) {
            is DbMsg.Query -> {
                // use connection...
                Behavior.same()
            }
        }
    }.onSignal { ctx, signal ->
        when (signal) {
            is Signal.PostStop -> {
                connection.close()
                ctx.info("Database connection closed")
                Behavior.same()
            }
            else -> Behavior.same()
        }
    }
}
```

### 6. Stateless Actor (Side Effects Only)

When every message is handled the same way with no state:

```kotlin
val logger = system.spawn("logger", statelessBehavior<LogEntry> { entry ->
    println("[${entry.level}] ${entry.message}")
})
```

### 7. Lifecycle Hooks (Simple Start/Stop)

When you need start/stop callbacks without full signal handling:

```kotlin
val actor = system.spawn("metrics", lifecycleBehavior<MetricMsg>(
    onStart = { MetricsRegistry.register("actor.metrics") },
    onStop  = { MetricsRegistry.unregister("actor.metrics") }
) { msg ->
    MetricsRegistry.record(msg.name, msg.value)
    Behavior.same()
})
```

---

## Configuration

### ActorConfig

Bundle all actor settings into a reusable config object:

```kotlin
val config = ActorConfig(
    mailboxCapacity = 64,                      // bounded mailbox (backpressure)
    supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 5),
    traceCapacity = 256,                       // flight recorder buffer size
    slowMessageThresholdMs = 50L,              // warn if message takes > 50ms
    enableMessageSnapshots = true              // capture message.toString() in traces
)

val ref = system.spawn("worker", workerBehavior(), config)
```

### Built-in Presets

| Preset | Mailbox | Trace Buffer | Message Snapshots | Slow Threshold |
|--------|---------|-------------|-------------------|---------------|
| `ActorConfig.DEFAULT` | 256 | 128 | off | 100ms |
| `ActorConfig.DEBUG` | 256 | 1024 | **on** | 50ms |
| `ActorConfig.HIGH_THROUGHPUT` | 1024 | 128 | off | disabled |

```kotlin
// Use debug config during development
val ref = system.spawn("worker", workerBehavior(), ActorConfig.DEBUG)

// Use high-throughput in production
val ref = system.spawn("ingress", ingressBehavior(), ActorConfig.HIGH_THROUGHPUT)
```

### Individual Parameters (spawn overload)

You can also pass parameters directly without `ActorConfig`:

```kotlin
val ref = system.spawn(
    name = "worker",
    behavior = workerBehavior(),
    mailboxCapacity = 32,
    supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 10)
)
```

### Child Actor Configuration

Children **inherit** their parent's trace config (traceCapacity, slowMessageThresholdMs, enableMessageSnapshots) automatically. You can override mailbox and supervisor individually:

```kotlin
fun parent(): Behavior<ParentMsg> = setup { ctx ->
    // Child inherits parent's trace config
    val child = ctx.spawn("worker", workerBehavior(),
        mailboxCapacity = 32,
        supervisorStrategy = SupervisorStrategy.resume()
    )
    receive { _, _ -> Behavior.same() }
}
```

---

## Tracing & Debugging

Every actor automatically maintains a **flight recorder** — a bounded ring buffer of trace events. Tracing is always-on with near-zero overhead (no allocation on the hot path, bounded memory).

### What Gets Traced Automatically

| Event | Example |
|-------|---------|
| Message received | `MSG_RECV ProcessTask from=system/pool` |
| Message sent | `MSG_SENT → system/worker-1 ProcessTask` |
| Signal delivered | `SIGNAL PreStart` |
| State change | `STATE CREATED→STARTING` |
| Behavior change | `BEHAVIOR → counter(42)` |
| Child spawned | `SPAWN system/pool/worker-1` |
| Child stopped | `CHILD_STOP system/pool/worker-1` |
| Watch registered | `WATCH → system/db` |
| Failure handled | `FAILURE IllegalStateException → RESTART` |
| Slow message | `⚠ SLOW ProcessTask 250ms` |
| Custom log | `[INFO] Processing order 12345` |

### Supervision Tree Dump

```kotlin
println(system.dumpTree())
```
```
ActorSystem: my-app
├── [RUNNING] my-app/pool  msgs=100 restarts=0 mailbox=../256 trace=128/128
│   ├── [RUNNING] my-app/pool/worker-1  msgs=50 restarts=1 mailbox=2/256 trace=64/128
│   └── [RUNNING] my-app/pool/worker-2  msgs=48 restarts=0 mailbox=0/256 trace=60/128
└── [RUNNING] my-app/monitor  msgs=5 restarts=0 mailbox=0/256 trace=12/128
```

### Actor Trace Dump

```kotlin
println(system.dumpActorTrace("pool"))
```
```
╔══════════════════════════════════════════════════════════════╗
║ FLIGHT RECORDER: my-app/pool                                ║
║ Events: 128 recorded, 128 in buffer, 0 evicted              ║
╠══════════════════════════════════════════════════════════════╣
║ [L:1   ] STATE CREATED→STARTING                             ║
║ [L:2   ] SIGNAL PreStart                                     ║
║ [L:3   ] STATE STARTING→RUNNING                             ║
║ [L:4   ] SPAWN my-app/pool/worker-1                         ║
║ [L:5   ] SPAWN my-app/pool/worker-2                         ║
║ [L:6   ] MSG_RECV Submit("task-1")                           ║
║ [L:7   ] MSG_SENT → my-app/pool/worker-1 Execute            ║
╚══════════════════════════════════════════════════════════════╝
```

### Structured Logging Inside Actors

Log to **both** SLF4J and the flight recorder in one call:

```kotlin
val orderActor = receive<OrderMsg> { ctx, msg ->
    ctx.info("Processing order", "orderId" to msg.id, "amount" to msg.total.toString())
    // → SLF4J:          actor.system/orders - Processing order
    // → FlightRecorder:  CustomEvent(INFO, "Processing order", {orderId=O-123, amount=99.99})
    Behavior.same()
}
```

### Cross-Actor Distributed Tracing

`tell()` and `ask()` automatically propagate trace context (traceId, spanId, parentSpanId) across actor boundaries — zero manual instrumentation:

```kotlin
// Actor A sends to B sends to C
// All three events share the same traceId with parent→child spans
// Fully reconstructible from flight recorder dumps
```

### NDJSON Export & Post-Mortem Replay

Export traces from production, analyze on your dev machine:

```kotlin
// On production: export
File("trace.ndjson").writeText(system.exportAllTracesNdjson())

// On dev: replay and analyze
val events = TraceReplay.loadNdjson(File("trace.ndjson").readText())

TraceReplay.printTimeline(events)            // Unified chronological timeline
TraceReplay.printMessageFlow(events)          // Who talked to whom (+ counts)
TraceReplay.printCausalChain(events, "abc")   // Full causal chain for trace "abc"
TraceReplay.printFailures(events)             // All failures with surrounding context

// Programmatic access
val byActor = TraceReplay.groupByActor(events)
val byTrace = TraceReplay.groupByTrace(events)
val flow    = TraceReplay.messageFlow(events)   // Map<(sender→receiver), count>
```

---

## Fault Tolerance

### Backpressure

Every mailbox is **bounded**. When full:
- `tell()` suspends the sender until space is available (true backpressure)
- `tryTell()` returns `false` immediately (caller decides: drop or retry)
- No message is ever silently lost

### Supervisor Strategies

When an actor's behavior throws, the supervisor strategy decides what happens:

```kotlin
// Stop the actor (safest — default for system.spawn)
system.spawn("safe", myBehavior,
    supervisorStrategy = SupervisorStrategy.stop())

// Restart up to N times, then stop
system.spawn("resilient", myBehavior,
    supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 5))

// Skip the bad message, keep going
system.spawn("tolerant", myBehavior,
    supervisorStrategy = SupervisorStrategy.resume())

// Custom per-exception logic
system.spawn("smart", myBehavior,
    supervisorStrategy = SupervisorStrategy.custom(maxRestarts = 5) { error ->
        when (error) {
            is IllegalArgumentException -> SupervisorStrategy.Directive.RESUME
            is IllegalStateException    -> SupervisorStrategy.Directive.RESTART
            else                        -> SupervisorStrategy.Directive.STOP
        }
    })
```

### Cascading Stop

Stopping a parent stops all children (depth-first, bottom-up `PostStop` delivery):

```
parent.stop()
  → child-a stops
    → grandchild stops → PostStop
  → child-a → PostStop
  → child-b stops → PostStop
  → parent → PostStop
```

### ChildFailed Signal

When a child fails, the parent receives a `ChildFailed` signal **before** the supervisor strategy fires — giving you in-band fault handling (better than Erlang's out-of-band supervisors):

```kotlin
receive<ParentMsg> { ctx, msg -> Behavior.same() }
    .onSignal { ctx, signal ->
        when (signal) {
            is Signal.ChildFailed -> {
                ctx.warn("Child ${signal.ref.name} failed: ${signal.cause}")
                // React: update state, notify monitoring, spawn replacement
                Behavior.same()
            }
            else -> Behavior.same()
        }
    }
```

---

## Formal Verification

Every core component is specified in TLA+ and model-checked with TLC.

### TLA+ Specifications

| Spec | What it models | States | Key Invariants |
|------|---------------|--------|---------------|
| `ActorMailbox.tla` | Bounded FIFO channel | 1,060 | BoundedCapacity, MessageConservation |
| `ActorLifecycle.tla` | Lifecycle FSM + signals | 175,616 | PreStartBeforeProcess, StoppedIsTerminal |
| `RequestReply.tla` | Ask pattern | 2,137 | NoDoubleReply, TimeoutOrReply |
| `ActorHierarchy.tla` | Supervision tree | 14,220 | ChildParentConsistency, StoppedHasNoChildren |
| `DeathWatch.tla` | Actor monitoring | 45,855 | NoPhantomTerminated, WatcherReceivedTerminated |
| `ActorTrace.tla` | Flight recorder | 2,607,852 | BoundedTrace, MonotonicLamport, CausalOrder |

### Running TLC

```bash
alias tlc="java -cp src/main/tla/tla2tools.jar tlc2.TLC"

for spec in ActorMailbox ActorLifecycle RequestReply ActorHierarchy DeathWatch ActorTrace; do
    echo "=== $spec ==="
    tlc src/main/tla/${spec}.tla -config src/main/tla/${spec}.cfg -workers auto
done
```

### TLA+ ↔ Kotlin Traceability

Every Kotlin class, method, and field is annotated linking it to its TLA+ counterpart:

```kotlin
@TlaSpec("ActorMailbox")                          // class ↔ spec module
class Mailbox<M : Any>(capacity: Int) {
    @TlaVariable("sendCount")                     // field ↔ spec variable
    private val totalSent = AtomicLong(0)

    @TlaAction("Send")                            // method ↔ spec action
    suspend fun send(message: M) { ... }

    @TlaInvariant("MessageConservation")           // invariant ↔ spec invariant
    fun checkMessageConservation(): Boolean = totalReceived.get() <= totalSent.get()
}
```

### Lincheck Integration

TLA+ invariants are embedded in Lincheck tests as `@Operation` postconditions, so any invariant violation under any interleaving is caught:

```bash
# Run all tests including Lincheck
../gradlew test
```

---

## tla2lincheck — Auto-Generated Lincheck Tests

The project uses **tla2lincheck**, a custom Gradle plugin that parses TLA+ specifications and auto-generates JetBrains Lincheck test classes with embedded TLA+ invariants. This bridges the gap between formal specification and runtime verification.

### How It Works

```
┌──────────────┐     ┌──────────────────┐     ┌────────────────────┐     ┌──────────────┐
│  TLA+ Spec   │     │  tla2lincheck    │     │  Generated Kotlin  │     │  Lincheck    │
│  (.tla file) │────▶│  Parser +        │────▶│  Test Class        │────▶│  Model Check │
│              │     │  Code Generator  │     │  (invariants       │     │  + Stress    │
└──────────────┘     └──────────────────┘     │   embedded)        │     └──────────────┘
                                              └────────────────────┘
```

For each `.tla` file, tla2lincheck:
1. Parses `CONSTANTS`, `VARIABLES`, and `INVARIANTS`
2. Extracts each TLA+ action as a Lincheck `@Operation`
3. Embeds all invariant checks inside every `@Operation` as postconditions
4. Generates both `ModelCheckingOptions` and `StressOptions` tests

### Configuration

The plugin is configured in `build.gradle.kts`:

```kotlin
plugins {
    id("io.github.tla2lincheck") version "0.1.0-SNAPSHOT"
}

tla2lincheck {
    tlaSourceDir.set(file("src/main/tla"))           // where to find .tla files
    outputDir.set(file("src/test/tla2lincheck"))      // where to write generated tests
    packageName.set("com.actors.generated")           // package for generated classes
    threads.set(3)                                    // Lincheck threads
    actorsPerThread.set(2)                            // operations per thread
    iterations.set(50)                                // model checking iterations
    embedInvariants.set(true)                         // embed TLA+ invariants in @Operations
}
```

The plugin source lives in a sibling directory (`../tla2lincheck`) and is included as a composite build via `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../tla2lincheck")
}
```

### Running the Generator

```bash
# Generate Lincheck tests from all TLA+ specs
./gradlew generateLincheckTests

# Generate + run all tests
./gradlew generateLincheckTests test
```

Generated files are written to `src/test/tla2lincheck/com/actors/generated/`:

```
src/test/tla2lincheck/com/actors/generated/
├── ActorMailboxLincheckTest.kt       ← from ActorMailbox.tla
├── ActorLifecycleLincheckTest.kt     ← from ActorLifecycle.tla
├── RequestReplyLincheckTest.kt       ← from RequestReply.tla
├── ActorHierarchyLincheckTest.kt     ← from ActorHierarchy.tla
├── DeathWatchLincheckTest.kt         ← from DeathWatch.tla
└── ActorTraceLincheckTest.kt         ← from ActorTrace.tla
```

### What a Generated Test Looks Like

Each generated test class:
- Declares state fields from TLA+ `VARIABLES`
- Maps each TLA+ action to a Lincheck `@Operation`
- Calls `checkAllInvariants()` inside every operation
- Runs both model checking (exhaustive interleaving exploration) and stress testing

```kotlin
// AUTO-GENERATED from ActorMailbox.tla
class ActorMailboxLincheckTest {
    // State from TLA+ VARIABLES
    private val mailbox: MutableList<Any> = mutableListOf()
    private var recvCount: Any = 0
    private val sendCount: MutableMap<Int, Int> = mutableMapOf()

    @Operation
    fun send(@Param(name = "s") s: Int, @Param(name = "msg") msg: Int): String {
        // ... TLA+ action logic ...
        checkAllInvariants()  // ← embedded from TLA+ INVARIANTS
        return "ok"
    }

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(50)
        .threads(3)
        .actorsPerThread(2)
        .check(this::class)

    @Test
    fun stressTest() = StressOptions()
        .iterations(50)
        .threads(3)
        .actorsPerThread(2)
        .check(this::class)
}
```

### Hand-Written vs Generated Tests

The project has **both**:

| Type | Location | Purpose |
|------|----------|--------|
| Hand-written | `src/test/kotlin/com/actors/` | Test the real Kotlin implementation (ActorCell, Mailbox, etc.) |
| Auto-generated | `src/test/tla2lincheck/` | Test the TLA+ specification's abstract state machine directly |

Hand-written tests verify that the Kotlin code behaves correctly. Generated tests verify that the TLA+ spec itself is logically consistent under concurrent interleavings. Together, they close the verification loop:

```
TLA+ spec  ──TLC──▶  spec is correct (state space exhaustion)
                     │
                     ├──tla2lincheck──▶  spec is linearizable (Lincheck)
                     │
Kotlin code ──hand-written tests──▶  implementation matches spec
```

---

## Test Suite

| Test Class | Strategy | What it verifies |
|-----------|----------|-----------------|
| `MailboxLincheckTest` | Lincheck model + stress | Mailbox linearizability |
| `ActorLifecycleLincheckTest` | Lincheck model + stress | Lifecycle FSM linearizability |
| `RequestReplyLincheckTest` | Lincheck model + stress | Ask pattern linearizability |
| `ActorSystemTest` | JUnit 5 | Core functionality + invariants |
| `ActorConcurrencyTest` | Concurrent stress | Actors under load |
| `ActorHierarchyTest` | JUnit 5 | Parent-child, cascading stop |
| `SignalTest` | JUnit 5 | All signal types + DeathWatch |
| `ActorTraceTest` | JUnit 5 + concurrent | Flight recorder, Lamport clocks |
| `TraceabilityTest` | JUnit 5 + concurrent | Cross-actor tracing, NDJSON round-trip |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          ActorSystem                                │
│                    CoroutineScope (SupervisorJob)                    │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  ActorCell                      ActorRef<M>                    │  │
│  │  ┌──────────────┐  ┌─────────────────────────────────────┐    │  │
│  │  │  Mailbox     │  │  ActorContext                        │    │  │
│  │  │  (Channel)   │  │  self, spawn, watch, stop, log       │    │  │
│  │  │  Bounded     │  │  flightRecorder, traceContext         │    │  │
│  │  │  MPSC FIFO   │  └─────────────────────────────────────┘    │  │
│  │  └──────────────┘                                             │  │
│  │  ┌──────────────┐  ┌─────────────────────────────────────┐    │  │
│  │  │  Behavior<M> │  │  FlightRecorder (ring buffer)        │    │  │
│  │  │  (pure func) │  │  TraceEvents + Lamport clock         │    │  │
│  │  └──────────────┘  └─────────────────────────────────────┘    │  │
│  │       │                                                       │  │
│  │       ├── child ActorCell                                     │  │
│  │       │       ├── grandchild ActorCell                        │  │
│  │       │       └── grandchild ActorCell                        │  │
│  │       └── child ActorCell                                     │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Signals: PreStart → [running] → PostStop                           │
│           Terminated(ref)   ChildFailed(ref, cause)                  │
└─────────────────────────────────────────────────────────────────────┘
```

### Core Components

| Component | Purpose |
|-----------|---------|
| `ActorSystem` | Top-level container, coroutine scope, tree/trace dumps |
| `ActorCell` | Internal runtime: lifecycle FSM, message loop, hierarchy, flight recorder |
| `ActorRef<M>` | Type-safe handle: `tell`, `ask`, `tryTell` |
| `ActorContext<M>` | Actor's API: self, spawn, watch, stop, log, trace |
| `Behavior<M>` | Pure message handler: `(ActorContext<M>, M) -> Behavior<M>` |
| `Mailbox<M>` | Bounded FIFO channel with backpressure |
| `Signal` | Lifecycle events: PreStart, PostStop, Terminated, ChildFailed |
| `SupervisorStrategy` | Fault policy: stop, restart, resume, escalate, custom |
| `ActorConfig` | User-facing configuration bundle |
| `ActorFlightRecorder` | Per-actor bounded ring buffer of trace events |
| `TraceContext` | Distributed tracing: traceId/spanId propagation |
| `TraceReplay` | Post-mortem analysis: NDJSON load, timeline, causal chains |
| `ActorTreeDumper` | Supervision tree visualization (ASCII + JSON) |
| `MessageEnvelope` | Internal metadata wrapper: trace context + sender info |

---

## Repository Layout

```
actors/
├── src/
│   ├── main/
│   │   ├── kotlin/com/actors/        ← Core library source
│   │   │   ├── ActorCell.kt          ← Runtime: lifecycle, loop, hierarchy, deathwatch
│   │   │   ├── ActorConfig.kt        ← User-facing configuration bundle
│   │   │   ├── ActorContext.kt       ← Actor's API: spawn, watch, stop, log
│   │   │   ├── ActorFlightRecorder.kt← Per-actor trace buffer (ring buffer)
│   │   │   ├── ActorRef.kt           ← Message handle: tell, ask
│   │   │   ├── ActorState.kt         ← State enum: CREATED→STARTING→RUNNING→STOPPING→STOPPED
│   │   │   ├── ActorSystem.kt        ← Root container and spawn entry point
│   │   │   ├── ActorTreeDumper.kt    ← Supervision tree visualization
│   │   │   ├── Behavior.kt           ← Behavior<M> fun interface + combinators
│   │   │   ├── Dsl.kt                ← receive{}, setup{}, behavior{}, lifecycleBehavior{}
│   │   │   ├── Mailbox.kt            ← Bounded Channel wrapper
│   │   │   ├── MessageEnvelope.kt    ← Internal trace metadata per message
│   │   │   ├── Messages.kt           ← Common protocols: Request<R>
│   │   │   ├── Signal.kt             ← PreStart, PostStop, Terminated, ChildFailed
│   │   │   ├── SupervisorStrategy.kt ← STOP/RESTART/RESUME/ESCALATE
│   │   │   ├── TlaAnnotations.kt     ← @TlaSpec @TlaAction @TlaVariable @TlaInvariant
│   │   │   ├── TraceContext.kt       ← Distributed trace correlation (traceId/spanId)
│   │   │   ├── TraceEvent.kt         ← All observable event types
│   │   │   └── TraceReplay.kt        ← NDJSON import + post-mortem analysis
│   │   └── tla/                      ← TLA+ specifications
│   │       ├── ActorMailbox.tla/.cfg
│   │       ├── ActorLifecycle.tla/.cfg
│   │       ├── RequestReply.tla/.cfg
│   │       ├── ActorHierarchy.tla/.cfg
│   │       ├── DeathWatch.tla/.cfg
│   │       ├── ActorTrace.tla/.cfg
│   │       └── tla2tools.jar         ← Standalone TLC model checker
│   └── test/
│       ├── kotlin/com/actors/        ← Hand-written tests
│       └── tla2lincheck/             ← Auto-generated Lincheck tests
├── build.gradle.kts
├── settings.gradle.kts
├── README.md                         ← This file
└── AGENTS.md                         ← LLM agent instructions
```

---

## Comparison with Akka & Erlang

| Feature | Erlang/OTP | Akka Typed | This Library |
|---------|-----------|-----------|--------------|
| Typed messages | No | Yes | Yes (sealed classes) |
| Supervision | Out-of-band supervisor process | Strategy on parent | **In-band `ChildFailed` signal** |
| Backpressure | No (unbounded mailbox) | BoundedMailbox (opt-in) | **Always bounded** (default 256) |
| Tracing | `:observer`, `:dbg` | Lightbend Telemetry ($$$) | **Built-in flight recorder** (free) |
| Formal spec | QuickCheck/PropEr | None | **TLA+ verified** (6 specs) |
| Async behaviors | No | `CompletionStage` | **Native `suspend`** |
| Causal ordering | No | No | **Lamport clocks** |
| Post-mortem replay | Crash dumps | Thread dumps | **NDJSON export + `TraceReplay`** |

---

## Tech Stack

| Dependency | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0.21 | Language |
| JVM | 21 | Runtime |
| kotlinx-coroutines | 1.9.0 | Actor scheduling, Channel, select{} |
| Lincheck | 2.34 | Linearizability model checking |
| JUnit 5 | 5.11.2 | Test runner |
| AssertJ | 3.26.3 | Assertions |
| jqwik | 1.9.2 | Property-based testing |
| tla2lincheck | 0.1.0-SNAPSHOT | TLA+ → Lincheck test generation |

---

## Contributing

### Getting Started

```bash
# Clone the repository (and the tla2lincheck sibling)
git clone <repo-url> actors
git clone <repo-url> tla2lincheck   # must be in ../tla2lincheck relative to actors/

cd actors

# Build and test
./gradlew test

# Run TLC on all TLA+ specs
alias tlc="java -cp src/main/tla/tla2tools.jar tlc2.TLC"
for spec in ActorMailbox ActorLifecycle RequestReply ActorHierarchy DeathWatch ActorTrace; do
    echo "=== $spec ==="
    tlc src/main/tla/${spec}.tla -config src/main/tla/${spec}.cfg -workers auto
done

# Generate Lincheck tests from TLA+ specs
./gradlew generateLincheckTests
```

### Adding a New Feature (Checklist)

The project follows a strict **spec-first** workflow. New concurrent/lifecycle/messaging features must follow this order:

#### Step 1 — TLA+ Spec

- [ ] Identify which existing spec covers this, OR create a new `.tla` file
- [ ] Write `CONSTANTS`, `VARIABLES`, `TypeOK`, `Init`, actions, and safety invariants
- [ ] Each action must have a `\* Maps to: ClassName.methodName()` comment
- [ ] Each variable must have a `\* SUBSET Actors — ClassName.fieldName` comment
- [ ] Create `.cfg` with `SPECIFICATION Spec`, `CONSTANTS`, `INVARIANTS`
- [ ] Run TLC — **must complete with zero errors**

#### Step 2 — Kotlin Implementation

- [ ] Annotate the class with `@TlaSpec("ModuleName")`
- [ ] Annotate fields with `@TlaVariable("variableName")`
- [ ] Annotate methods with `@TlaAction("ActionName")`
- [ ] Write `@TlaInvariant("Name") fun checkXxx(): Boolean` for each invariant
- [ ] Add all invariant checks to `checkAllInvariants()`
- [ ] Use bounded channels (never unbounded on the message path)
- [ ] Include error messages with actor name: `check(...) { "... actor '$name' ..." }`

#### Step 3 — Tests

- [ ] JUnit 5 unit tests with `checkAllInvariants()` at the end of each test
- [ ] Concurrent stress test with `@RepeatedTest` and `CyclicBarrier`
- [ ] Lincheck test if the feature involves linearizable operations
- [ ] Re-generate tla2lincheck tests: `./gradlew generateLincheckTests`

#### Step 4 — Verify

- [ ] All 6+ TLA+ specs pass TLC with zero errors
- [ ] All existing tests still pass
- [ ] No compiler errors

### Code Conventions

| Rule | Detail |
|------|--------|
| **Behaviors are immutable** | State is encoded as function parameters, not `var` fields |
| **Messages are sealed** | `sealed class` or `sealed interface` with `data class` / `data object` variants |
| **No shared mutable state** | Actors communicate only via messages |
| **No `runBlocking` in production** | Only in tests |
| **No `GlobalScope`** | Exception: transient reply-ref actors (documented with `@OptIn`) |
| **Rethrow `CancellationException`** | `catch (e: CancellationException) { throw e }` |
| **ActorContext is request-scoped** | Never store it, pass it outside the behavior, or use from another coroutine |
| **Every `check()`/`require()` includes actor name** | `check(cond) { "... actor '$name' ..." }` |

### Forbidden Patterns

```kotlin
// ✗ Unbounded queue in hot path
val mailbox = Channel<M>(UNLIMITED)

// ✗ Shared mutable state
class MyActor { var counter = 0 }

// ✗ Mutable var in behavior closure
val b = behavior<Msg> { var state = 0; state++; Behavior.same() }

// ✗ Blocking in coroutine
Thread.sleep(1000)  // use delay()

// ✗ Touching another actor's internals
otherActor.cell.children  // use message passing
```

### Running Verification

```bash
# TLC model checking (all specs, must say "No error has been found")
for spec in ActorMailbox ActorLifecycle RequestReply ActorHierarchy DeathWatch ActorTrace; do
    tlc src/main/tla/${spec}.tla -config src/main/tla/${spec}.cfg -workers auto
done

# Kotlin tests + Lincheck
./gradlew test

# Check TLA+ annotation coverage (should never decrease)
grep -r "@TlaSpec\|@TlaAction\|@TlaVariable\|@TlaInvariant" \
     src/main/kotlin/com/actors/ | wc -l
```

### Commit Message Convention

Use descriptive messages that reference the spec when applicable:

```
feat(mailbox): add backpressure metrics [ActorMailbox.tla]
fix(lifecycle): CAS race in startInline [ActorLifecycle.tla]
test(deathwatch): add concurrent unwatch stress test
docs: update README with tla2lincheck guide
```
