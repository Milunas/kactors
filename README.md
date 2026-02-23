# Actor Model Library

**Kotlin Coroutines + Channels · TLA+ Verified · Lincheck Tested**

A formally specified Actor Model library built on Kotlin Coroutines and Channels, designed as a foundation for a distributed actor system. Every core component is specified in TLA+, and linearizability is verified using JetBrains Lincheck with invariants auto-generated via tla2lincheck.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        ActorSystem                              │
│                  CoroutineScope (SupervisorJob)                  │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐  │
│  │    ActorCell      │  │    ActorCell      │  │  ActorCell   │  │
│  │  ┌────────────┐  │  │  ┌────────────┐  │  │  ┌────────┐  │  │
│  │  │  Mailbox   │  │  │  │  Mailbox   │  │  │  │Mailbox │  │  │
│  │  │ (Channel)  │  │  │  │ (Channel)  │  │  │  │(Chan.) │  │  │
│  │  └────────────┘  │  │  └────────────┘  │  │  └────────┘  │  │
│  │  Behavior<M>     │  │  Behavior<M>     │  │  Behavior<M> │  │
│  │  Supervisor      │  │  Supervisor      │  │  Supervisor  │  │
│  └──────────────────┘  └──────────────────┘  └──────────────┘  │
│         ▲                      ▲                    ▲           │
│     ActorRef              ActorRef              ActorRef        │
└─────────────────────────────────────────────────────────────────┘
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
    var count = 0
    when (msg) {
        is CounterMsg.Increment -> {
            count += msg.n
            Behavior.same()
        }
        is CounterMsg.GetCount -> {
            msg.replyTo.tell(count)
            Behavior.same()
        }
    }
})

// Fire-and-forget
counter.tell(CounterMsg.Increment(5))

// Request-reply (ask pattern)
val count: Int = counter.ask { replyTo -> CounterMsg.GetCount(replyTo) }
```

### Stateful Behavior with Functional State

```kotlin
fun counter(count: Int = 0): Behavior<CounterMsg> = behavior { msg ->
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

### Lifecycle Hooks

```kotlin
val actor = system.spawn("db-actor", lifecycleBehavior<DbMsg>(
    onStart = { println("Connecting to database...") },
    onStop = { println("Closing database connection...") }
) { msg ->
    // handle messages
    Behavior.same()
})
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

---

## Design Decisions

### Why Kotlin Coroutines + Channels?

1. **One coroutine per actor** — sequential message processing without locks
2. **Channel as mailbox** — built-in backpressure, bounded capacity, thread-safe MPSC
3. **SupervisorJob** — child failure isolation (one actor crash doesn't kill siblings)
4. **Structured concurrency** — system shutdown cancels all actor coroutines cleanly

### Why Three Small TLA+ Specs?

1. **Modularity** — each spec is independently checkable (<1M states)
2. **Composability** — specs can be combined for larger system verification
3. **Educational** — each spec teaches one concept (mailbox, lifecycle, request-reply)
4. **Fast feedback** — TLC finishes in 30s–2min per spec

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
