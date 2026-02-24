# Actor Model Library

**Kotlin Coroutines + Channels · TLA+ Verified · Lincheck Tested**

A formally specified Actor Model library built on Kotlin Coroutines and Channels, inspired by Erlang/OTP and Akka Typed. Features parent-child supervision hierarchy, lifecycle signals, DeathWatch, and context-aware behaviors. Every core component is specified in TLA+, and linearizability is verified using JetBrains Lincheck with invariants auto-generated via tla2lincheck.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          ActorSystem                                │
│                    CoroutineScope (SupervisorJob)                    │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  ActorCell "guardian"          ◀── ActorRef<GuardianMsg>       │  │
│  │  ┌──────────┐  ActorContext   Behavior<M>   SupervisorStrategy│  │
│  │  │ Mailbox  │  ┌──────────────────────────────────┐           │  │
│  │  │(Channel) │  │ self, spawn(), watch(), stop()   │           │  │
│  │  └──────────┘  └──────────────────────────────────┘           │  │
│  │       │                                                       │  │
│  │       ├── child "worker-1"                                    │  │
│  │       │   ┌──────────┐  ActorContext  Behavior  Supervisor    │  │
│  │       │   │ Mailbox  │  SignalChannel (PreStart/PostStop/...) │  │
│  │       │   └──────────┘                                        │  │
│  │       │       │                                               │  │
│  │       │       └── grandchild "db-pool"                        │  │
│  │       │           ┌──────────┐  ActorContext  Behavior        │  │
│  │       │           │ Mailbox  │  SignalChannel                 │  │
│  │       │           └──────────┘                                │  │
│  │       │                                                       │  │
│  │       └── child "worker-2"                                    │  │
│  │           ┌──────────┐  ActorContext  Behavior  Supervisor    │  │
│  │           │ Mailbox  │  SignalChannel                         │  │
│  │           └──────────┘                                        │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Signals: PreStart ──▶ [running] ──▶ PostStop                       │
│           Terminated(ref)   ChildFailed(ref, cause)                  │
└─────────────────────────────────────────────────────────────────────┘
```

### Core Components

| Component | File | TLA+ Spec | Description |
|-----------|------|-----------|-------------|
| **Mailbox** | `Mailbox.kt` | `ActorMailbox.tla` | Bounded FIFO channel (send/receive/trySend/tryReceive) |
| **ActorCell** | `ActorCell.kt` | `ActorLifecycle.tla` | Runtime container: lifecycle FSM, select-based message loop, hierarchy management |
| **ActorRef** | `ActorRef.kt` | `RequestReply.tla` | Location-transparent handle: tell + ask pattern |
| **Behavior** | `Behavior.kt` | — | Functional message handler: `(ActorContext<M>, M) → Behavior<M>` |
| **ActorContext** | `ActorContext.kt` | — | Actor's view of the world: self, spawn, watch, stop, children |
| **Signal** | `Signal.kt` | — | Lifecycle events: PreStart, PostStop, Terminated, ChildFailed |
| **SupervisorStrategy** | `SupervisorStrategy.kt` | `ActorLifecycle.tla` | Fault tolerance: stop/restart/resume/escalate |
| **ActorSystem** | `ActorSystem.kt` | — | Top-level container with SupervisorJob scope |

---

## TLA+ Specifications

Three modular specs, each focused and small enough for TLC model checking in under 3–4 minutes:

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

The project includes `tla2tools.jar` for standalone TLC verification:

```bash
java -cp src/main/tla/tla2tools.jar tlc2.TLC src/main/tla/ActorMailbox.tla -config src/main/tla/ActorMailbox.cfg
java -cp src/main/tla/tla2tools.jar tlc2.TLC src/main/tla/ActorLifecycle.tla -config src/main/tla/ActorLifecycle.cfg
java -cp src/main/tla/tla2tools.jar tlc2.TLC src/main/tla/RequestReply.tla -config src/main/tla/RequestReply.cfg
```

Or create an alias for convenience:
```bash
alias tlc="java -cp src/main/tla/tla2tools.jar tlc2.TLC"
tlc src/main/tla/ActorMailbox.tla -config src/main/tla/ActorMailbox.cfg
tlc src/main/tla/ActorLifecycle.tla -config src/main/tla/ActorLifecycle.cfg
tlc src/main/tla/RequestReply.tla -config src/main/tla/RequestReply.cfg
```

### Using tla2lincheck (Gradle Plugin)

Add to `build.gradle.kts`:
```kotlin
plugins {
    id("io.github.tla2lincheck") version "0.1.0-SNAPSHOT"
}

tla2lincheck {
    tlaSourceDir.set(file("src/main/tla"))
    packageName.set("com.actors.generated")
    threads.set(3)
    actorsPerThread.set(2)
    embedInvariants.set(true)
}
```

Then: `./gradlew generateLincheckTests test`

---

## Usage Examples

### Basic Actor

```kotlin
// Define message protocol
sealed class CounterMsg {
    data class Increment(val n: Int) : CounterMsg()
    data class GetCount(val replyTo: ActorRef<Int>) : CounterMsg(), Request<Int>
}

// Create actor system and spawn actors
val system = ActorSystem.create("my-app")

val counter = system.spawn<CounterMsg>("counter", behavior { msg ->
    when (msg) {
        is CounterMsg.Increment -> Behavior.same()
        is CounterMsg.GetCount -> {
            msg.replyTo.tell(0)
            Behavior.same()
        }
    }
})

// Fire-and-forget
counter.tell(CounterMsg.Increment(5))

// Request-reply (ask pattern)
val count: Int = counter.ask { replyTo -> CounterMsg.GetCount(replyTo) }
```

### Context-Aware Actor with `receive`

The `receive` DSL gives access to `ActorContext` — the actor's view of the world:

```kotlin
val greeter = system.spawn("greeter", receive<GreetMsg> { ctx, msg ->
    ctx.log.info("${ctx.name} received: $msg")
    ctx.self.tell(GreetMsg.Ack)  // send message to self
    Behavior.same()
})
```

### Setup + Actor Hierarchy

Use `setup` for one-time initialization, then `receive` for message handling.
Actors spawn children via `context.spawn()`:

```kotlin
sealed class ManagerMsg {
    data class CreateWorker(val name: String) : ManagerMsg()
    data class Dispatch(val task: String) : ManagerMsg()
}

val manager = system.spawn("manager", setup<ManagerMsg> { ctx ->
    ctx.log.info("Manager starting, spawning initial workers...")
    val worker1 = ctx.spawn("worker-1", workerBehavior())
    val worker2 = ctx.spawn("worker-2", workerBehavior())

    receive { ctx, msg ->
        when (msg) {
            is ManagerMsg.CreateWorker -> {
                ctx.spawn(msg.name, workerBehavior())
                Behavior.same()
            }
            is ManagerMsg.Dispatch -> {
                // dispatch to workers...
                Behavior.same()
            }
        }
    }
})
```

### Stateful Behavior with Functional State

```kotlin
fun counter(count: Int = 0): Behavior<CounterMsg> = receive { ctx, msg ->
    when (msg) {
        is CounterMsg.Increment -> counter(count + msg.n)  // New behavior with new state
        is CounterMsg.GetCount -> {
            msg.replyTo.tell(count)
            Behavior.same()  // Keep current behavior
        }
    }
}

val ref = system.spawn("counter", counter())
```

### Signals and DeathWatch

Actors receive lifecycle signals via `.onSignal {}`:

```kotlin
val monitored = system.spawn("worker", workerBehavior())

val watcher = system.spawn("watcher", receive<WatcherMsg> { ctx, msg ->
    Behavior.same()
}.onSignal { signal ->
    when (signal) {
        is Signal.Terminated -> println("Actor ${signal.ref} died!")
        is Signal.ChildFailed -> println("Child ${signal.ref} failed: ${signal.cause}")
        is Signal.PreStart -> println("Starting up")
        is Signal.PostStop -> println("Shutting down")
    }
    Behavior.same()
})
```

Watch another actor to be notified when it stops:

```kotlin
val supervisor = setup<SupervisorMsg> { ctx ->
    val child = ctx.spawn("child", childBehavior())
    ctx.watch(child)  // Will receive Signal.Terminated when child stops

    receive<SupervisorMsg> { ctx, msg ->
        Behavior.same()
    }.onSignal { signal ->
        when (signal) {
            is Signal.Terminated -> {
                println("Child stopped, spawning replacement")
                ctx.spawn("child", childBehavior())
                Behavior.same()
            }
            else -> Behavior.same()
        }
    }
}
```

### Lifecycle Hooks (legacy DSL)

```kotlin
val actor = system.spawn("db-actor", lifecycleBehavior<DbMsg>(
    onStart = { println("Connecting to database...") },
    onStop = { println("Closing database connection...") }
) { msg ->
    // handle messages
    Behavior.same()
})
```

### Cascading Stop

Stopping a parent automatically stops all its children (depth-first):

```kotlin
val parent = system.spawn("parent", setup<ParentMsg> { ctx ->
    ctx.spawn("child-a", childBehavior())
    ctx.spawn("child-b", childBehavior())
    receive { _, _ -> Behavior.same() }
})

// Stopping parent cascades to child-a and child-b
system.terminate()
```

### Fault Tolerance

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

---

## Test Suite

| Test Class | Strategy | TLA+ Spec | What it verifies |
|-----------|----------|-----------|-----------------|
| `MailboxLincheckTest` | Lincheck model check + stress | `ActorMailbox.tla` | Mailbox linearizability + invariants |
| `ActorLifecycleLincheckTest` | Lincheck model check + stress | `ActorLifecycle.tla` | Lifecycle FSM linearizability |
| `RequestReplyLincheckTest` | Lincheck model check + stress | `RequestReply.tla` | Ask pattern linearizability |
| `ActorSystemTest` | JUnit 5 unit tests | All specs | Core functionality + invariant checks |
| `ActorConcurrencyTest` | Concurrent stress tests | All specs | Real actor system under load |
| `ActorHierarchyTest` | JUnit 5 unit tests | — | Parent-child spawning, cascading stop, context.stop(child) |
| `SignalTest` | JUnit 5 unit tests | — | PreStart, PostStop, Terminated, ChildFailed, DeathWatch, setup/receive DSL |

---

## Design Decisions

### Why Kotlin Coroutines + Channels?

1. **One coroutine per actor** — sequential message processing without locks
2. **Channel as mailbox** — built-in backpressure, bounded capacity, thread-safe MPSC
3. **SupervisorJob** — child failure isolation (one actor crash doesn't kill siblings)
4. **Structured concurrency** — system shutdown cancels all actor coroutines cleanly

### Why Context-as-Parameter (not CoroutineContext)?

Inspired by Akka Typed's `ActorContext<T>`. The context is passed explicitly to every behavior invocation:

```kotlin
fun interface Behavior<M : Any> {
    suspend fun onMessage(context: ActorContext<M>, message: M): Behavior<M>
}
```

Benefits:
1. **Explicit dependencies** — behavior knows exactly what it can access
2. **Testable** — context can be mocked or stubbed
3. **No magic** — spawning a child is `ctx.spawn()`, not a global ambient function
4. **Type-safe** — `ActorContext<M>` is parameterized on the actor's message type

### Why Select-Based Message Loop?

The message loop uses `kotlinx.coroutines.selects.select {}` to multiplex two channels:

```kotlin
select {
    signalChannel.onReceive { signal -> handleSignal(signal) }
    mailbox.channel.onReceive { message -> handleMessage(message) }
}
```

This ensures:
1. **Signal priority** — lifecycle signals (PreStart, PostStop, Terminated) are drained before messages
2. **No separate coroutine** — signals and messages are processed in the same sequential loop
3. **Clean shutdown** — PostStop signal is delivered after all children have stopped

### Why Three Small TLA+ Specs?

1. **Modularity** — each spec is independently checkable (<1M states)
2. **Composability** — specs can be combined for larger system verification
3. **Educational** — each spec teaches one concept (mailbox, lifecycle, request-reply)
4. **Fast feedback** — TLC finishes in 30s–2min per spec

### Erlang/Akka Inspiration

| Concept | Erlang/OTP | Akka Typed | This Library |
|---------|-----------|-----------|--------------|
| Message handler | `receive` clause | `Behavior<T>` | `Behavior<M>` (fun interface) |
| Actor context | `self()` | `ActorContext<T>` | `ActorContext<M>` |
| Child spawning | `spawn_link` | `ctx.spawn()` | `ctx.spawn()` |
| Death monitoring | `monitor` | `ctx.watch()` | `ctx.watch()` |
| Lifecycle events | `init/terminate` | `Signal` | `Signal` sealed class |
| State machine | `gen_statem` | `Behaviors.receive()` | `receive {}` / `setup {}` |
| Supervision | `one_for_one` | `SupervisorStrategy` | `SupervisorStrategy` |

### TLA+ ↔ Kotlin Traceability

Every Kotlin class, method, and field is annotated with `@TlaSpec`, `@TlaAction`, `@TlaVariable`, or `@TlaInvariant` linking it to the corresponding TLA+ element. This creates a machine-readable traceability matrix for the PhD thesis.

---

## Future: Distributed Actor Model

The current single-node design is structured for distributed extension:

| Feature | Current | Future |
|---------|---------|--------|
| ActorRef | Local (direct mailbox) | Remote (network routing) |
| Location | Single JVM | Multi-node cluster |
| Discovery | By name (HashMap) | Distributed registry |
| Supervision | Local supervisor tree | Cross-node supervision |
| TLA+ | Safety invariants | + Network partition specs |

```
Node 1                     Node 2
┌──────────────┐           ┌──────────────┐
│ ActorSystem  │◀─────────▶│ ActorSystem  │
│  Actor A     │  Cluster   │  Actor C     │
│  Actor B     │  Protocol  │  Actor D     │
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
