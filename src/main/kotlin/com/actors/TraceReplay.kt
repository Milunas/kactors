package com.actors

import java.io.Reader
import java.io.Writer
import java.time.Instant

/**
 * ═══════════════════════════════════════════════════════════════════
 * TRACE REPLAY: Post-Mortem Analysis & Replay Utility
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorTrace.tla (extends: trace analysis invariants)
 *
 * Provides tools for loading, analyzing, and visualizing actor traces
 * exported via NDJSON. This is the "100% replayability" engine:
 *
 *   1. EXPORT:    ActorSystem.exportAllTraces("trace.ndjson")
 *   2. TRANSFER:  Copy trace.ndjson from prod to dev machine
 *   3. ANALYZE:   TraceReplay.load("trace.ndjson")
 *   4. VISUALIZE: TraceReplay.printTimeline(...) / buildCausalGraph(...)
 *   5. DEBUG:     Step through events, see who sent what, when, why
 *
 * The replay utility operates on the flat event log and reconstructs:
 *   - Per-actor timeline: what each actor did, in order
 *   - Causal graph: which message caused which (via traceId/spanId)
 *   - Message flow: A sent X to B, B processed it and sent Y to C
 *   - Failure chain: who failed, what happened, how supervisor reacted
 *
 * This is NOT execution replay (we don't re-run the code). It is
 * DIAGNOSTIC replay: you see exactly what happened, step by step,
 * in enough detail to understand and reproduce the bug.
 *
 * Design note: no external dependencies. Parses NDJSON manually
 * (each line is a JSON object → field extraction via regex).
 * For production use, consider piping to jq or loading into a
 * structured analysis tool.
 *
 * Example:
 * ```kotlin
 * // On prod: export trace
 * system.exportAllTraces(File("trace.ndjson").writer())
 *
 * // On dev: analyze
 * val events = TraceReplay.loadNdjson(File("trace.ndjson").reader())
 * TraceReplay.printTimeline(events)
 * TraceReplay.printCausalChain(events, traceId = "abc12345")
 * TraceReplay.printActorTimeline(events, actorPath = "system/orders/worker-3")
 * ```
 */
object TraceReplay {

    /**
     * A parsed trace event from NDJSON, containing all fields as a map.
     * This is a lightweight representation — no need to reconstruct
     * full TraceEvent objects for analysis.
     */
    data class ParsedEvent(
        val actor: String,
        val time: String,
        val lamport: Long,
        val type: String,
        val fields: Map<String, String>
    ) {
        /** Get a field value, or empty string if not present. */
        operator fun get(key: String): String = fields[key] ?: ""
    }

    // ─── Loading ─────────────────────────────────────────────────

    /**
     * Load trace events from NDJSON text (one JSON object per line).
     *
     * @param ndjson The NDJSON string (e.g., from ActorFlightRecorder.exportNdjson())
     * @return List of parsed events, ordered as they appear in the input
     */
    fun loadNdjson(ndjson: String): List<ParsedEvent> {
        return ndjson.lines()
            .filter { it.isNotBlank() }
            .map { parseLine(it) }
    }

    /**
     * Load trace events from a Reader (file, stream, etc.).
     *
     * @param reader The reader providing NDJSON content
     * @return List of parsed events
     */
    fun loadNdjson(reader: Reader): List<ParsedEvent> {
        return reader.readLines()
            .filter { it.isNotBlank() }
            .map { parseLine(it) }
    }

    // ─── Analysis ────────────────────────────────────────────────

    /**
     * Group events by actor path.
     * Returns a map from actor path to its events (in order).
     */
    fun groupByActor(events: List<ParsedEvent>): Map<String, List<ParsedEvent>> {
        return events.groupBy { it.actor }
    }

    /**
     * Group events by trace ID.
     * Returns a map from traceId to all events in that trace chain.
     * Only includes events that have a traceId (msg_received, msg_sent, custom).
     */
    fun groupByTrace(events: List<ParsedEvent>): Map<String, List<ParsedEvent>> {
        return events
            .filter { it["traceId"].isNotEmpty() }
            .groupBy { it["traceId"] }
    }

    /**
     * Build the causal chain for a specific trace ID.
     * Returns events ordered by Lamport timestamp, showing the
     * full message flow: send → receive → send → receive → ...
     */
    fun causalChain(events: List<ParsedEvent>, traceId: String): List<ParsedEvent> {
        return events
            .filter { it["traceId"] == traceId }
            .sortedBy { it.lamport }
    }

    /**
     * Find all failure events across all actors.
     */
    fun failures(events: List<ParsedEvent>): List<ParsedEvent> {
        return events.filter { it.type == "failure_handled" }
    }

    /**
     * Find all slow message warnings.
     */
    fun slowMessages(events: List<ParsedEvent>): List<ParsedEvent> {
        return events.filter { it.type == "slow_message" }
    }

    /**
     * Build a message flow graph: edges between actors.
     * Returns pairs of (sender, receiver) with message counts.
     */
    fun messageFlow(events: List<ParsedEvent>): Map<Pair<String, String>, Int> {
        return events
            .filter { it.type == "msg_received" && it["sender"].isNotEmpty() }
            .groupBy { (it["sender"] to it.actor) }
            .mapValues { it.value.size }
    }

    // ─── Formatted Output ────────────────────────────────────────

    /**
     * Print a unified timeline of ALL events across all actors,
     * sorted by Lamport timestamp (causal order).
     *
     * Output format:
     * ```
     * ═══ UNIFIED TIMELINE (42 events across 5 actors) ═══
     * [L:1  ] system/parent         STATE CREATED→STARTING
     * [L:2  ] system/parent         SIGNAL PreStart
     * [L:3  ] system/parent         STATE STARTING→RUNNING
     * [L:4  ] system/parent         MSG_RECV Process     from=system/dispatcher  trace=abc/s1
     * [L:5  ] system/parent         MSG_SENT → system/worker  DoWork  trace=abc/s2
     * [L:6  ] system/worker         MSG_RECV DoWork      from=system/parent      trace=abc/s2
     * ```
     */
    fun printTimeline(events: List<ParsedEvent>, writer: Writer = java.io.PrintWriter(System.out, true)) {
        val sorted = events.sortedBy { it.lamport }
        val actors = events.map { it.actor }.distinct()
        writer.write("═══ UNIFIED TIMELINE (${events.size} events across ${actors.size} actors) ═══\n")

        for (event in sorted) {
            val line = formatParsedEvent(event)
            writer.write(line)
            writer.write("\n")
        }
        writer.flush()
    }

    /**
     * Print the timeline for a single actor.
     */
    fun printActorTimeline(events: List<ParsedEvent>, actorPath: String, writer: Writer = java.io.PrintWriter(System.out, true)) {
        val actorEvents = events.filter { it.actor == actorPath }
        writer.write("═══ ACTOR TIMELINE: $actorPath (${actorEvents.size} events) ═══\n")
        for (event in actorEvents) {
            writer.write(formatParsedEvent(event))
            writer.write("\n")
        }
        writer.flush()
    }

    /**
     * Print the causal chain for a specific trace ID — shows the full
     * message flow that constitutes one end-to-end operation.
     */
    fun printCausalChain(events: List<ParsedEvent>, traceId: String, writer: Writer = java.io.PrintWriter(System.out, true)) {
        val chain = causalChain(events, traceId)
        writer.write("═══ CAUSAL CHAIN: trace=$traceId (${chain.size} events) ═══\n")
        for (event in chain) {
            val depth = event["parentSpanId"].count { it == 's' } // rough depth estimate
            val indent = "  ".repeat(depth.coerceAtMost(10))
            writer.write("$indent${formatParsedEvent(event)}\n")
        }
        writer.flush()
    }

    /**
     * Print a message flow summary — who talked to whom, how many times.
     */
    fun printMessageFlow(events: List<ParsedEvent>, writer: Writer = java.io.PrintWriter(System.out, true)) {
        val flow = messageFlow(events)
        writer.write("═══ MESSAGE FLOW SUMMARY ═══\n")
        flow.entries.sortedByDescending { it.value }.forEach { (pair, count) ->
            writer.write("  ${pair.first} → ${pair.second}  ($count messages)\n")
        }
        writer.flush()
    }

    /**
     * Print a failure report — all failures with context.
     */
    fun printFailures(events: List<ParsedEvent>, writer: Writer = java.io.PrintWriter(System.out, true)) {
        val fails = failures(events)
        writer.write("═══ FAILURE REPORT (${fails.size} failures) ═══\n")
        for (fail in fails) {
            writer.write("  [L:${fail.lamport}] ${fail.actor}: ${fail["error"]} — ${fail["message"]} → ${fail["directive"]}\n")
        }
        writer.flush()
    }

    // ─── Merging Multiple Traces ─────────────────────────────────

    /**
     * Merge traces from multiple actors/recorders into a single
     * unified timeline, sorted by Lamport timestamp.
     * This is how you reconstruct a system-wide view from per-actor
     * flight recorders.
     */
    fun mergeByLamport(vararg eventLists: List<ParsedEvent>): List<ParsedEvent> {
        return eventLists.flatMap { it }.sortedBy { it.lamport }
    }

    // ─── Internal Parsing ────────────────────────────────────────

    /**
     * Parse a single JSON line into a ParsedEvent.
     * Minimal JSON parser — extracts string and number values.
     * No external library needed.
     */
    private fun parseLine(json: String): ParsedEvent {
        val fields = mutableMapOf<String, String>()
        // Match "key":"value" — handles escaped quotes (\\") inside values
        val stringPattern = Regex(""""(\w+)":"((?:[^"\\]|\\.)*)"""")
        val numberPattern = Regex(""""(\w+)":(\d+)""")

        stringPattern.findAll(json).forEach {
            // Unescape JSON string values
            fields[it.groupValues[1]] = it.groupValues[2]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
        numberPattern.findAll(json).forEach {
            fields[it.groupValues[1]] = it.groupValues[2]
        }

        return ParsedEvent(
            actor = fields["actor"] ?: "",
            time = fields["time"] ?: "",
            lamport = fields["lamport"]?.toLongOrNull() ?: 0,
            type = fields["type"] ?: "",
            fields = fields
        )
    }

    private fun formatParsedEvent(event: ParsedEvent): String {
        val lamport = "[L:%-4d]".format(event.lamport)
        val actor = event.actor.padEnd(35)
        return when (event.type) {
            "msg_received" -> {
                val sender = if (event["sender"].isNotEmpty()) " from=${event["sender"]}" else ""
                val trace = if (event["traceId"].isNotEmpty()) " trace=${event["traceId"]}/${event["spanId"]}" else ""
                val content = if (event["content"].isNotEmpty()) " [${event["content"]}]" else ""
                "$lamport $actor MSG_RECV ${event["messageType"]}$sender$trace$content"
            }
            "msg_sent" -> {
                val trace = if (event["traceId"].isNotEmpty()) " trace=${event["traceId"]}/${event["spanId"]}" else ""
                val content = if (event["content"].isNotEmpty()) " [${event["content"]}]" else ""
                "$lamport $actor MSG_SENT → ${event["target"]} ${event["messageType"]}$trace$content"
            }
            "signal_delivered" -> {
                val detail = if (event["detail"].isNotEmpty()) " (${event["detail"]})" else ""
                "$lamport $actor SIGNAL ${event["signal"]}$detail"
            }
            "state_changed" ->
                "$lamport $actor STATE ${event["from"]}→${event["to"]}"
            "behavior_changed" ->
                "$lamport $actor BEHAVIOR → ${event["behavior"]}"
            "child_spawned" ->
                "$lamport $actor SPAWN ${event["child"]} (${event["behavior"]})"
            "child_stopped" ->
                "$lamport $actor CHILD_STOP ${event["child"]} (${event["reason"]})"
            "watch_registered" ->
                "$lamport $actor WATCH → ${event["watched"]}"
            "failure_handled" ->
                "$lamport $actor FAILURE ${event["error"]}: ${event["message"]} → ${event["directive"]}"
            "slow_message" ->
                "$lamport $actor ⚠ SLOW ${event["messageType"]} ${event["durationMs"]}ms"
            "custom" -> {
                val level = event["level"].padEnd(5)
                val trace = if (event["traceId"].isNotEmpty()) " trace=${event["traceId"]}/${event["spanId"]}" else ""
                "$lamport $actor [$level] ${event["message"]}$trace"
            }
            else ->
                "$lamport $actor ${event.type} ${event.fields}"
        }
    }
}
