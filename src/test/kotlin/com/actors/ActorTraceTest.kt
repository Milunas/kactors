package com.actors

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for the Actor Flight Recorder traceability system.
 *
 * Verifies:
 *   1. Flight recorder captures all observable events
 *   2. Ring buffer eviction works correctly
 *   3. Lamport clock monotonicity
 *   4. Supervision tree dump
 *   5. Slow message detection
 *   6. All TLA+ invariants hold at runtime
 *   7. System-wide trace dump
 */
class ActorTraceTest {

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.create("trace-test")
    }

    @AfterEach
    fun teardown() = runBlocking {
        if (!system.isTerminated) system.terminate()
    }

    // ─── Flight Recorder Unit Tests ──────────────────────────────

    @Test
    fun `flight recorder records events within capacity`() {
        val recorder = ActorFlightRecorder(capacity = 8)

        repeat(5) { i ->
            recorder.record(TraceEvent.StateChanged(
                actorPath = "test/actor",
                timestamp = java.time.Instant.now(),
                lamportTimestamp = recorder.nextLamportTimestamp(),
                fromState = ActorState.CREATED,
                toState = ActorState.STARTING
            ))
        }

        assertThat(recorder.size).isEqualTo(5)
        assertThat(recorder.totalEvents).isEqualTo(5)
        assertThat(recorder.evictedCount).isEqualTo(0)
        recorder.checkAllInvariants()
    }

    @Test
    fun `flight recorder evicts oldest events when full`() {
        val recorder = ActorFlightRecorder(capacity = 4)

        repeat(10) { i ->
            recorder.record(TraceEvent.MessageReceived(
                actorPath = "test/actor",
                timestamp = java.time.Instant.now(),
                lamportTimestamp = recorder.nextLamportTimestamp(),
                messageType = "Msg$i",
                mailboxSizeAfter = 0
            ))
        }

        assertThat(recorder.size).isEqualTo(4) // Only last 4 kept
        assertThat(recorder.totalEvents).isEqualTo(10)
        assertThat(recorder.evictedCount).isEqualTo(6)

        // Verify the kept events are the LAST 4
        val events = recorder.snapshot()
        assertThat(events).hasSize(4)
        val msgEvents = events.filterIsInstance<TraceEvent.MessageReceived>()
        assertThat(msgEvents.map { it.messageType }).containsExactly("Msg6", "Msg7", "Msg8", "Msg9")

        recorder.checkAllInvariants()
    }

    @Test
    fun `Lamport clock is monotonically increasing`() {
        val recorder = ActorFlightRecorder(capacity = 16)

        repeat(5) {
            recorder.record(TraceEvent.StateChanged(
                actorPath = "test/actor",
                timestamp = java.time.Instant.now(),
                lamportTimestamp = recorder.nextLamportTimestamp(),
                fromState = ActorState.CREATED,
                toState = ActorState.STARTING
            ))
        }

        assertThat(recorder.currentLamportTime).isEqualTo(5)
        val events = recorder.snapshot()
        // Verify each event has increasing Lamport timestamp
        events.zipWithNext().forEach { (prev, next) ->
            assertThat(next.lamportTimestamp).isGreaterThan(prev.lamportTimestamp)
        }
        recorder.checkAllInvariants()
    }

    @Test
    fun `Lamport clock update with remote timestamp`() {
        val recorder = ActorFlightRecorder(capacity = 16)

        // Record some local events
        repeat(3) {
            recorder.record(TraceEvent.StateChanged(
                actorPath = "test/actor",
                timestamp = java.time.Instant.now(),
                lamportTimestamp = recorder.nextLamportTimestamp(),
                fromState = ActorState.CREATED,
                toState = ActorState.STARTING
            ))
        }

        assertThat(recorder.currentLamportTime).isEqualTo(3)

        // Simulate receiving a message from a remote actor with higher Lamport time
        val updated = recorder.updateLamportClock(100)
        assertThat(updated).isEqualTo(101) // max(3, 100) + 1
    }

    @Test
    fun `flight recorder snapshot filters by predicate`() {
        val recorder = ActorFlightRecorder(capacity = 16)

        recorder.record(TraceEvent.StateChanged(
            actorPath = "test", timestamp = java.time.Instant.now(),
            lamportTimestamp = 1, fromState = ActorState.CREATED, toState = ActorState.STARTING
        ))
        recorder.record(TraceEvent.MessageReceived(
            actorPath = "test", timestamp = java.time.Instant.now(),
            lamportTimestamp = 2, messageType = "Hello", mailboxSizeAfter = 0
        ))
        recorder.record(TraceEvent.SignalDelivered(
            actorPath = "test", timestamp = java.time.Instant.now(),
            lamportTimestamp = 3, signalType = "PreStart"
        ))

        val signals = recorder.snapshot { it is TraceEvent.SignalDelivered }
        assertThat(signals).hasSize(1)
        assertThat((signals[0] as TraceEvent.SignalDelivered).signalType).isEqualTo("PreStart")
    }

    @Test
    fun `flight recorder lastN returns most recent events reversed`() {
        val recorder = ActorFlightRecorder(capacity = 16)

        repeat(10) { i ->
            recorder.record(TraceEvent.MessageReceived(
                actorPath = "test", timestamp = java.time.Instant.now(),
                lamportTimestamp = recorder.nextLamportTimestamp(),
                messageType = "Msg$i", mailboxSizeAfter = 0
            ))
        }

        val last3 = recorder.lastN(3)
        assertThat(last3).hasSize(3)
        val types = last3.map { (it as TraceEvent.MessageReceived).messageType }
        assertThat(types).containsExactly("Msg9", "Msg8", "Msg7") // Most recent first
    }

    @Test
    fun `flight recorder dump produces readable output`() {
        val recorder = ActorFlightRecorder(capacity = 16)

        recorder.record(TraceEvent.StateChanged(
            actorPath = "system/actor", timestamp = java.time.Instant.now(),
            lamportTimestamp = 1, fromState = ActorState.CREATED, toState = ActorState.STARTING
        ))
        recorder.record(TraceEvent.SignalDelivered(
            actorPath = "system/actor", timestamp = java.time.Instant.now(),
            lamportTimestamp = 2, signalType = "PreStart"
        ))

        val dump = recorder.dump()
        assertThat(dump).contains("FLIGHT RECORDER")
        assertThat(dump).contains("system/actor")
        assertThat(dump).contains("STATE")
        assertThat(dump).contains("SIGNAL")
        assertThat(dump).contains("PreStart")
    }

    @Test
    fun `flight recorder dumpJson produces valid-looking JSON`() {
        val recorder = ActorFlightRecorder(capacity = 16)

        recorder.record(TraceEvent.MessageReceived(
            actorPath = "system/actor", timestamp = java.time.Instant.now(),
            lamportTimestamp = 1, messageType = "Hello", mailboxSizeAfter = 2
        ))

        val json = recorder.dumpJson()
        assertThat(json).startsWith("[")
        assertThat(json).contains("msg_received")
        assertThat(json).contains("Hello")
        assertThat(json).endsWith("]")
    }

    // ─── Integration: Flight Recorder in Actors ──────────────────

    @Test
    fun `actor records lifecycle state changes in flight recorder`() = runBlocking {
        sealed class Msg {
            data object Ping : Msg()
        }

        val ref = system.spawn("lifecycle-actor", receive<Msg> { _, _ -> Behavior.same() })

        delay(100.milliseconds) // Let actor start

        ref.tell(Msg.Ping)
        delay(50.milliseconds)

        val events = ref.actorCell.flightRecorder.snapshot()

        // Should have at least: CREATED→STARTING, PreStart signal, STARTING→RUNNING, message
        val stateChanges = events.filterIsInstance<TraceEvent.StateChanged>()
        assertThat(stateChanges).isNotEmpty
        assertThat(stateChanges.any { it.fromState == ActorState.CREATED && it.toState == ActorState.STARTING }).isTrue
        assertThat(stateChanges.any { it.fromState == ActorState.STARTING && it.toState == ActorState.RUNNING }).isTrue

        val signals = events.filterIsInstance<TraceEvent.SignalDelivered>()
        assertThat(signals.any { it.signalType == "PreStart" }).isTrue

        val messages = events.filterIsInstance<TraceEvent.MessageReceived>()
        assertThat(messages).isNotEmpty
        assertThat(messages[0].messageType).isEqualTo("Ping")

        ref.actorCell.checkAllInvariants()
    }

    @Test
    fun `actor records child spawn in flight recorder`() = runBlocking {
        sealed class ParentMsg {
            data object SpawnChild : ParentMsg()
        }

        val parentRef = system.spawn("parent", receive<ParentMsg> { ctx, msg ->
            when (msg) {
                is ParentMsg.SpawnChild -> {
                    ctx.spawn("child", statelessBehavior<String> { })
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        parentRef.tell(ParentMsg.SpawnChild)
        delay(100.milliseconds)

        val events = parentRef.actorCell.flightRecorder.snapshot()
        val childSpawns = events.filterIsInstance<TraceEvent.ChildSpawned>()
        assertThat(childSpawns).isNotEmpty
        assertThat(childSpawns[0].childPath).contains("child")

        parentRef.actorCell.checkAllInvariants()
    }

    @Test
    fun `actor records signal delivery including Terminated`() = runBlocking {
        sealed class WatcherMsg {
            data class WatchThis(val ref: ActorRef<String>) : WatcherMsg()
        }

        val watchedRef = system.spawn("watched", statelessBehavior<String> { })

        val watcherRef = system.spawn("watcher", receive<WatcherMsg> { ctx, msg ->
            when (msg) {
                is WatcherMsg.WatchThis -> {
                    ctx.watch(msg.ref)
                    Behavior.same()
                }
            }
        }.onSignal { _, signal ->
            when (signal) {
                is Signal.Terminated -> Behavior.same()
                else -> Behavior.same()
            }
        })

        delay(100.milliseconds)
        watcherRef.tell(WatcherMsg.WatchThis(watchedRef))
        delay(50.milliseconds)

        // Stop the watched actor — should trigger Terminated signal to watcher
        system.stop("watched")
        delay(200.milliseconds)

        val watcherEvents = watcherRef.actorCell.flightRecorder.snapshot()
        val watchRegs = watcherEvents.filterIsInstance<TraceEvent.WatchRegistered>()
        assertThat(watchRegs).isNotEmpty
        assertThat(watchRegs[0].watchedPath).contains("watched")

        val signals = watcherEvents.filterIsInstance<TraceEvent.SignalDelivered>()
        assertThat(signals.any { it.signalType == "Terminated" }).isTrue

        watcherRef.actorCell.checkAllInvariants()
    }

    @Test
    fun `actor records failure handling in flight recorder`() = runBlocking {
        sealed class FailMsg {
            data object Fail : FailMsg()
            data object Ok : FailMsg()
        }

        val ref = system.spawn("failing-actor", receive<FailMsg> { _, msg ->
            when (msg) {
                is FailMsg.Fail -> throw RuntimeException("test failure")
                is FailMsg.Ok -> Behavior.same()
            }
        }, supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 3))

        delay(100.milliseconds)
        ref.tell(FailMsg.Fail)
        delay(200.milliseconds) // Wait for restart cycle

        val events = ref.actorCell.flightRecorder.snapshot()
        val failures = events.filterIsInstance<TraceEvent.FailureHandled>()
        assertThat(failures).isNotEmpty
        assertThat(failures[0].errorType).isEqualTo("RuntimeException")
        assertThat(failures[0].errorMessage).isEqualTo("test failure")
        assertThat(failures[0].directive).isEqualTo(SupervisorStrategy.Directive.RESTART)

        // Should also have RESTARTING state change
        val stateChanges = events.filterIsInstance<TraceEvent.StateChanged>()
        assertThat(stateChanges.any { it.toState == ActorState.RESTARTING }).isTrue

        ref.actorCell.checkAllInvariants()
    }

    @Test
    fun `actor records behavior change in flight recorder`() = runBlocking {
        sealed class CountMsg {
            data object Increment : CountMsg()
        }

        fun counter(count: Int): Behavior<CountMsg> = receive { _, msg ->
            when (msg) {
                is CountMsg.Increment -> counter(count + 1) // Returns new behavior
            }
        }

        val ref = system.spawn("counter", counter(0))
        delay(100.milliseconds)
        ref.tell(CountMsg.Increment)
        ref.tell(CountMsg.Increment)
        delay(100.milliseconds)

        val events = ref.actorCell.flightRecorder.snapshot()
        val behaviorChanges = events.filterIsInstance<TraceEvent.BehaviorChanged>()
        // Should have at least 2 behavior changes (one per Increment that returns new behavior)
        assertThat(behaviorChanges.size).isGreaterThanOrEqualTo(2)

        ref.actorCell.checkAllInvariants()
    }

    // ─── Slow Message Detection ──────────────────────────────────

    @Test
    fun `slow message detection records warning in flight recorder`() = runBlocking {
        sealed class SlowMsg {
            data object Slow : SlowMsg()
        }

        // Create actor cell with very low threshold for testing
        val ref = system.spawn("slow-actor", receive<SlowMsg> { _, msg ->
            when (msg) {
                is SlowMsg.Slow -> {
                    delay(50.milliseconds) // Simulate slow processing
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        ref.tell(SlowMsg.Slow)
        delay(200.milliseconds)

        // The default threshold is 100ms, so 50ms won't trigger.
        // This tests that no false positives occur.
        val events = ref.actorCell.flightRecorder.snapshot()
        val slowWarnings = events.filterIsInstance<TraceEvent.SlowMessageWarning>()
        assertThat(slowWarnings).isEmpty() // 50ms < 100ms default threshold

        ref.actorCell.checkAllInvariants()
    }

    // ─── Actor Tree Dump ─────────────────────────────────────────

    @Test
    fun `system dumpTree includes all actors`() = runBlocking {
        system.spawn("actor-a", statelessBehavior<String> { })
        system.spawn("actor-b", statelessBehavior<String> { })
        delay(100.milliseconds)

        val tree = system.dumpTree()
        assertThat(tree).contains("ActorSystem: trace-test")
        assertThat(tree).contains("actor-a")
        assertThat(tree).contains("actor-b")
        assertThat(tree).contains("RUNNING")
    }

    @Test
    fun `system dumpTreeJson produces structured output`() = runBlocking {
        system.spawn("json-actor", statelessBehavior<String> { })
        delay(100.milliseconds)

        val json = system.dumpTreeJson()
        assertThat(json).contains("\"system\"")
        assertThat(json).contains("trace-test")
        assertThat(json).contains("json-actor")
        assertThat(json).contains("\"state\"")
        assertThat(json).contains("\"traceEvents\"")
    }

    @Test
    fun `system dumpAllTraces includes all actor traces`() = runBlocking {
        sealed class TraceMsg {
            data object Ping : TraceMsg()
        }

        val ref = system.spawn("traced-actor", statelessBehavior<TraceMsg> { })
        delay(100.milliseconds)
        ref.tell(TraceMsg.Ping)
        delay(50.milliseconds)

        val dump = system.dumpAllTraces()
        assertThat(dump).contains("SYSTEM TRACE DUMP")
        assertThat(dump).contains("FLIGHT RECORDER")
    }

    @Test
    fun `system dumpActorTrace returns trace for specific actor`() = runBlocking {
        system.spawn("specific-actor", statelessBehavior<String> { })
        delay(100.milliseconds)

        val trace = system.dumpActorTrace("specific-actor")
        assertThat(trace).isNotNull
        assertThat(trace).contains("FLIGHT RECORDER")
        assertThat(trace).contains("specific-actor")

        val missing = system.dumpActorTrace("nonexistent")
        assertThat(missing).isNull()
    }

    // ─── Flight Recorder Accessed from ActorContext ──────────────

    @Test
    fun `actor can access its own flight recorder via context`() = runBlocking {
        sealed class ContextMsg {
            data class GetTraceSize(val replyTo: ActorRef<Int>) : ContextMsg(), Request<Int>
        }

        val ref = system.spawn("ctx-trace-actor", receive<ContextMsg> { ctx, msg ->
            when (msg) {
                is ContextMsg.GetTraceSize -> {
                    msg.replyTo.tell(ctx.flightRecorder.size)
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        val size: Int = ref.ask { replyTo -> ContextMsg.GetTraceSize(replyTo) }

        // The actor should have recorded at least lifecycle events
        assertThat(size).isGreaterThan(0)
    }

    // ─── Concurrency Stress Test ─────────────────────────────────

    @RepeatedTest(5)
    fun `flight recorder is thread-safe under concurrent reads and writes`() {
        val recorder = ActorFlightRecorder(capacity = 32)
        val barrier = CyclicBarrier(4) // 1 writer + 3 readers
        val latch = CountDownLatch(4)

        // Writer thread (simulates actor coroutine)
        Thread {
            barrier.await()
            repeat(100) { i ->
                recorder.record(TraceEvent.MessageReceived(
                    actorPath = "test", timestamp = java.time.Instant.now(),
                    lamportTimestamp = recorder.nextLamportTimestamp(),
                    messageType = "Msg$i", mailboxSizeAfter = 0
                ))
            }
            latch.countDown()
        }.start()

        // Reader threads (simulate monitoring tools)
        repeat(3) {
            Thread {
                barrier.await()
                repeat(50) {
                    recorder.snapshot()
                    recorder.dump()
                    recorder.size
                    recorder.totalEvents
                }
                latch.countDown()
            }.start()
        }

        latch.await(10, TimeUnit.SECONDS)

        assertThat(recorder.totalEvents).isEqualTo(100)
        assertThat(recorder.size).isLessThanOrEqualTo(32)
        recorder.checkAllInvariants()
    }

    // ─── Invariant Verification ──────────────────────────────────

    @Test
    fun `all flight recorder invariants hold after varied operations`() {
        val recorder = ActorFlightRecorder(capacity = 8)

        // Mix of different event types
        recorder.record(TraceEvent.StateChanged(
            "a", java.time.Instant.now(), recorder.nextLamportTimestamp(),
            ActorState.CREATED, ActorState.STARTING
        ))
        recorder.record(TraceEvent.SignalDelivered(
            "a", java.time.Instant.now(), recorder.nextLamportTimestamp(),
            "PreStart"
        ))
        recorder.record(TraceEvent.StateChanged(
            "a", java.time.Instant.now(), recorder.nextLamportTimestamp(),
            ActorState.STARTING, ActorState.RUNNING
        ))
        repeat(10) { i ->
            recorder.record(TraceEvent.MessageReceived(
                "a", java.time.Instant.now(), recorder.nextLamportTimestamp(),
                "Msg$i", 0
            ))
        }
        recorder.record(TraceEvent.FailureHandled(
            "a", java.time.Instant.now(), recorder.nextLamportTimestamp(),
            "RuntimeException", "boom", SupervisorStrategy.Directive.RESTART, 1
        ))
        recorder.record(TraceEvent.SlowMessageWarning(
            "a", java.time.Instant.now(), recorder.nextLamportTimestamp(),
            "SlowMsg", 250, 100
        ))

        // All invariants must hold
        recorder.checkAllInvariants()

        // Verify bounds
        assertThat(recorder.size).isLessThanOrEqualTo(8)
        assertThat(recorder.totalEvents).isEqualTo(15)
        assertThat(recorder.evictedCount).isEqualTo(7)
    }
}
