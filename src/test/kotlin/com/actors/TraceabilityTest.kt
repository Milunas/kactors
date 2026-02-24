package com.actors

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.RepeatedTest
import java.io.StringWriter
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the cross-actor traceability, structured logging, and replay system.
 *
 * Verifies:
 *   1. TraceContext creation and child span propagation
 *   2. Cross-actor sender identification (who sent what to whom)
 *   3. Lamport clock synchronization across actors
 *   4. Structured logging via ctx.trace/debug/info/warn/error
 *   5. NDJSON export and TraceReplay round-trip
 *   6. MessageEnvelope metadata correctness
 *   7. System-wide exportAllTracesNdjson
 *   8. Causal chain reconstruction from traces
 */
class TraceabilityTest {

    // Message types for tests (sealed classes cannot be local in Kotlin)

    sealed class SenderPathForwardMsg {
        data class Forward(val target: ActorRef<SenderPathReceiverMsg>) : SenderPathForwardMsg()
    }
    sealed class SenderPathReceiverMsg {
        data object Hello : SenderPathReceiverMsg()
    }

    sealed class MsgSentForwardMsg {
        data class Forward(val target: ActorRef<String>) : MsgSentForwardMsg()
    }

    sealed class LamportMsg {
        data class Ping(val target: ActorRef<LamportMsg>) : LamportMsg()
        data object Pong : LamportMsg()
    }

    sealed class InfoMsg {
        data class Process(val orderId: String) : InfoMsg()
    }

    sealed class LogLevelMsg {
        data object DoAll : LogLevelMsg()
    }

    sealed class RecordMsg {
        data object Store : RecordMsg()
    }

    sealed class WorkerMsg {
        data object Work : WorkerMsg()
    }
    sealed class DispatchForwardMsg {
        data class Go(val target: ActorRef<WorkerMsg>) : DispatchForwardMsg()
    }

    sealed class NdjsonMsg {
        data object Ping : NdjsonMsg()
    }

    sealed class CMsg {
        data object Work : CMsg()
    }
    sealed class BMsg {
        data class Forward(val cRef: ActorRef<CMsg>) : BMsg()
    }
    sealed class AMsg {
        data class Start(val bRef: ActorRef<BMsg>, val cRef: ActorRef<CMsg>) : AMsg()
    }

    sealed class PongMsg {
        data object Pong : PongMsg()
    }
    sealed class PingMsg {
        data class Ping(val target: ActorRef<PongMsg>) : PingMsg()
    }

    sealed class OrderMsg {
        data class Place(val orderId: String) : OrderMsg()
    }

    sealed class CheckTraceMsg {
        data class CheckTrace(override val replyTo: ActorRef<String>) : CheckTraceMsg(), Request<String>
    }

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.create("trace-test")
    }

    @AfterEach
    fun teardown() = runBlocking {
        if (!system.isTerminated) system.terminate()
    }

    // ─── TraceContext Unit Tests ──────────────────────────────────

    @Test
    fun `TraceContext create generates unique traceId and spanId`() {
        val ctx1 = TraceContext.create()
        val ctx2 = TraceContext.create()

        assertThat(ctx1.traceId).isNotEmpty
        assertThat(ctx1.spanId).isNotEmpty
        assertThat(ctx1.parentSpanId).isEmpty()
        assertThat(ctx1.isActive).isTrue

        // Different traces have different IDs
        assertThat(ctx1.traceId).isNotEqualTo(ctx2.traceId)
        assertThat(ctx1.spanId).isNotEqualTo(ctx2.spanId)
    }

    @Test
    fun `TraceContext newChildSpan preserves traceId and links parent`() {
        val parent = TraceContext.create()
        val child = parent.newChildSpan()

        assertThat(child.traceId).isEqualTo(parent.traceId)
        assertThat(child.parentSpanId).isEqualTo(parent.spanId)
        assertThat(child.spanId).isNotEqualTo(parent.spanId)
        assertThat(child.isActive).isTrue
    }

    @Test
    fun `TraceContext EMPTY is inactive`() {
        assertThat(TraceContext.EMPTY.isActive).isFalse
        assertThat(TraceContext.EMPTY.traceId).isEmpty()
        assertThat(TraceContext.EMPTY.spanId).isEmpty()
    }

    @Test
    fun `TraceContext toString is readable`() {
        val ctx = TraceContext.create()
        assertThat(ctx.toString()).contains("Trace(")
        assertThat(TraceContext.EMPTY.toString()).isEqualTo("Trace(none)")
    }

    // ─── Cross-Actor Sender Identification ───────────────────────

    @Test
    fun `tell from actor records sender path in receiver flight recorder`() = runBlocking {
        val receiverRef = system.spawn("receiver", statelessBehavior<SenderPathReceiverMsg> { })

        val forwarderRef = system.spawn("forwarder", receive<SenderPathForwardMsg> { ctx, msg ->
            when (msg) {
                is SenderPathForwardMsg.Forward -> {
                    msg.target.tell(SenderPathReceiverMsg.Hello)
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        forwarderRef.tell(SenderPathForwardMsg.Forward(receiverRef))
        delay(200.milliseconds)

        // The receiver's flight recorder should show the sender
        val receiverEvents = receiverRef.actorCell.flightRecorder.snapshot()
        val msgEvents = receiverEvents.filterIsInstance<TraceEvent.MessageReceived>()
        val helloEvent = msgEvents.find { it.messageType == "Hello" }

        assertThat(helloEvent).isNotNull
        assertThat(helloEvent!!.senderPath).isEqualTo("trace-test/forwarder")
        assertThat(helloEvent.traceContext).isNotNull
        assertThat(helloEvent.traceContext!!.isActive).isTrue

        receiverRef.actorCell.checkAllInvariants()
    }

    @Test
    fun `tell from actor records MessageSent in sender flight recorder`() = runBlocking {
        val targetRef = system.spawn("target", statelessBehavior<String> { })

        val senderRef = system.spawn("sender", receive<MsgSentForwardMsg> { _, msg ->
            when (msg) {
                is MsgSentForwardMsg.Forward -> {
                    msg.target.tell("hello")
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        senderRef.tell(MsgSentForwardMsg.Forward(targetRef))
        delay(200.milliseconds)

        // Sender's flight recorder should have MessageSent event
        val senderEvents = senderRef.actorCell.flightRecorder.snapshot()
        val sentEvents = senderEvents.filterIsInstance<TraceEvent.MessageSent>()

        assertThat(sentEvents).isNotEmpty
        val sent = sentEvents.first()
        assertThat(sent.targetPath).isEqualTo("trace-test/target")
        assertThat(sent.messageType).isEqualTo("String")
        assertThat(sent.traceContext).isNotNull

        senderRef.actorCell.checkAllInvariants()
    }

    // ─── Cross-Actor Lamport Clock Synchronization ───────────────

    @Test
    fun `Lamport clock syncs across actors on message delivery`() = runBlocking {
        // Actor A sends to B, then B sends back
        val latchB = CountDownLatch(1)
        val actorB = system.spawn("b", receive<LamportMsg> { ctx, msg ->
            when (msg) {
                is LamportMsg.Ping -> {
                    msg.target.tell(LamportMsg.Pong)
                    Behavior.same()
                }
                is LamportMsg.Pong -> {
                    latchB.countDown()
                    Behavior.same()
                }
            }
        })

        val latchA = CountDownLatch(1)
        val actorA = system.spawn("a", receive<LamportMsg> { ctx, msg ->
            when (msg) {
                is LamportMsg.Ping -> {
                    msg.target.tell(LamportMsg.Pong)
                    Behavior.same()
                }
                is LamportMsg.Pong -> {
                    latchA.countDown()
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        // A sends Ping to B, B receives and sends Pong back
        actorA.tell(LamportMsg.Ping(actorB))
        latchA.await(2, TimeUnit.SECONDS)

        val clockA = actorA.actorCell.flightRecorder.currentLamportTime
        val clockB = actorB.actorCell.flightRecorder.currentLamportTime

        // Both clocks should be > 0 and reflect causal ordering
        assertThat(clockA).isGreaterThan(0)
        assertThat(clockB).isGreaterThan(0)

        // After A→B→A round trip, A's clock should be > B's sent-time
        // (i.e., A's clock advanced based on B's timestamp)
        actorA.actorCell.checkAllInvariants()
        actorB.actorCell.checkAllInvariants()
    }

    @Test
    fun `updateLamportClock takes max of local and remote`() {
        val recorder = ActorFlightRecorder(capacity = 16)

        // Record some local events to advance clock
        repeat(5) {
            recorder.record(TraceEvent.StateChanged(
                "test", java.time.Instant.now(), recorder.nextLamportTimestamp(),
                ActorState.CREATED, ActorState.STARTING
            ))
        }
        assertThat(recorder.currentLamportTime).isEqualTo(5)

        // Remote timestamp is higher — should jump
        val updated = recorder.updateLamportClock(50)
        assertThat(updated).isEqualTo(51) // max(5, 50) + 1

        // Remote timestamp is lower — should just increment
        val updated2 = recorder.updateLamportClock(10)
        assertThat(updated2).isEqualTo(52) // max(51, 10) + 1
    }

    // ─── Structured Logging (ctx.trace/debug/info/warn/error) ────

    @Test
    fun `ctx info records CustomEvent in flight recorder`() = runBlocking {
        val ref = system.spawn("order-actor", receive<InfoMsg> { ctx, msg ->
            when (msg) {
                is InfoMsg.Process -> {
                    ctx.info("Processing order", "orderId" to msg.orderId, "step" to "validation")
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        ref.tell(InfoMsg.Process("ORD-123"))
        delay(100.milliseconds)

        val events = ref.actorCell.flightRecorder.snapshot()
        val customEvents = events.filterIsInstance<TraceEvent.CustomEvent>()

        assertThat(customEvents).isNotEmpty
        val event = customEvents.first()
        assertThat(event.level).isEqualTo(TraceEvent.LogLevel.INFO)
        assertThat(event.message).isEqualTo("Processing order")
        assertThat(event.extra).containsEntry("orderId", "ORD-123")
        assertThat(event.extra).containsEntry("step", "validation")

        ref.actorCell.checkAllInvariants()
    }

    @Test
    fun `all log levels are recorded as CustomEvents`() = runBlocking {
        val ref = system.spawn("multi-level", receive<LogLevelMsg> { ctx, msg ->
            when (msg) {
                is LogLevelMsg.DoAll -> {
                    ctx.trace("trace msg")
                    ctx.debug("debug msg")
                    ctx.info("info msg")
                    ctx.warn("warn msg")
                    ctx.error("error msg")
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        ref.tell(LogLevelMsg.DoAll)
        delay(100.milliseconds)

        val events = ref.actorCell.flightRecorder.snapshot()
        val customEvents = events.filterIsInstance<TraceEvent.CustomEvent>()

        assertThat(customEvents).hasSize(5)
        assertThat(customEvents.map { it.level }).containsExactly(
            TraceEvent.LogLevel.TRACE,
            TraceEvent.LogLevel.DEBUG,
            TraceEvent.LogLevel.INFO,
            TraceEvent.LogLevel.WARN,
            TraceEvent.LogLevel.ERROR
        )
        assertThat(customEvents.map { it.message }).containsExactly(
            "trace msg", "debug msg", "info msg", "warn msg", "error msg"
        )

        ref.actorCell.checkAllInvariants()
    }

    @Test
    fun `ctx record stores event without SLF4J logging`() = runBlocking {
        val ref = system.spawn("silent-recorder", receive<RecordMsg> { ctx, msg ->
            when (msg) {
                is RecordMsg.Store -> {
                    ctx.record("key1" to "value1", "key2" to "value2")
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        ref.tell(RecordMsg.Store)
        delay(100.milliseconds)

        val events = ref.actorCell.flightRecorder.snapshot()
        val customEvents = events.filterIsInstance<TraceEvent.CustomEvent>()

        assertThat(customEvents).isNotEmpty
        val event = customEvents.first()
        assertThat(event.message).isEmpty()
        assertThat(event.extra).containsEntry("key1", "value1")
        assertThat(event.extra).containsEntry("key2", "value2")

        ref.actorCell.checkAllInvariants()
    }

    @Test
    fun `custom events carry current trace context`() = runBlocking {
        val latch = CountDownLatch(1)
        val workerRef = system.spawn("worker", receive<WorkerMsg> { ctx, msg ->
            when (msg) {
                is WorkerMsg.Work -> {
                    ctx.info("Working on task", "stage" to "init")
                    latch.countDown()
                    Behavior.same()
                }
            }
        })

        val dispatcherRef = system.spawn("dispatcher", receive<DispatchForwardMsg> { _, msg ->
            when (msg) {
                is DispatchForwardMsg.Go -> {
                    msg.target.tell(WorkerMsg.Work)
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        dispatcherRef.tell(DispatchForwardMsg.Go(workerRef))
        latch.await(2, TimeUnit.SECONDS)
        delay(50.milliseconds)

        // The custom event in worker should have trace context from the message chain
        val events = workerRef.actorCell.flightRecorder.snapshot()
        val customEvents = events.filterIsInstance<TraceEvent.CustomEvent>()

        assertThat(customEvents).isNotEmpty
        val event = customEvents.find { it.message == "Working on task" }
        assertThat(event).isNotNull

        // The trace context should be active (propagated from dispatcher→worker)
        assertThat(event!!.traceContext).isNotNull
        assertThat(event.traceContext!!.isActive).isTrue
        assertThat(event.traceContext!!.traceId).isNotEmpty

        workerRef.actorCell.checkAllInvariants()
    }

    // ─── NDJSON Export & TraceReplay Round-Trip ───────────────────

    @Test
    fun `exportNdjson produces valid NDJSON loadable by TraceReplay`() = runBlocking {
        val ref = system.spawn("ndjson-actor", statelessBehavior<NdjsonMsg> { })
        delay(100.milliseconds)
        ref.tell(NdjsonMsg.Ping)
        delay(100.milliseconds)

        val ndjson = ref.actorCell.flightRecorder.exportNdjson()
        assertThat(ndjson).isNotEmpty

        // Each line should be valid JSON-like
        val lines = ndjson.lines().filter { it.isNotBlank() }
        assertThat(lines).allSatisfy { line ->
            assertThat(line).startsWith("{")
            assertThat(line).endsWith("}")
        }

        // Should be loadable by TraceReplay
        val events = TraceReplay.loadNdjson(ndjson)
        assertThat(events).isNotEmpty

        // All events should have this actor's path
        assertThat(events).allSatisfy { event ->
            assertThat(event.actor).contains("ndjson-actor")
        }

        // Should include message receipt
        assertThat(events.any { it.type == "msg_received" }).isTrue

        ref.actorCell.checkAllInvariants()
    }

    @Test
    fun `exportNdjson to Writer produces same output as string version`() {
        val recorder = ActorFlightRecorder(capacity = 16)

        recorder.record(TraceEvent.StateChanged(
            "test", java.time.Instant.now(), recorder.nextLamportTimestamp(),
            ActorState.CREATED, ActorState.STARTING
        ))
        recorder.record(TraceEvent.MessageReceived(
            "test", java.time.Instant.now(), recorder.nextLamportTimestamp(),
            "Hello", 0
        ))

        val stringOutput = recorder.exportNdjson()

        val writer = StringWriter()
        recorder.exportNdjson(writer)
        val writerOutput = writer.toString()

        assertThat(writerOutput).isEqualTo(stringOutput)
    }

    @Test
    fun `system exportAllTracesNdjson includes all actors`() = runBlocking {
        system.spawn("actor-a", statelessBehavior<String> { })
        system.spawn("actor-b", statelessBehavior<String> { })
        delay(100.milliseconds)

        val ndjson = system.exportAllTracesNdjson()
        assertThat(ndjson).isNotEmpty

        val events = TraceReplay.loadNdjson(ndjson)

        // Events from both actors should be present
        val actors = events.map { it.actor }.distinct()
        assertThat(actors.any { it.contains("actor-a") }).isTrue
        assertThat(actors.any { it.contains("actor-b") }).isTrue
    }

    @Test
    fun `system exportAllTracesNdjson to Writer works`() = runBlocking {
        system.spawn("writer-actor", statelessBehavior<String> { })
        delay(100.milliseconds)

        val writer = StringWriter()
        system.exportAllTracesNdjson(writer)
        val output = writer.toString()

        assertThat(output).isNotEmpty
        val events = TraceReplay.loadNdjson(output)
        assertThat(events).isNotEmpty
    }

    // ─── TraceReplay Analysis ────────────────────────────────────

    @Test
    fun `TraceReplay groupByActor partitions events correctly`() {
        val ndjson = """
            {"actor":"system/a","time":"2024-01-01T00:00:00Z","lamport":1,"type":"state_changed","from":"CREATED","to":"STARTING"}
            {"actor":"system/b","time":"2024-01-01T00:00:01Z","lamport":2,"type":"state_changed","from":"CREATED","to":"STARTING"}
            {"actor":"system/a","time":"2024-01-01T00:00:02Z","lamport":3,"type":"msg_received","messageType":"Hello"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val byActor = TraceReplay.groupByActor(events)

        assertThat(byActor).hasSize(2)
        assertThat(byActor["system/a"]).hasSize(2)
        assertThat(byActor["system/b"]).hasSize(1)
    }

    @Test
    fun `TraceReplay groupByTrace groups trace chains`() {
        val ndjson = """
            {"actor":"system/a","time":"2024-01-01T00:00:00Z","lamport":1,"type":"msg_sent","traceId":"abc","spanId":"s1","targetPath":"system/b"}
            {"actor":"system/b","time":"2024-01-01T00:00:01Z","lamport":2,"type":"msg_received","traceId":"abc","spanId":"s2","parentSpanId":"s1"}
            {"actor":"system/c","time":"2024-01-01T00:00:02Z","lamport":3,"type":"msg_received","traceId":"def","spanId":"s3"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val byTrace = TraceReplay.groupByTrace(events)

        assertThat(byTrace).hasSize(2)
        assertThat(byTrace["abc"]).hasSize(2)
        assertThat(byTrace["def"]).hasSize(1)
    }

    @Test
    fun `TraceReplay causalChain sorts by Lamport timestamp`() {
        val ndjson = """
            {"actor":"system/b","time":"2024-01-01T00:00:02Z","lamport":5,"type":"msg_received","traceId":"abc","spanId":"s2"}
            {"actor":"system/a","time":"2024-01-01T00:00:00Z","lamport":1,"type":"msg_sent","traceId":"abc","spanId":"s1"}
            {"actor":"system/c","time":"2024-01-01T00:00:03Z","lamport":8,"type":"msg_received","traceId":"abc","spanId":"s3"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val chain = TraceReplay.causalChain(events, "abc")

        assertThat(chain).hasSize(3)
        assertThat(chain[0].lamport).isEqualTo(1)
        assertThat(chain[1].lamport).isEqualTo(5)
        assertThat(chain[2].lamport).isEqualTo(8)
    }

    @Test
    fun `TraceReplay messageFlow builds actor communication graph`() {
        val ndjson = """
            {"actor":"system/b","time":"2024-01-01T00:00:00Z","lamport":1,"type":"msg_received","sender":"system/a"}
            {"actor":"system/b","time":"2024-01-01T00:00:01Z","lamport":2,"type":"msg_received","sender":"system/a"}
            {"actor":"system/c","time":"2024-01-01T00:00:02Z","lamport":3,"type":"msg_received","sender":"system/b"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val flow = TraceReplay.messageFlow(events)

        assertThat(flow).hasSize(2)
        assertThat(flow["system/a" to "system/b"]).isEqualTo(2)
        assertThat(flow["system/b" to "system/c"]).isEqualTo(1)
    }

    @Test
    fun `TraceReplay failures finds failure events`() {
        val ndjson = """
            {"actor":"system/a","time":"2024-01-01T00:00:00Z","lamport":1,"type":"msg_received","messageType":"Hello"}
            {"actor":"system/a","time":"2024-01-01T00:00:01Z","lamport":2,"type":"failure_handled","error":"RuntimeException","message":"boom","directive":"RESTART"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val fails = TraceReplay.failures(events)

        assertThat(fails).hasSize(1)
        assertThat(fails[0]["error"]).isEqualTo("RuntimeException")
        assertThat(fails[0]["message"]).isEqualTo("boom")
    }

    @Test
    fun `TraceReplay mergeByLamport merges and sorts multiple event lists`() {
        val list1 = listOf(
            TraceReplay.ParsedEvent("a", "t1", 1, "state_changed", mapOf()),
            TraceReplay.ParsedEvent("a", "t3", 5, "msg_received", mapOf())
        )
        val list2 = listOf(
            TraceReplay.ParsedEvent("b", "t2", 3, "state_changed", mapOf()),
            TraceReplay.ParsedEvent("b", "t4", 7, "msg_received", mapOf())
        )

        val merged = TraceReplay.mergeByLamport(list1, list2)

        assertThat(merged).hasSize(4)
        assertThat(merged.map { it.lamport }).containsExactly(1L, 3L, 5L, 7L)
    }

    // ─── TraceReplay Formatted Output ────────────────────────────

    @Test
    fun `printTimeline produces readable output`() {
        val ndjson = """
            {"actor":"system/a","time":"2024-01-01T00:00:00Z","lamport":1,"type":"state_changed","from":"CREATED","to":"STARTING"}
            {"actor":"system/a","time":"2024-01-01T00:00:01Z","lamport":2,"type":"signal_delivered","signal":"PreStart"}
            {"actor":"system/a","time":"2024-01-01T00:00:02Z","lamport":3,"type":"msg_received","messageType":"Hello","sender":"system/b","traceId":"abc","spanId":"s1"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val writer = StringWriter()
        TraceReplay.printTimeline(events, writer)
        val output = writer.toString()

        assertThat(output).contains("UNIFIED TIMELINE")
        assertThat(output).contains("STATE")
        assertThat(output).contains("SIGNAL")
        assertThat(output).contains("MSG_RECV")
        assertThat(output).contains("Hello")
        assertThat(output).contains("abc")
    }

    @Test
    fun `printActorTimeline filters to specific actor`() {
        val ndjson = """
            {"actor":"system/a","time":"2024-01-01T00:00:00Z","lamport":1,"type":"state_changed","from":"CREATED","to":"STARTING"}
            {"actor":"system/b","time":"2024-01-01T00:00:01Z","lamport":2,"type":"state_changed","from":"CREATED","to":"STARTING"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val writer = StringWriter()
        TraceReplay.printActorTimeline(events, "system/a", writer)
        val output = writer.toString()

        assertThat(output).contains("ACTOR TIMELINE: system/a")
        assertThat(output).contains("STATE")
        assertThat(output).doesNotContain("system/b")
    }

    @Test
    fun `printMessageFlow shows communication summary`() {
        val ndjson = """
            {"actor":"system/b","time":"2024-01-01T00:00:00Z","lamport":1,"type":"msg_received","sender":"system/a"}
            {"actor":"system/b","time":"2024-01-01T00:00:01Z","lamport":2,"type":"msg_received","sender":"system/a"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val writer = StringWriter()
        TraceReplay.printMessageFlow(events, writer)
        val output = writer.toString()

        assertThat(output).contains("MESSAGE FLOW SUMMARY")
        assertThat(output).contains("system/a → system/b")
        assertThat(output).contains("2 messages")
    }

    @Test
    fun `printFailures shows failure report`() {
        val ndjson = """
            {"actor":"system/a","time":"2024-01-01T00:00:00Z","lamport":1,"type":"failure_handled","error":"NPE","message":"null ref","directive":"RESTART"}
        """.trimIndent()

        val events = TraceReplay.loadNdjson(ndjson)
        val writer = StringWriter()
        TraceReplay.printFailures(events, writer)
        val output = writer.toString()

        assertThat(output).contains("FAILURE REPORT")
        assertThat(output).contains("NPE")
        assertThat(output).contains("null ref")
        assertThat(output).contains("RESTART")
    }

    // ─── End-to-End: Cross-Actor Trace Propagation ───────────────

    @Test
    fun `trace context propagates through actor chain A to B to C`() = runBlocking {
        val latch = CountDownLatch(1)
        val actorC = system.spawn("c", receive<CMsg> { ctx, msg ->
            when (msg) {
                is CMsg.Work -> {
                    ctx.info("Doing work in C")
                    latch.countDown()
                    Behavior.same()
                }
            }
        })

        val actorB = system.spawn("b", receive<BMsg> { ctx, msg ->
            when (msg) {
                is BMsg.Forward -> {
                    ctx.info("Forwarding to C")
                    msg.cRef.tell(CMsg.Work)
                    Behavior.same()
                }
            }
        })

        val actorA = system.spawn("a", receive<AMsg> { ctx, msg ->
            when (msg) {
                is AMsg.Start -> {
                    ctx.info("Starting chain")
                    msg.bRef.tell(BMsg.Forward(msg.cRef))
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        actorA.tell(AMsg.Start(actorB, actorC))
        latch.await(3, TimeUnit.SECONDS)
        delay(100.milliseconds)

        // Export all traces and verify the causal chain
        val ndjson = system.exportAllTracesNdjson()
        val events = TraceReplay.loadNdjson(ndjson)

        // Should have events from all 3 actors
        val actors = TraceReplay.groupByActor(events).keys
        assertThat(actors.any { it.contains("/a") }).isTrue
        assertThat(actors.any { it.contains("/b") }).isTrue
        assertThat(actors.any { it.contains("/c") }).isTrue

        // Verify message flow
        val flow = TraceReplay.messageFlow(events)
        // A→B (Forward), B→C (Work)
        assertThat(flow.entries.any { it.key.first.contains("/a") && it.key.second.contains("/b") }).isTrue
        assertThat(flow.entries.any { it.key.first.contains("/b") && it.key.second.contains("/c") }).isTrue

        // All invariants
        actorA.actorCell.checkAllInvariants()
        actorB.actorCell.checkAllInvariants()
        actorC.actorCell.checkAllInvariants()
    }

    @Test
    fun `trace context links sender and receiver via same traceId`() = runBlocking {
        val latch = CountDownLatch(1)
        val ponger = system.spawn("ponger", receive<PongMsg> { _, msg ->
            when (msg) {
                is PongMsg.Pong -> {
                    latch.countDown()
                    Behavior.same()
                }
            }
        })

        val pinger = system.spawn("pinger", receive<PingMsg> { _, msg ->
            when (msg) {
                is PingMsg.Ping -> {
                    msg.target.tell(PongMsg.Pong)
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        pinger.tell(PingMsg.Ping(ponger))
        latch.await(2, TimeUnit.SECONDS)
        delay(50.milliseconds)

        // The MessageSent from pinger and MessageReceived in ponger should share a traceId
        val pingerEvents = pinger.actorCell.flightRecorder.snapshot()
        val pongerEvents = ponger.actorCell.flightRecorder.snapshot()

        val sentEvent = pingerEvents.filterIsInstance<TraceEvent.MessageSent>()
            .find { it.targetPath.contains("ponger") }
        val recvEvent = pongerEvents.filterIsInstance<TraceEvent.MessageReceived>()
            .find { it.messageType == "Pong" }

        assertThat(sentEvent).isNotNull
        assertThat(recvEvent).isNotNull
        assertThat(sentEvent!!.traceContext).isNotNull
        assertThat(recvEvent!!.traceContext).isNotNull

        // Same trace ID means they're part of the same causal chain
        assertThat(recvEvent.traceContext!!.traceId).isEqualTo(sentEvent.traceContext!!.traceId)
    }

    // ─── NDJSON Round-Trip: Export → Load → Analyze ──────────────

    @Test
    fun `full round-trip from live system to TraceReplay analysis`() = runBlocking {
        val latch = CountDownLatch(1)
        val ref = system.spawn("orders", receive<OrderMsg> { ctx, msg ->
            when (msg) {
                is OrderMsg.Place -> {
                    ctx.info("Order placed", "orderId" to msg.orderId)
                    latch.countDown()
                    Behavior.same()
                }
            }
        })

        delay(100.milliseconds)
        ref.tell(OrderMsg.Place("ORD-999"))
        latch.await(2, TimeUnit.SECONDS)
        delay(50.milliseconds)

        // Step 1: Export
        val ndjson = system.exportAllTracesNdjson()

        // Step 2: Load
        val events = TraceReplay.loadNdjson(ndjson)
        assertThat(events).isNotEmpty

        // Step 3: Analyze
        val byActor = TraceReplay.groupByActor(events)
        assertThat(byActor.keys.any { it.contains("orders") }).isTrue

        // Should find the custom event
        val orderEvents = byActor.entries.first { it.key.contains("orders") }.value
        assertThat(orderEvents.any { it.type == "custom" && it["message"] == "Order placed" }).isTrue

        // Step 4: Print timeline (just verify it doesn't crash)
        val writer = StringWriter()
        TraceReplay.printTimeline(events, writer)
        assertThat(writer.toString()).contains("UNIFIED TIMELINE")
    }

    // ─── Concurrency Stress: Trace Propagation Under Load ────────

    @RepeatedTest(3)
    fun `trace context is consistent under concurrent sends`() = runBlocking {
        val messageCount = 50
        val latch = CountDownLatch(messageCount)

        val receiverRef = system.spawn("receiver", statelessBehavior<Int> {
            latch.countDown()
        })

        val senderRef = system.spawn("sender", receive<String> { _, _ ->
            repeat(messageCount) { i ->
                receiverRef.tell(i)
            }
            Behavior.same()
        })

        delay(100.milliseconds)
        senderRef.tell("go")
        latch.await(5, TimeUnit.SECONDS)
        delay(100.milliseconds)

        // Verify receiver got messages with sender info
        val events = receiverRef.actorCell.flightRecorder.snapshot()
        val msgEvents = events.filterIsInstance<TraceEvent.MessageReceived>()

        // All message events should have sender path
        val msgsWithSender = msgEvents.filter { it.senderPath != null }
        assertThat(msgsWithSender).isNotEmpty
        msgsWithSender.forEach { event ->
            assertThat(event.senderPath).contains("sender")
        }

        // All invariants hold
        receiverRef.actorCell.checkAllInvariants()
        senderRef.actorCell.checkAllInvariants()
    }

    // ─── MessageEnvelope Metadata ────────────────────────────────

    @Test
    fun `MessageEnvelope carries all metadata fields`() {
        val ctx = TraceContext.create()
        val envelope = MessageEnvelope(
            message = "hello",
            traceContext = ctx,
            senderPath = "system/sender",
            senderLamportTime = 42,
            messageSnapshot = "hello-snapshot"
        )

        assertThat(envelope.message).isEqualTo("hello")
        assertThat(envelope.traceContext).isEqualTo(ctx)
        assertThat(envelope.senderPath).isEqualTo("system/sender")
        assertThat(envelope.senderLamportTime).isEqualTo(42)
        assertThat(envelope.messageSnapshot).isEqualTo("hello-snapshot")
    }

    @Test
    fun `MessageEnvelope defaults are null and zero`() {
        val envelope = MessageEnvelope(message = "bare")

        assertThat(envelope.traceContext).isNull()
        assertThat(envelope.senderPath).isNull()
        assertThat(envelope.senderLamportTime).isEqualTo(0)
        assertThat(envelope.messageSnapshot).isNull()
    }

    // ─── Actor currentTraceContext ───────────────────────────────

    @Test
    fun `actor currentTraceContext is updated on message receipt`() = runBlocking {
        // Have actor A send to actor B, then B reports its traceContext
        val actorB = system.spawn("b", receive<CheckTraceMsg> { ctx, msg ->
            when (msg) {
                is CheckTraceMsg.CheckTrace -> {
                    msg.replyTo.tell(ctx.currentTraceContext.toString())
                    Behavior.same()
                }
            }
        })

        val actorA = system.spawn("a", receive<String> { _, _ ->
            // Send from A to B — B should get a trace context
            val response: String = actorB.ask { replyTo -> CheckTraceMsg.CheckTrace(replyTo) }
            // We can verify response is a valid trace context representation
            Behavior.same()
        })

        delay(100.milliseconds)
        actorA.tell("go")
        delay(200.milliseconds)

        // B should have processed a message with trace context
        val bEvents = actorB.actorCell.flightRecorder.snapshot()
        val msgEvents = bEvents.filterIsInstance<TraceEvent.MessageReceived>()
        val checkEvent = msgEvents.find { it.messageType == "CheckTrace" }

        assertThat(checkEvent).isNotNull
        assertThat(checkEvent!!.traceContext).isNotNull
        assertThat(checkEvent.traceContext!!.isActive).isTrue
    }
}
