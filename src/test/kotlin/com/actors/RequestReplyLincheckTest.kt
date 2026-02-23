package com.actors

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Test

/**
 * ═══════════════════════════════════════════════════════════════════
 * LINCHECK TEST: Request-Reply Pattern Linearizability
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: RequestReply.tla
 *
 * Verifies the Ask pattern invariants:
 *   - INV-1 MutualExclusion: a request is in at most one state
 *   - INV-2 NoReplyAfterTimeout: replied ∩ timedOut = ∅
 *   - INV-3 NoPhantomReplies: resolved ≤ sent
 *   - INV-4 BoundedPending: pending ≤ MaxPending
 *
 * Models the request-reply state machine for Lincheck verification.
 */
@Param(name = "clientId", gen = IntGen::class, conf = "1:2")
@Param(name = "requestId", gen = IntGen::class, conf = "1:3")
class RequestReplyLincheckTest {

    // ─── State (TLA+ VARIABLES) ─────────────────────────────────
    // Using simple sets to model the request lifecycle
    private val pending = mutableSetOf<Pair<Int, Int>>()     // (client, requestId)
    private val replied = mutableSetOf<Pair<Int, Int>>()     // (client, requestId)
    private val timedOut = mutableSetOf<Pair<Int, Int>>()    // (client, requestId)
    private val nextId = mutableMapOf<Int, Int>()            // client → next request ID
    private val maxPending = 3

    private val lockObj = Any()

    // ─── Operations (TLA+ Actions) ──────────────────────────────

    /** TLA+ action: SendRequest(c) */
    @Operation
    fun sendRequest(@Param(name = "clientId") clientId: Int): String = synchronized(lockObj) {
        val rid = nextId.getOrDefault(clientId, 1)
        if (rid > maxPending) {
            checkInvariants()
            return "no-slot"
        }
        val key = Pair(clientId, rid)
        pending.add(key)
        nextId[clientId] = rid + 1
        checkInvariants()
        "sent:$rid"
    }

    /** TLA+ action: DeliverReply(c, rid) */
    @Operation
    fun deliverReply(
        @Param(name = "clientId") clientId: Int,
        @Param(name = "requestId") requestId: Int
    ): String = synchronized(lockObj) {
        val key = Pair(clientId, requestId)
        if (key in pending && key !in timedOut) {
            pending.remove(key)
            replied.add(key)
            checkInvariants()
            "replied"
        } else {
            checkInvariants()
            "no-op"
        }
    }

    /** TLA+ action: Timeout(c, rid) */
    @Operation
    fun timeout(
        @Param(name = "clientId") clientId: Int,
        @Param(name = "requestId") requestId: Int
    ): String = synchronized(lockObj) {
        val key = Pair(clientId, requestId)
        if (key in pending && key !in replied) {
            pending.remove(key)
            timedOut.add(key)
            checkInvariants()
            "timed-out"
        } else {
            checkInvariants()
            "no-op"
        }
    }

    // ─── Invariant Checks (TLA+ INVARIANTS) ─────────────────────

    private fun checkInvariants() {
        // INV-1: MutualExclusion — request in at most one state
        for (key in pending + replied + timedOut) {
            val count = listOf(key in pending, key in replied, key in timedOut).count { it }
            check(count <= 1) {
                "MutualExclusion violated for $key: in $count states"
            }
        }

        // INV-2: NoReplyAfterTimeout — disjoint sets
        val overlap = replied.intersect(timedOut)
        check(overlap.isEmpty()) {
            "NoReplyAfterTimeout violated: overlap=$overlap"
        }

        // INV-3: NoPhantomReplies — resolved ≤ sent
        for ((clientId, _) in replied + timedOut) {
            val sent = (nextId[clientId] ?: 1) - 1
            val resolved = (replied + timedOut).count { it.first == clientId }
            check(resolved <= sent) {
                "NoPhantomReplies violated: client=$clientId resolved=$resolved > sent=$sent"
            }
        }

        // INV-4: BoundedPending — pending ≤ maxPending
        for (clientId in nextId.keys) {
            val clientPending = pending.count { it.first == clientId }
            check(clientPending <= maxPending) {
                "BoundedPending violated: client=$clientId pending=$clientPending > max=$maxPending"
            }
        }
    }

    // ─── Model Checking ─────────────────────────────────────────

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .threads(2)
        .actorsPerThread(2)
        .actorsBefore(1)
        .actorsAfter(0)
        .iterations(50)
        .invocationsPerIteration(1000)
        .check(this::class)

    // ─── Stress Testing ─────────────────────────────────────────

    @Test
    fun stressTest() = StressOptions()
        .threads(2)
        .actorsPerThread(2)
        .actorsBefore(1)
        .actorsAfter(0)
        .iterations(30)
        .invocationsPerIteration(100)
        .check(this::class)
}
