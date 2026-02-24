# AGENTS.md — LLM Agent Instructions

**Actor Model Library: Formally Specified · Fault Tolerant · Traceable · Academic-Grade**

This document is the authoritative guide for any LLM agent working on this project.
Read it fully before making any change. Every section is mandatory.

---

## Table of Contents

1. [Project Identity](#1-project-identity)
2. [Repository Layout](#2-repository-layout)
3. [Core Principles — Never Violate](#3-core-principles--never-violate)
4. [Architecture Constraints](#4-architecture-constraints)
5. [TLA+ Specification Rules](#5-tla-specification-rules)
6. [Kotlin Code Rules](#6-kotlin-code-rules)
7. [Fault Tolerance & Backpressure Rules](#7-fault-tolerance--backpressure-rules)
8. [Traceability & Debuggability Rules](#8-traceability--debuggability-rules)
9. [Testing Requirements](#9-testing-requirements)
10. [Checklist: Adding a New Feature](#10-checklist-adding-a-new-feature)
11. [Forbidden Patterns](#11-forbidden-patterns)
12. [How to Run Verification](#12-how-to-run-verification)
13. [Open Roadmap Areas](#13-open-roadmap-areas)
14. [Academic Writing Standards](#14-academic-writing-standards)

---

## 1. Project Identity

### What This Project Is

A **formally specified Actor Model library** for the JVM, built on Kotlin Coroutines and Channels.
Its three equal goals are:

| Goal | Meaning |
|------|---------|
| **Fault tolerant** | Better backpressure and supervision than Akka Typed or Erlang/OTP on JVM |
| **Traceable & debuggable** | Every message, signal, lifecycle event must be observable and linked to a spec |
| **Academic / PhD-grade** | Every design decision must be provable at the TLA+ level and publishable |

This is **not** an application framework. It is a **library** that applications build on top of.
Do not add application-level concepts (Kafka wrappers, HTTP adapters, etc.) to the core library.
Those belong in example modules or separate repositories.

### The Thesis Context

This library is part of a PhD research project on:
- Formal verification of concurrent systems with TLA+ and Lincheck
- The traceability between TLA+ specifications and Kotlin implementations
- Actor model semantics for fault-tolerant distributed systems

Every invariant, annotation, and spec comment may end up in an academic paper.
Write with that level of precision.

### Inspiration Hierarchy

```
Erlang/OTP ──▶ Process links, monitors, let-it-crash, supervision trees
Akka Typed  ──▶ Typed behaviors, ActorContext as parameter, signal handling
This Library ──▶ Better: TLA+-verified, better backpressure, suspendable behaviors,
                          machine-readable annotations, modular specs
```

Be better than both where it matters. Study the gaps in Akka Typed and Erlang before adding features.

---

## 2. Repository Layout

```
actors/
├── src/
│   ├── main/
│   │   ├── kotlin/com/actors/       ← Core library (8 source files)
│   │   │   ├── ActorCell.kt         ← Runtime: lifecycle, loop, hierarchy, deathwatch
│   │   │   ├── ActorContext.kt      ← Actor's API: spawn, watch, stop, log
│   │   │   ├── ActorRef.kt          ← Message handle: tell, ask
│   │   │   ├── ActorState.kt        ← State enum: CREATED→STARTING→RUNNING→STOPPING→STOPPED
│   │   │   ├── ActorSystem.kt       ← Root container and spawn entry point
│   │   │   ├── Behavior.kt          ← Behavior<M> fun interface + SetupBehavior, SignalBehavior
│   │   │   ├── Dsl.kt               ← receive{}, setup{}, behavior{}, lifecycleBehavior{}
│   │   │   ├── Mailbox.kt           ← Bounded Channel wrapper with invariant checks
│   │   │   ├── Messages.kt          ← Common protocols: Request<R>, StatusResponse
│   │   │   ├── Signal.kt            ← PreStart, PostStop, Terminated, ChildFailed
│   │   │   ├── SupervisorStrategy.kt← STOP/RESTART/RESUME/ESCALATE + custom decider
│   │   │   └── TlaAnnotations.kt    ← @TlaSpec @TlaAction @TlaVariable @TlaInvariant
│   │   └── tla/                     ← TLA+ specifications (1 spec = 1 concern)
│   │       ├── ActorMailbox.tla/.cfg
│   │       ├── ActorLifecycle.tla/.cfg
│   │       ├── RequestReply.tla/.cfg
│   │       ├── ActorHierarchy.tla/.cfg
│   │       ├── DeathWatch.tla/.cfg
│   │       └── tla2tools.jar        ← Standalone TLC model checker
│   └── test/
│       └── kotlin/com/actors/
│           ├── ActorSystemTest.kt        ← JUnit5 unit tests, invariant checks
│           ├── ActorConcurrencyTest.kt   ← Real concurrent stress tests
│           ├── ActorHierarchyTest.kt     ← Hierarchy and cascading stop tests
│           ├── SignalTest.kt             ← Signal ordering, DeathWatch tests
│           ├── ActorLifecycleLincheckTest.kt  ← Lincheck: lifecycle FSM
│           ├── MailboxLincheckTest.kt         ← Lincheck: mailbox linearizability
│           └── RequestReplyLincheckTest.kt    ← Lincheck: ask pattern
├── build.gradle.kts                    ← Kotlin 2.0.21 / JVM 21 / Coroutines 1.9.0
├── settings.gradle.kts                 ← Composite build with ../tla2lincheck
├── README.md                           ← Human-readable project documentation
└── AGENTS.md                           ← This file
```

### Key Dependency Versions (Never Downgrade)

| Dependency | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0.21 | Language |
| JVM | 21 | Runtime (ZGC GC) |
| kotlinx-coroutines | 1.9.0 | Actor scheduling, Channel, select{} |
| Lincheck | 2.34 | Linearizability model checking + stress |
| JUnit 5 | 5.11.2 | Test runner |
| AssertJ | 3.26.3 | Assertion library |
| jqwik | 1.9.2 | Property-based testing |
| tla2lincheck | 0.1.0-SNAPSHOT | TLA+ → Lincheck test generation |

---

## 3. Core Principles — Never Violate

These are non-negotiable. Any PR that breaks one must be rejected.

### P1 — One Spec Per Concern

Every behavioral aspect of the system must have exactly one TLA+ spec.
Specs must be independent and composable.
A single `.tla` file must be checkable in under 5 minutes by TLC on a laptop.

### P2 — Every Kotlin Feature Has a TLA+ Spec First

Do not write Kotlin code for new concurrent/lifecycle/messaging behavior without a TLA+ spec.
The spec defines the correctness criterion. The Kotlin code proves it can be implemented.
The Lincheck test proves the implementation doesn't violate it under real interleavings.

Order: **TLA+ spec → Kotlin code → `@Tla*` annotations → Lincheck/JUnit tests**

### P3 — Backpressure Is Non-Optional

Every message channel must be bounded.
No unbounded queues in the hot path.
Signal channels are the only exception (they are bounded by the number of lifecycle events per actor lifetime, which is provably small).

### P4 — Full Traceability Chain

Every Kotlin construct that implements a TLA+ concept must be annotated:
- Class → `@TlaSpec("ModuleName")`
- Field → `@TlaVariable("variableName")`
- Method → `@TlaAction("ActionName")` or `@TlaInvariant("InvariantName")`

This annotation coverage must be maintained at 100% for all files in `src/main/kotlin/com/actors/`.

### P5 — No Shared Mutable State Between Actors

Actor state lives exclusively inside behaviors (functional pattern) or inside the actor's own `ActorCell`.
Never pass mutable objects via messages. Messages must be effectively immutable (data classes, sealed classes, primitives).

### P6 — Backward Compatibility of the DSL

The `behavior {}`, `statelessBehavior {}`, and `lifecycleBehavior {}` DSL functions must remain
compatible across changes. They are the user-facing API. Internal refactoring is fine; changing
these signatures or removing them is not.

### P7 — TLC Must Pass Before Merge

After any TLA+ change, all 5 specs must pass TLC with zero errors before the change is merged.
Run all specs via:
```bash
alias tlc="java -cp src/main/tla/tla2tools.jar tlc2.TLC"
for spec in ActorMailbox ActorLifecycle RequestReply ActorHierarchy DeathWatch; do
  echo "=== $spec ===" && tlc src/main/tla/$spec.tla -config src/main/tla/$spec.cfg -workers auto
done
```

---

## 4. Architecture Constraints

### Actor Message Processing Loop

The message loop in `ActorCell.messageLoop()` uses `kotlinx.coroutines.selects.select {}`.
This is fundamental — do not replace it with polling, `while(true) { channel.receive() }`, or
separate coroutines for signals. The select-based loop is what gives signal priority guarantees
that correspond to the `PreStartBeforeProcess` invariant in `ActorLifecycle.tla`.

```kotlin
// CORRECT: select multiplexes both channels with signal priority
select {
    signalChannel.onReceive { signal -> handleSignal(signal) }
    mailbox.channel.onReceive { message -> handleMessage(message) }
}

// WRONG: signals and messages compete equally, no priority guarantee
coroutineScope {
    launch { for (s in signalChannel) handleSignal(s) }
    launch { for (m in mailbox.channel) handleMessage(m) }
}
```

### Behavior Immutability Pattern

Behaviors are **immutable state machines**. State is encoded functionally as parameters:

```kotlin
// CORRECT: state is closure parameter, each message returns new behavior
fun counter(count: Int): Behavior<Msg> = receive { ctx, msg ->
    when (msg) {
        is Increment -> counter(count + 1)  // new behavior, new state
        is Get -> { msg.reply.tell(count); Behavior.same() }
    }
}

// WRONG: mutable state inside behavior lambda
val count = AtomicInteger(0)
val counter = behavior<Msg> { msg ->
    when (msg) { is Increment -> count.incrementAndGet() }  // shared mutable state
    Behavior.same()
}
```

### ActorContext Scoping

`ActorContext<M>` is only valid inside the actor's own coroutine (the message processing loop).
Never store it, pass it to external lambdas that outlive the message invocation, or call it
from a different coroutine. This is analogous to `HttpContext` in request-scoped web frameworks.

### Hierarchy Parent-Child Contract

- A child actor's name is always `"$parentName/$localName"`.
- `parent?.onChildStopped(this)` must be called in `performStop()` — never skip this.
- `stopAllChildren()` must complete (all children join) before `PostStop` is delivered.
  This ordering is verified by TLC in `ActorHierarchy.tla` (`StoppedHasNoChildren` invariant).

### SupervisorJob Per Actor

Each `ActorCell` creates its own `childScope = CoroutineScope(SupervisorJob(...))`.
This is what gives child failure isolation. Never replace this with a flat scope or shared executor.

---

## 5. TLA+ Specification Rules

### Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Module name | PascalCase, matches behavior | `ActorHierarchy` |
| Variables | camelCase | `actorState`, `restartCount` |
| Constants | PascalCase | `MaxRestarts`, `Actors` |
| Actions | PascalCase verb | `SpawnChild`, `ProcessMessage`, `Die` |
| Invariants | PascalCase sentence | `ChildParentConsistency`, `NoPhantomTerminated` |

### Required Sections in Every Spec

Every `.tla` file must contain, in this order:
1. Module header comment (purpose, state space estimate, TLC timing, Kotlin mapping)
2. `EXTENDS` clause (only what is needed)
3. `CONSTANTS` with one-line comment per constant
4. `VARIABLES` with one-line comment per variable
5. `vars` tuple
6. `TypeOK` invariant
7. `Init` predicate
8. Actions section (`\* ─── Actions ───`)
9. `Next` disjunction (with terminal stutter step to prevent TLC deadlock)
10. Safety Invariants section (`\* ─── Safety Invariants ───`) with comment per invariant
11. `Spec == Init /\ [][Next]_vars`

### State Space Management

Keep state spaces small for fast feedback:
- 3 actors maximum in lifecycle/hierarchy configs
- 4 actors maximum in DeathWatch config
- Bound all counters: use `MaxMessages`, `MaxPending`, etc.
- Add a terminal stutter step in `Next` to prevent TLC deadlock:
  ```
  \/ (terminalCondition) /\ UNCHANGED vars
  ```
- Use model values (not `CHOOSE x : x \notin S`) for sentinel constants like `None`.

### Kotlin Mapping Comments

Every action must document its Kotlin mapping:
```tla
\* Die(a) — Maps to: ActorCell.performStop() → notifyWatchers()
Die(a) == ...
```

Every variable must document its Kotlin mapping:
```tla
VARIABLES
    alive,   \* SUBSET Actors — ActorCell.stateRef != STOPPED
    watchers \* [Actors -> SUBSET Actors] — ActorCell.watchers (CopyOnWriteArraySet)
```

### Adding New Invariants

When adding an invariant:
1. Add it to the `.tla` file with a numbered comment (`\* INV-N: description`)
2. Add it to the `.cfg` file `INVARIANTS` list
3. Run TLC to confirm it holds
4. Add a corresponding `@TlaInvariant("InvariantName") fun check...()` method in the Kotlin class
5. Call the check method from `checkAllInvariants()`

---

## 6. Kotlin Code Rules

### File Header Format

Every source file in `src/main/kotlin/` must begin with a KDoc block containing:
```kotlin
/**
 * ═══════════════════════════════════════════════════════════════════
 * COMPONENT NAME: Short Description
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Specs: Which .tla files cover this class
 *
 * What it does (2-3 sentences, precise enough for a paper)
 *
 * Design note: Why this design was chosen vs alternatives
 *
 * Example:
 * ```kotlin
 * // Minimal working example
 * ```
 */
```

### TLA+ Annotation Coverage

All of the following must be annotated in every class:

| What to annotate | Annotation |
|-----------------|-----------|
| The class itself | `@TlaSpec("ModuleName")` — or multiple: `@TlaSpec("A|B|C")` |
| `AtomicReference` fields tracking TLA+ state | `@TlaVariable("tlaVarName")` |
| `AtomicInteger` counters tracking TLA+ variables | `@TlaVariable("tlaVarName")` |
| Collections tracking TLA+ functions/sets | `@TlaVariable("tlaVarName")` |
| Methods implementing TLA+ actions | `@TlaAction("ActionName")` |
| Methods checking TLA+ invariants | `@TlaInvariant("InvariantName")` |

**Missing annotations are bugs**, not style issues.

### Coroutine Discipline

- `suspend` functions may only be called from within an actor's coroutine scope or in tests.
- `runBlocking` is only for tests. Never use in production code.
- `GlobalScope` is only for transient reply-ref actors (`startInline`). Never elsewhere.
- `@OptIn(DelicateCoroutinesApi::class)` must accompany every `GlobalScope` usage.
- `CancellationException` must always be rethrown: `catch (e: CancellationException) { throw e }`.

### Sealed Classes for Messages

All actor message types must be `sealed class` or `sealed interface`.
Data classes for messages with fields. `data object` for singleton messages.
Provide explicit `Request<R>` interface for ask-pattern messages:

```kotlin
sealed class WorkerMsg {
    data class Process(val task: Task) : WorkerMsg()
    data class Query(val replyTo: ActorRef<WorkerStatus>, val id: String)
        : WorkerMsg(), Request<WorkerStatus>   // ← marks as ask-compatible
    data object Shutdown : WorkerMsg()
}
```

### Naming

| Concept | Convention |
|---------|-----------|
| Actor name (runtime) | `"parent/child/grandchild"` — slash-separated path |
| Behavior factory | `fun counterBehavior(state: Int = 0): Behavior<Msg>` |
| Test class | `ComponentNameTest` for unit, `ComponentNameLincheckTest` for Lincheck |
| TLA+ spec file | `ConceptName.tla` — matches the concern, not the Kotlin class |

### Error Messages

Every `check()`, `require()`, and `error()` must include the actor's name:
```kotlin
check(stateRef.compareAndSet(ActorState.CREATED, ActorState.STARTING)) {
    "Cannot start actor '$name' in state ${stateRef.get()}"
}
```

---

## 7. Fault Tolerance & Backpressure Rules

This library must be **better** than Akka and Erlang/OTP in these specific ways.
Every design decision here is deliberate.

### Backpressure Architecture

```
Sender (tell)              ActorCell
─────────────   ──────▶   ┌──────────────────────────────────┐
                          │ Mailbox (bounded Channel)         │
                          │ capacity: configurable, default 16 │
                          │                                  │
                          │ On FULL: tell() suspends until   │
                          │          space is available      │
                          │          trySend() returns false  │
                          └──────────────────────────────────┘
```

Rules:
1. **Default capacity = 16**: `Mailbox.DEFAULT_CAPACITY = 16`. Chosen to match CPU cache lines.
   Never change this without a performance benchmark and TLA+ analysis.
2. **`tell()` suspends on full mailbox** (via `channel.send()`). This is correct backpressure.
   It propagates the backpressure signal to the sender's coroutine.
3. **`trySend()` returns `false` on full mailbox**. Never throws. Callers decide on drop vs retry.
4. **Signal channel is `Channel.UNLIMITED`**. This is intentional — signals are lifecycle events,
   not load. There are at most `O(children + watchers)` signals per actor lifetime.
   Adding backpressure to signals would risk deadlock during shutdown cascades.
5. **No message loss by default**. If a message cannot be delivered, the sender's coroutine
   suspends. Message loss is only possible via `trySend()` when the caller explicitly chooses it.

### Supervision: Better Than Erlang/OTP

Erlang's supervision is out-of-band (the supervisor is a separate process).
This library does in-band supervision: the parent actor receives `ChildFailed` signal
and can react as part of its normal behavior loop.

```kotlin
receive<ParentMsg> { ctx, msg -> Behavior.same() }
    .onSignal { ctx, signal ->
        when (signal) {
            is Signal.ChildFailed -> {
                // Erlang: supervisor process receives EXIT signal separately
                // This library: parent actor gets signal inline, can update its own state
                logger.error("Child ${signal.ref.name} failed: ${signal.cause}")
                ctx.spawn("replacement", childBehavior())  // spawn replacement here
                Behavior.same()
            }
            else -> Behavior.same()
        }
    }
```

Key requirement: `ChildFailed` signal is delivered **before** the supervisor strategy fires.
This ordering is modeled in `ActorLifecycle.tla` and must be preserved.

### SupervisorStrategy Defaults

The default for top-level actors is `SupervisorStrategy.stop()` (safest).
The default for `ctx.spawn()` children is `SupervisorStrategy.restart(maxRestarts = 3)`.
Never change these defaults without updating the TLA+ config `MaxRestarts` constant.

### Cascading Stop Is Depth-First, Synchronous

`stopAllChildren()` calls `child.stop()` and then `child.awaitTermination()` for each child.
Children stop in insertion order. Each child recursively stops its own children first.
This ensures `PostStop` is delivered bottom-up (leaf actors first).

**Never** make this concurrent (e.g., parallel `launch { child.stop() }`) unless you can
prove the `StoppedHasNoChildren` invariant still holds in `ActorHierarchy.tla`.

---

## 8. Traceability & Debuggability Rules

This is a key differentiator. The goal: any message or signal in the system can be
fully traced from the TLA+ model level down to the running JVM process.

### Structured Logging

Every `ActorContext` provides a logger scoped to the actor's path:
```kotlin
val log = LoggerFactory.getLogger("actor.${self.name}")
// Output: actor.system/parent/child - processed Increment(5)
```

All actor logs must use this logger. Never use a class-level logger for actor-internal events.
The actor path is the primary tracing token.

### Required Log Points

Every `ActorCell` must log at these lifecycle moments (already implemented, keep them):

| Event | Level | Message format |
|-------|-------|---------------|
| Started (RUNNING) | DEBUG | `"Actor '{}' is now RUNNING"` |
| Signal delivered | DEBUG | (handled by `deliverSignal`) |
| Failure + directive | WARN/INFO | `"Actor '{}' failed: {} → {}"` |
| Restart | INFO | `"Actor '{}' restarting (attempt {}/{})"` |
| Stopped | DEBUG | `"Actor '{}' is now STOPPED (processed: {}, restarts: {}, children stopped)"` |

### Invariant Checks as Assertions

Every `ActorCell` exposes `checkAllInvariants()`. In test environments, this must be called:
- After each message processing (via Lincheck's `@Operation` wrappers)
- At teardown of every JUnit5 test
- In stress tests' assertion phase

This is how TLA+ invariants are enforced at runtime. A failed invariant check means
the Kotlin implementation diverged from the TLA+ model.

### Actor Path as Distributed Trace ID

Actor names follow the hierarchical path convention: `"systemName/parentName/childName"`.
This path is:
1. The Slf4j logger name: `actor.systemName/parentName/childName`
2. The primary key for any debugging tool
3. The link between a running actor and its TLA+ spec actor identifier

Future: when distributed actors are added, this path becomes the distributed trace ID.
Design all new features to preserve and extend this path convention.

### Debuggability Wishlist (Motivating Future Work)

These are explicitly unimplemented and represent future directions:
- **Message trace buffer** per actor: ring buffer of last N messages processed
- **Signal history** per actor: ordered list of signals delivered in this lifetime
- **Actor tree visualization**: dump the live supervision tree as JSON/DOT
- **Slow actor detection**: log when an actor's message processing exceeds a threshold
- **Dead letter office**: capture messages sent to stopped actors
- **Mailbox metrics**: per-actor gauges for mailbox size, send rate, receive rate

When implementing any of the above, it must come with a TLA+ spec and `@Tla*` annotations.

---

## 9. Testing Requirements

### Test Coverage Matrix

Every feature must have tests in all applicable categories:

| Category | When Required | Test Pattern |
|----------|--------------|-------------|
| **JUnit5 unit test** | Always | Functional correctness, edge cases, invariant checks at teardown |
| **Concurrency stress test** | Any concurrent feature | `@RepeatedTest(5)` with `CyclicBarrier`, check message counts |
| **Lincheck model check** | Any linearizable operation | `@Operation` on each operation, `ModelCheckingOptions` |
| **Lincheck stress test** | Complements model check | `StressOptions` to catch races under real scheduling |
| **TLA+ spec** | Any new behavior | Spec + cfg, TLC passes, invariants embedded in Lincheck test |

### JUnit5 Test Conventions

```kotlin
class MyFeatureTest {

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() { system = ActorSystem.create("test-system") }

    @AfterEach
    fun teardown() = runBlocking {
        // Always terminate, even if test fails
        if (!system.isTerminated) system.terminate()
    }

    @Test
    fun `descriptive behavior-driven name`() = runBlocking {
        // Arrange
        val ref = system.spawn("actor", myBehavior())

        // Act
        ref.tell(SomeMessage)
        delay(100.milliseconds)

        // Assert
        assertThat(something).isEqualTo(expected)

        // Invariants — always call at end of test
        // This is where TLA+ formally meets the running code
        ref.actorCell.checkAllInvariants()
    }
}
```

### Lincheck Test Conventions

```kotlin
class MyLincheckTest : VerifierState() {

    private val subject = MyStructure()

    @Operation
    fun myOperation(arg: Int): Result {
        val result = subject.doSomething(arg)
        // CRITICAL: embed TLA+ invariant checks inside every operation
        subject.checkAllInvariants()
        return result
    }

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(100)
        .invocationCountPerIteration(10_000)
        .check(this::class)

    @Test
    fun stressTest() = StressOptions()
        .iterations(50)
        .invocationCountPerIteration(50_000)
        .check(this::class)
}
```

### Delay-Free Assertions

Avoid `delay(N.milliseconds)` in tests where possible. It is acceptable only when:
- Waiting for async signal delivery (200ms max)
- Waiting for coroutine scheduling (100ms max)

Prefer `awaitTermination()`, `CompletableDeferred`, or `CountDownLatch` for deterministic waiting.

### What Tests Must NOT Do

- Test implementation internals (access to `ActorCell` private fields via reflection)
- Use `Thread.sleep()` — use `delay()` in coroutines
- Use unbounded mailboxes in tests (always specify `mailboxCapacity` to stress backpressure)
- Share `ActorSystem` between tests — each test gets its own system

---

## 10. Checklist: Adding a New Feature

Follow this checklist **in order** for any new behavioral feature.

### Step 1 — Define the TLA+ Spec

- [ ] Identify which existing spec covers this, OR create a new `.tla` file
- [ ] Write the module header comment with:
  - Purpose (one sentence)
  - State space estimate (how many states with config constants)
  - TLC timing estimate
  - Kotlin mapping for each variable
- [ ] Define `CONSTANTS`, `VARIABLES`, `TypeOK`, `Init`
- [ ] Write each action with `\* Maps to: ClassName.methodName()` comment
- [ ] Write safety invariants (numbered `INV-N:`) — at least 3 per spec
- [ ] Add terminal stutter step in `Next` to prevent TLC deadlock
- [ ] Create `.cfg` with `SPECIFICATION Spec`, `CONSTANTS`, `INVARIANTS`
- [ ] Run TLC: **must complete with zero errors**

### Step 2 — Implement in Kotlin

- [ ] Annotate the class with `@TlaSpec("ModuleName")`
- [ ] Annotate each variable matching a TLA+ variable with `@TlaVariable("name")`
- [ ] Implement each action method and annotate with `@TlaAction("Name")`
- [ ] Write `@TlaInvariant("Name") fun checkXxx(): Boolean` for each invariant
- [ ] Add all invariant checks to `checkAllInvariants()`
- [ ] Use `ActorContext` for any user-facing spawn/watch/stop (not `ActorCell` directly)
- [ ] Add backpressure: use bounded channels, never unbounded for message paths
- [ ] Add structured logging at the required lifecycle log points

### Step 3 — Write Tests

- [ ] JUnit5 unit tests: happy path, edge cases, error cases
- [ ] Call `checkAllInvariants()` at the end of each test
- [ ] Concurrency stress test with `@RepeatedTest(5)` and `CyclicBarrier`
- [ ] Lincheck test if the feature involves linearizable operations
- [ ] Verify `get_errors()` shows zero compile errors

### Step 4 — Documentation

- [ ] Add the component to the `Core Components` table in `README.md`
- [ ] Add the spec to the `TLA+ Specifications` section in `README.md`
- [ ] Add the test class to the `Test Suite` table in `README.md`
- [ ] Update the `Design Decisions` section if this introduces a new pattern
- [ ] Add `tlc` invocation to the README's Quick Start section

### Step 5 — Verify Everything

- [ ] `get_errors()` returns zero errors for all source files
- [ ] TLC passes all 5 (or more) specs
- [ ] All existing tests still pass (no regressions)

---

## 11. Forbidden Patterns

These patterns must never appear in the core library. If you see them, flag as a bug.

### Concurrency Anti-Patterns

```kotlin
// FORBIDDEN: unbounded queue in hot path
val mailbox = Channel<M>(UNLIMITED)  // only OK for signalChannel

// FORBIDDEN: shared mutable state in behavior
class MyActor {
    var counter = 0  // actors communicate via messages, not shared fields
}

// FORBIDDEN: actor touching another actor's internals
otherActor.cell.children  // use ActorRef and message passing

// FORBIDDEN: blocking in a coroutine
Thread.sleep(1000)  // use delay(1000.milliseconds)

// FORBIDDEN: unchecked nulls on actor identity
val ref: ActorRef<M>? = null  // ActorRef is always non-null after spawn

// FORBIDDEN: catching CancellationException without rethrowing
try { ... } catch (e: CancellationException) { /* ignore */ }
// CORRECT:
try { ... } catch (e: CancellationException) { throw e }
```

### State Machine Anti-Patterns

```kotlin
// FORBIDDEN: behavior with external mutable state
val count = AtomicInteger(0)
val b = behavior<Msg> { msg -> count.incrementAndGet(); Behavior.same() }

// CORRECT: state encoded as closure parameter
fun counter(count: Int): Behavior<Msg> = receive { _, _ -> counter(count + 1) }

// FORBIDDEN: switching behavior from outside the actor
ref.actorCell.currentBehavior = newBehavior  // private for a reason
```

### TLA+ Anti-Patterns

```tla
(* FORBIDDEN: unbounded CHOOSE as sentinel *)
None == CHOOSE x : x \notin Actors  (* use a CONSTANT model value instead *)

(* FORBIDDEN: spec with no terminal stutter — causes TLC deadlock *)
Next == \E a \in Actors : SomeAction(a)
(* CORRECT: add terminal stutter *)
Next == (\E a \in Actors : SomeAction(a)) \/ (terminalCondition /\ UNCHANGED vars)

(* FORBIDDEN: invariant with no Kotlin counterpart *)
(* Every TLA+ invariant must have a @TlaInvariant-annotated check method *)

(* FORBIDDEN: action with no Kotlin mapping comment *)
(* Every action must document: \* Maps to: ClassName.methodName() *)
```

### API Anti-Patterns

```kotlin
// FORBIDDEN: leaking ActorCell to users
fun spawn(): ActorCell<M>  // must return ActorRef<M>

// FORBIDDEN: breaking DSL backward compatibility
// These signatures must never change:
fun <M : Any> behavior(handler: suspend (M) -> Behavior<M>): Behavior<M>
fun <M : Any> statelessBehavior(handler: suspend (M) -> Unit): Behavior<M>
fun <M : Any> receive(handler: suspend (ActorContext<M>, M) -> Behavior<M>): Behavior<M>
fun <M : Any> setup(factory: (ActorContext<M>) -> Behavior<M>): Behavior<M>

// FORBIDDEN: application-level code in core library
// things like: KafkaConsumerActor, HttpHandlerActor, DatabaseActor
// these belong in examples/, not in src/main/kotlin/com/actors/
```

---

## 12. How to Run Verification

### Run TLC Model Checker (All Specs)

```bash
cd /path/to/actors
alias tlc="java -cp src/main/tla/tla2tools.jar tlc2.TLC"

# Run all 5 specs — all must say "No error has been found"
for spec in ActorMailbox ActorLifecycle RequestReply ActorHierarchy DeathWatch; do
    echo "=== Checking $spec ===" 
    tlc src/main/tla/${spec}.tla -config src/main/tla/${spec}.cfg -workers auto 2>&1 \
        | grep -E "No error|Error:|states generated|Finished"
    echo ""
done
```

Expected output per spec:
```
Model checking completed. No error has been found.
NNN states generated, MMM distinct states found, 0 states left on queue.
Finished in XXs
```

### Run Tests (via IDE)

The Gradle composite build (`../tla2lincheck`) has known pre-existing compile errors
in `SanyParser.kt` that are unrelated to this project. Therefore use the IDE's Kotlin
compiler rather than `./gradlew test` to check for errors.

Use the `get_errors` tool after any file change to verify zero compile errors.
Use the IDE's test runner or run individual test classes.

### Verify TLA+ Annotations Coverage

After any code change, verify that the annotation coverage is maintained:

```bash
# Count @TlaSpec annotations vs classes in src/main/kotlin/com/actors/
grep -r "@TlaSpec\|@TlaAction\|@TlaVariable\|@TlaInvariant" \
     src/main/kotlin/com/actors/ | wc -l
```

The count should never decrease unless a class or method is legitimately removed.

---

## 13. Open Roadmap Areas

These are explicitly planned but not yet implemented. When working on these,
follow the full feature checklist from §10.

### Tier 1: Core Library Extensions (Do These Next)

| Feature | TLA+ Spec Needed | Kotlin Design |
|---------|-----------------|--------------|
| **Stash** — defer messages during initialization | `ActorStash.tla` | Buffer inside `SetupBehavior`, drain on transition to `Running` |
| **TimerScheduler** — schedule messages after delay | `ActorTimer.tla` | Separate coroutine per timer, delivers to actor's signalChannel |
| **Dead Letter Office** — capture messages to dead actors | `DeadLetter.tla` | ActorSystem-level Channel<DeadLetter>, subscribers |
| **Backpressure Metrics** — per-actor mailbox gauges | Extend `ActorMailbox.tla` | Micrometer integration, push to signalChannel-like side channel |

### Tier 2: Observability (High Priority for Debuggability Goals)

| Feature | Description |
|---------|-------------|
| **Message trace buffer** | Ring buffer of last N messages per actor, exposed via `ActorContext` |
| **Actor tree dump** | `ActorSystem.dumpTree(): String` returns supervision tree as ASCII/JSON |
| **Slow actor detection** | Log + metric when `onMessage` exceeds configurable threshold |
| **Signal history** | Ordered list of signals per actor lifetime, useful for post-mortem |

### Tier 3: Distribution (Long-Term Research)

| Feature | TLA+ Spec Needed | Kotlin Design |
|---------|-----------------|--------------|
| **Remote ActorRef** | `RemoteRef.tla` — network delivery, at-most-once/at-least-once | Netty transport, ActorRef with node address |
| **Receptionist / Registry** | `Receptionist.tla` — service discovery | ConcurrentHashMap at cluster level, gossip protocol |
| **Cluster supervision** | `ClusterSupervision.tla` — cross-node supervision | Network partition models, Phi-accrual failure detector |
| **Network partition model** | `NetworkPartition.tla` | TLA+ spec for split-brain scenarios |

Each Tier 3 item requires a TLA+ spec that models the distributed/network failure modes.
This is the core academic contribution: proving that actor-model supervision works correctly
under network partitions and message reordering.

### Tier 4: Academic Output

| Output | Content |
|--------|---------|
| **Paper 1** | TLA+ ↔ Kotlin traceability methodology — the annotation system as a research contribution |
| **Paper 2** | Lincheck as a TLA+-to-runtime bridge — tla2lincheck tool evaluation |
| **Paper 3** | Backpressure in actor systems — formal analysis and comparison vs Erlang/Akka |

---

## 14. Academic Writing Standards

### KDoc Must Be Paper-Quality

Every public class and function's KDoc must be precise enough to cite in a paper.
This means:
- Use the precise terminology: "bounded FIFO channel", "supervision tree", "linearizability"
- Include the TLA+ connection explicitly: "Corresponds to TLA+ action `SpawnChild(p, c)` in `ActorHierarchy.tla`"
- Include an analogy to Erlang/Akka where relevant (helps reviewers who know those systems)
- Avoid implementation details in public-facing KDoc (not "uses ConcurrentHashMap", but "thread-safe and lock-free")

### Comparison Language

When comparing to Erlang/OTP and Akka Typed, be precise:

| Do Say | Don't Say |
|--------|----------|
| "Unlike Akka Typed, signals are delivered _before_ the supervisor strategy fires" | "this is better than Akka" |
| "Erlang monitors deliver `{'DOWN', Ref, process, Pid, Reason}` to the monitoring process; this library delivers `Signal.Terminated(ref)` to the actor's own behavior loop" | "inspired by Erlang" |
| "The `select {}` loop provides a formal priority ordering between signals and messages, verified by the `PreStartBeforeProcess` invariant" | "signals have priority" |

### Invariant Naming Must Follow the Paper Pattern

TLA+ invariant names must be descriptive enough to appear as theorem names:
- `ChildParentConsistency` ✓
- `NoPhantomTerminated` ✓
- `Inv1` ✗ — never use numeric names
- `CheckBounds` ✗ — too vague

Each `@TlaInvariant` annotation and its corresponding Kotlin method name must match the TLA+ spec exactly.
The annotation value is read by tooling (`tla2lincheck`) to generate the invariant name in the paper.

### Spec Comments Are Abstract, Code Comments Are Concrete

In `.tla` files: describe the abstract behavior ("An alive actor watches another: registers bidirectionally")
In `.kt` files: describe the implementation ("Adds this cell to `other.watchers` and `other` to `this.watching`")
Never mix the levels.

---

*Last updated: 2026-02-24*
*Maintained alongside the PhD research project. Update this file whenever a principle changes.*
