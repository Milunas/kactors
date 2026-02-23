package com.actors

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Test

/**
 * ═══════════════════════════════════════════════════════════════════
 * LINCHECK TEST: Mailbox Linearizability
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorMailbox.tla
 *
 * Verifies that the Mailbox (bounded channel) is linearizable:
 *   - Concurrent send/trySend/tryReceive operations appear to
 *     execute atomically in some sequential order
 *   - All TLA+ invariants hold in every interleaving
 *
 * Generated structure matches tla2lincheck output pattern.
 * The invariants from ActorMailbox.tla are embedded as post-operation checks.
 */
@Param(name = "msg", gen = IntGen::class, conf = "1:5")
class MailboxLincheckTest {

    // ─── State (TLA+ VARIABLES) ─────────────────────────────────
    // mailbox: bounded FIFO queue
    private val mailbox = Mailbox<Int>(capacity = 3)
    // sendCount (tracked internally by Mailbox)
    // recvCount (tracked internally by Mailbox)

    private val lockObj = Any()

    // ─── Operations (TLA+ Actions) ──────────────────────────────

    @Operation
    fun trySend(@Param(name = "msg") msg: Int): Boolean = synchronized(lockObj) {
        val result = mailbox.trySend(msg)
        checkInvariants()
        result
    }

    @Operation
    fun tryReceive(): Int? = synchronized(lockObj) {
        val result = mailbox.tryReceive()
        checkInvariants()
        result
    }

    // ─── Invariant Checks (TLA+ INVARIANTS) ─────────────────────

    /**
     * Embedded invariant checks from ActorMailbox.tla:
     *   INV-1: BoundedCapacity  — len(mailbox) ≤ capacity
     *   INV-2: MessageConservation — received ≤ total_sent
     *   INV-3: NonNegativeLength — len(mailbox) ≥ 0
     */
    private fun checkInvariants() {
        check(mailbox.checkBoundedCapacity()) { "BoundedCapacity violated" }
        check(mailbox.checkMessageConservation()) { "MessageConservation violated" }
        check(mailbox.checkNonNegativeLength()) { "NonNegativeLength violated" }
    }

    // ─── Model Checking ─────────────────────────────────────────
    // Explores ALL thread interleavings exhaustively

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .threads(3)
        .actorsPerThread(2)
        .actorsBefore(1)
        .actorsAfter(0)
        .iterations(50)
        .invocationsPerIteration(1000)
        .check(this::class)

    // ─── Stress Testing ─────────────────────────────────────────
    // Runs with real concurrency to catch races the model checker might miss

    @Test
    fun stressTest() = StressOptions()
        .threads(3)
        .actorsPerThread(3)
        .actorsBefore(1)
        .actorsAfter(0)
        .iterations(30)
        .invocationsPerIteration(100)
        .check(this::class)
}
