package com.actors

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * ═══════════════════════════════════════════════════════════════════
 * ACTOR FLIGHT RECORDER: Bounded Coalgebraic Trace Buffer
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorTrace.tla
 *
 * A per-actor ring buffer that records the last N [TraceEvent]s,
 * providing post-mortem debugging without unbounded memory growth.
 *
 * This is the runtime realization of the coalgebraic trace semantics:
 * given an actor coalgebra (B, δ: B → M → B × TraceEvent), the
 * flight recorder captures a sliding window over the TraceEvent
 * component of the product, forming a finite prefix of the final
 * coalgebra (the terminal object in the category of F-coalgebras).
 *
 * Design choices:
 *   - Ring buffer (ArrayDeque): O(1) record, O(N) snapshot. When full,
 *     the oldest event is evicted. This corresponds to TLA+ action
 *     RecordEvent with the Tail/Append pattern.
 *   - ReadWriteLock: multiple readers can snapshot concurrently while
 *     a single writer records events. This is safe because actors
 *     process messages sequentially (single writer), but monitoring
 *     tools may read concurrently.
 *   - Lamport clock: each event gets a monotonically increasing
 *     logical timestamp. In single-actor mode, this equals eventCount.
 *     In distributed mode, it enables causal ordering across actors.
 *
 * Unlike Akka's "debug logging" or Erlang's "dbg" module, this
 * recorder is always-on with near-zero overhead (no serialization,
 * no I/O — just in-memory ring buffer). It is designed to be
 * queried during failures, not during normal operation.
 *
 * Example:
 * ```kotlin
 * val recorder = ActorFlightRecorder(capacity = 64)
 * recorder.record(TraceEvent.MessageReceived(...))
 * // ... later, during debugging:
 * recorder.snapshot().forEach { println(it) }
 * // or dump as structured text:
 * println(recorder.dump())
 * ```
 */
@TlaSpec("ActorTrace")
class ActorFlightRecorder(
    /**
     * Maximum number of events retained in the ring buffer.
     * When exceeded, the oldest event is evicted.
     * Corresponds to TLA+ constant: MaxBufferSize
     */
    val capacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        /**
         * Default buffer size: 128 events per actor.
         * Chosen to balance memory overhead (~20KB per actor at 160 bytes/event)
         * with sufficient history for post-mortem debugging.
         * A typical actor processes 1000s of messages, so 128 events
         * captures the last ~100ms of activity at high throughput.
         */
        const val DEFAULT_CAPACITY = 128

        private val log = LoggerFactory.getLogger(ActorFlightRecorder::class.java)
    }

    // ─── Ring Buffer ─────────────────────────────────────────────

    /**
     * The bounded event buffer. ArrayDeque gives O(1) addLast + removeFirst.
     * Corresponds to TLA+ variable: trace
     */
    @TlaVariable("trace")
    private val events = ArrayDeque<TraceEvent>(capacity)

    /**
     * Total number of events ever recorded (including evicted ones).
     * Monotonically increasing. Always ≥ events.size.
     * Corresponds to TLA+ variable: eventCount
     */
    @TlaVariable("eventCount")
    private val totalEventCount = AtomicLong(0)

    /**
     * Lamport logical clock. Incremented on each recorded event.
     * In single-actor mode, equals totalEventCount. In distributed
     * mode, updated via max(local, remote) + 1 on message receipt.
     * Corresponds to TLA+ variable: lamportClock
     */
    @TlaVariable("lamportClock")
    private val lamportClock = AtomicLong(0)

    /**
     * ReadWriteLock for concurrent access: single writer (actor coroutine),
     * multiple readers (monitoring, dump, test assertions).
     */
    private val lock = ReentrantReadWriteLock()

    // ─── Recording ───────────────────────────────────────────────

    /**
     * Record a trace event into the ring buffer.
     * When the buffer is full, the oldest event is evicted.
     *
     * Thread-safety: called from the actor's own coroutine (single writer).
     * The write lock is held briefly to ensure snapshot consistency.
     *
     * TLA+ action: RecordEvent(a, e) — Maps to this method.
     *
     * @param event The trace event to record
     */
    @TlaAction("RecordEvent")
    fun record(event: TraceEvent) {
        lock.write {
            if (events.size >= capacity) {
                events.removeFirst() // Evict oldest (ring buffer semantics)
            }
            events.addLast(event)
        }
        totalEventCount.incrementAndGet()
        lamportClock.incrementAndGet()
    }

    /**
     * Update the Lamport clock on receiving a message from another actor.
     * Sets local clock to max(local, remote) + 1.
     * This is only used when cross-actor trace correlation is needed.
     *
     * @param remoteLamportTimestamp The sender's Lamport timestamp
     * @return The new local Lamport timestamp
     */
    fun updateLamportClock(remoteLamportTimestamp: Long): Long {
        return lamportClock.updateAndGet { local ->
            maxOf(local, remoteLamportTimestamp) + 1
        }
    }

    /**
     * Get the current Lamport timestamp for the next event.
     * Called during TraceEvent construction to set the logical timestamp.
     */
    fun nextLamportTimestamp(): Long = lamportClock.get() + 1

    // ─── Querying ────────────────────────────────────────────────

    /**
     * Take an immutable snapshot of the current trace buffer.
     * Safe to call from any thread (uses read lock).
     *
     * @return Ordered list of trace events (oldest first)
     */
    fun snapshot(): List<TraceEvent> = lock.read {
        events.toList()
    }

    /**
     * Return only trace events matching a predicate.
     * Useful for filtering by event type during debugging.
     *
     * Example:
     * ```kotlin
     * val failures = recorder.snapshot { it is TraceEvent.FailureHandled }
     * val signals = recorder.snapshot { it is TraceEvent.SignalDelivered }
     * ```
     */
    fun snapshot(predicate: (TraceEvent) -> Boolean): List<TraceEvent> = lock.read {
        events.filter(predicate)
    }

    /**
     * Get the last N events (most recent first).
     * Useful for quick debugging: "what happened just before this crash?"
     */
    fun lastN(n: Int): List<TraceEvent> = lock.read {
        events.takeLast(n).reversed()
    }

    /**
     * Current number of events in the buffer (not total ever recorded).
     */
    val size: Int get() = lock.read { events.size }

    /**
     * Total events ever recorded (including evicted ones from ring buffer).
     */
    val totalEvents: Long get() = totalEventCount.get()

    /**
     * Current Lamport clock value.
     */
    val currentLamportTime: Long get() = lamportClock.get()

    /**
     * Number of events that have been evicted from the ring buffer.
     */
    val evictedCount: Long get() = totalEventCount.get() - size

    // ─── Formatted Output ────────────────────────────────────────

    /**
     * Dump the trace buffer as a human-readable structured string.
     * Designed for console debugging, log output, and test assertions.
     *
     * Output format:
     * ```
     * ╔══════════════════════════════════════════════════════════════╗
     * ║ FLIGHT RECORDER: system/parent/child                        ║
     * ║ Events: 42 recorded, 128 in buffer, 0 evicted              ║
     * ╠══════════════════════════════════════════════════════════════╣
     * ║ [L:17] 2026-02-24T10:30:00.123Z  StateChanged CREATED→STARTING   ║
     * ║ [L:18] 2026-02-24T10:30:00.124Z  SignalDelivered PreStart        ║
     * ║ [L:19] 2026-02-24T10:30:00.125Z  StateChanged STARTING→RUNNING   ║
     * ║ [L:20] 2026-02-24T10:30:00.200Z  MessageReceived Increment       ║
     * ╚══════════════════════════════════════════════════════════════╝
     * ```
     */
    fun dump(): String = lock.read {
        val sb = StringBuilder()
        val actorName = events.firstOrNull()?.actorPath ?: "(empty)"
        val total = totalEventCount.get()
        val evicted = total - events.size

        sb.appendLine("╔══════════════════════════════════════════════════════════════════════════╗")
        sb.appendLine("║ FLIGHT RECORDER: %-55s ║".format(actorName))
        sb.appendLine("║ Events: %-8d recorded, %-5d in buffer, %-8d evicted             ║".format(total, events.size, evicted))
        sb.appendLine("╠══════════════════════════════════════════════════════════════════════════╣")

        for (event in events) {
            val line = formatEvent(event)
            sb.appendLine("║ %-72s ║".format(line))
        }

        sb.appendLine("╚══════════════════════════════════════════════════════════════════════════╝")
        sb.toString()
    }

    /**
     * Dump the trace buffer as a JSON array string.
     * Useful for tooling, visualization, and programmatic analysis.
     * No external JSON library dependency — hand-written for zero overhead.
     */
    fun dumpJson(): String = lock.read {
        val sb = StringBuilder()
        sb.append("[\n")
        events.forEachIndexed { index, event ->
            sb.append("  ")
            sb.append(eventToJson(event))
            if (index < events.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.append("]")
        sb.toString()
    }

    // ─── Formatting Helpers ──────────────────────────────────────

    private fun formatEvent(event: TraceEvent): String {
        val lamport = "[L:%-4d]".format(event.lamportTimestamp)
        val time = event.timestamp.toString().take(23)
        return when (event) {
            is TraceEvent.MessageReceived -> {
                val sender = event.senderPath?.let { " from=$it" } ?: ""
                val trace = event.traceContext?.let { " trace=${it.traceId}/${it.spanId}" } ?: ""
                val content = event.messageContent?.let { " content=$it" } ?: ""
                "$lamport $time  MSG_RECV ${event.messageType}$sender$trace$content"
            }
            is TraceEvent.MessageSent -> {
                val trace = event.traceContext?.let { " trace=${it.traceId}/${it.spanId}" } ?: ""
                val content = event.messageContent?.let { " content=$it" } ?: ""
                "$lamport $time  MSG_SENT → ${event.targetPath} ${event.messageType}$trace$content"
            }
            is TraceEvent.SignalDelivered ->
                "$lamport $time  SIGNAL ${event.signalType}${if (event.detail.isNotEmpty()) " (${event.detail})" else ""}"
            is TraceEvent.StateChanged ->
                "$lamport $time  STATE ${event.fromState}→${event.toState}"
            is TraceEvent.BehaviorChanged ->
                "$lamport $time  BEHAVIOR → ${event.behaviorType}"
            is TraceEvent.ChildSpawned ->
                "$lamport $time  SPAWN ${event.childPath} (${event.childBehaviorType})"
            is TraceEvent.ChildStopped ->
                "$lamport $time  CHILD_STOP ${event.childPath} (${event.reason})"
            is TraceEvent.WatchRegistered ->
                "$lamport $time  WATCH → ${event.watchedPath}"
            is TraceEvent.FailureHandled ->
                "$lamport $time  FAILURE ${event.errorType}: ${event.errorMessage} → ${event.directive} (restart #${event.restartCount})"
            is TraceEvent.SlowMessageWarning ->
                "$lamport $time  ⚠ SLOW ${event.messageType} ${event.durationMs}ms (threshold=${event.thresholdMs}ms)"
            is TraceEvent.CustomEvent -> {
                val lvl = event.level.name.padEnd(5)
                val trace = event.traceContext?.let { " trace=${it.traceId}/${it.spanId}" } ?: ""
                val extra = if (event.extra.isNotEmpty()) " ${event.extra}" else ""
                "$lamport $time  [$lvl] ${event.message}$trace$extra"
            }
        }
    }

    private fun eventToJson(event: TraceEvent): String {
        val base = """{"actor":"${event.actorPath}","time":"${event.timestamp}","lamport":${event.lamportTimestamp}"""
        return when (event) {
            is TraceEvent.MessageReceived -> {
                val sender = event.senderPath?.let { ""","sender":"$it"""" } ?: ""
                val trace = event.traceContext?.let { ""","traceId":"${it.traceId}","spanId":"${it.spanId}","parentSpanId":"${it.parentSpanId}"""" } ?: ""
                val content = event.messageContent?.let { ""","content":"${escapeJson(it)}"""" } ?: ""
                """$base,"type":"msg_received","messageType":"${event.messageType}","mailboxSize":${event.mailboxSizeAfter}$sender$trace$content}"""
            }
            is TraceEvent.MessageSent -> {
                val trace = event.traceContext?.let { ""","traceId":"${it.traceId}","spanId":"${it.spanId}","parentSpanId":"${it.parentSpanId}"""" } ?: ""
                val content = event.messageContent?.let { ""","content":"${escapeJson(it)}"""" } ?: ""
                """$base,"type":"msg_sent","target":"${event.targetPath}","messageType":"${event.messageType}"$trace$content}"""
            }
            is TraceEvent.SignalDelivered ->
                """$base,"type":"signal_delivered","signal":"${event.signalType}","detail":"${event.detail}"}"""
            is TraceEvent.StateChanged ->
                """$base,"type":"state_changed","from":"${event.fromState}","to":"${event.toState}"}"""
            is TraceEvent.BehaviorChanged ->
                """$base,"type":"behavior_changed","behavior":"${event.behaviorType}"}"""
            is TraceEvent.ChildSpawned ->
                """$base,"type":"child_spawned","child":"${event.childPath}","behavior":"${event.childBehaviorType}"}"""
            is TraceEvent.ChildStopped ->
                """$base,"type":"child_stopped","child":"${event.childPath}","reason":"${event.reason}"}"""
            is TraceEvent.WatchRegistered ->
                """$base,"type":"watch_registered","watched":"${event.watchedPath}"}"""
            is TraceEvent.FailureHandled ->
                """$base,"type":"failure_handled","error":"${event.errorType}","message":"${escapeJson(event.errorMessage)}","directive":"${event.directive}","restarts":${event.restartCount}}"""
            is TraceEvent.SlowMessageWarning ->
                """$base,"type":"slow_message","messageType":"${event.messageType}","durationMs":${event.durationMs},"thresholdMs":${event.thresholdMs}}"""
            is TraceEvent.CustomEvent -> {
                val trace = event.traceContext?.let { ""","traceId":"${it.traceId}","spanId":"${it.spanId}"""" } ?: ""
                val extra = if (event.extra.isNotEmpty()) {
                    val pairs = event.extra.entries.joinToString(",") { """"${it.key}":"${escapeJson(it.value)}"""" }
                    ""","extra":{$pairs}"""
                } else ""
                """$base,"type":"custom","level":"${event.level}","message":"${escapeJson(event.message)}"$trace$extra}"""
            }
        }
    }

    /**
     * Escape a string for safe JSON embedding.
     * Handles quotes, backslashes, and newlines.
     */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    // ─── NDJSON Export (Replayability) ─────────────────────────────

    /**
     * Export the trace buffer as NDJSON (Newline-Delimited JSON).
     * Each event is one JSON object per line — the standard format
     * for log streaming, import into analysis tools, and replay.
     *
     * NDJSON can be:
     *   - Written to a file for post-mortem analysis
     *   - Piped to jq for filtering: `cat trace.ndjson | jq 'select(.type=="msg_received")'`
     *   - Loaded by [TraceReplay] for causal graph reconstruction
     *   - Fed into Elasticsearch / Kibana / Grafana Loki
     *
     * @return One JSON object per line, no trailing newline
     */
    fun exportNdjson(): String = lock.read {
        events.joinToString("\n") { eventToJson(it) }
    }

    /**
     * Export the trace buffer to a [java.io.Writer].
     * More memory-efficient than [exportNdjson] for large buffers.
     */
    fun exportNdjson(writer: java.io.Writer) = lock.read {
        events.forEachIndexed { index, event ->
            writer.write(eventToJson(event))
            if (index < events.size - 1) writer.write("\n")
        }
        writer.flush()
    }

    // ─── Invariant Checks (TLA+ → Kotlin bridge) ────────────────

    /**
     * INV-1: BoundedTrace — Buffer size never exceeds capacity.
     * Corresponds to TLA+ invariant: BoundedTrace
     */
    @TlaInvariant("BoundedTrace")
    fun checkBoundedTrace(): Boolean = lock.read { events.size <= capacity }

    /**
     * INV-2: NonNegativeEventCount — Total event count is non-negative.
     * Corresponds to TLA+ invariant: NonNegativeEventCount
     */
    @TlaInvariant("NonNegativeEventCount")
    fun checkNonNegativeEventCount(): Boolean = totalEventCount.get() >= 0

    /**
     * INV-4: TraceLengthBounded — Buffer size ≤ total events recorded.
     * Events may be evicted, so buffer size can be less than total count.
     * Corresponds to TLA+ invariant: TraceLengthBounded
     */
    @TlaInvariant("TraceLengthBounded")
    fun checkTraceLengthBounded(): Boolean = lock.read { events.size.toLong() <= totalEventCount.get() }

    /**
     * INV-5: TraceLengthConsistency — Buffer size ≤ capacity.
     * Redundant with INV-1 but stated separately for the paper.
     * Corresponds to TLA+ invariant: TraceLengthConsistency
     */
    @TlaInvariant("TraceLengthConsistency")
    fun checkTraceLengthConsistency(): Boolean = lock.read { events.size <= capacity }

    /**
     * INV-6: LamportClockMonotonic — Lamport clock is always ≥ total event count.
     * In single-actor mode, they are equal. With cross-actor sends,
     * the clock may jump ahead due to max(local, remote) + 1.
     * Corresponds to TLA+ invariant: LamportClockMonotonic
     */
    @TlaInvariant("LamportClockMonotonic")
    fun checkLamportClockMonotonic(): Boolean = lamportClock.get() >= totalEventCount.get()

    /**
     * Verify all flight recorder invariants.
     * Called from ActorCell.checkAllInvariants() and from tests.
     */
    fun checkAllInvariants() {
        check(checkBoundedTrace()) {
            "Invariant violated: BoundedTrace — buffer size ${events.size} exceeds capacity $capacity"
        }
        check(checkNonNegativeEventCount()) {
            "Invariant violated: NonNegativeEventCount — totalEventCount=${totalEventCount.get()}"
        }
        check(checkTraceLengthBounded()) {
            "Invariant violated: TraceLengthBounded — buffer size ${events.size} > totalEventCount ${totalEventCount.get()}"
        }
        check(checkTraceLengthConsistency()) {
            "Invariant violated: TraceLengthConsistency — buffer size ${events.size} > capacity $capacity"
        }
        check(checkLamportClockMonotonic()) {
            "Invariant violated: LamportClockMonotonic — lamportClock=${lamportClock.get()} < totalEventCount=${totalEventCount.get()}"
        }
    }

    override fun toString(): String = "ActorFlightRecorder(capacity=$capacity, size=$size, total=$totalEvents)"
}
