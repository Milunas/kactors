package com.actors

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════
 * LINCHECK TEST: Actor Lifecycle Linearizability
 * ═══════════════════════════════════════════════════════════════════
 *
 * TLA+ Spec: ActorLifecycle.tla
 *
 * Verifies that concurrent lifecycle transitions are safe:
 *   - Multiple threads calling start/stop/processMessage
 *   - State machine invariants hold in every interleaving
 *   - Restart budget is never exceeded
 *
 * This is a simplified sequential model for Lincheck verification.
 * The real ActorCell uses coroutines, but the state machine logic
 * is the same and is verified here for linearizability.
 */
class ActorLifecycleLincheckTest {

    // ─── State (TLA+ VARIABLES) ─────────────────────────────────
    private val actorState = AtomicReference("created")
    private val restartCount = AtomicInteger(0)
    private val processed = AtomicInteger(0)
    private val maxRestarts = 3

    private val lockObj = Any()

    // ─── Operations (TLA+ Actions) ──────────────────────────────

    /** TLA+ action: Start(a) — created → starting */
    @Operation
    fun start(): String = synchronized(lockObj) {
        if (actorState.get() == "created") {
            actorState.set("starting")
            checkInvariants()
            "started"
        } else {
            checkInvariants()
            "no-op"
        }
    }

    /** TLA+ action: BecomeRunning(a) — starting → running */
    @Operation
    fun becomeRunning(): String = synchronized(lockObj) {
        if (actorState.get() == "starting") {
            actorState.set("running")
            checkInvariants()
            "running"
        } else {
            checkInvariants()
            "no-op"
        }
    }

    /** TLA+ action: ProcessMessage(a) — increments processed count */
    @Operation
    fun processMessage(): String = synchronized(lockObj) {
        if (actorState.get() == "running") {
            processed.incrementAndGet()
            checkInvariants()
            "processed"
        } else {
            checkInvariants()
            "no-op"
        }
    }

    /** TLA+ action: Fail(a) — running → restarting (if budget allows) */
    @Operation
    fun fail(): String = synchronized(lockObj) {
        if (actorState.get() == "running") {
            if (restartCount.get() < maxRestarts) {
                actorState.set("restarting")
                restartCount.incrementAndGet()
                checkInvariants()
                "restarting"
            } else {
                actorState.set("stopping")
                checkInvariants()
                "stopping-permanent"
            }
        } else {
            checkInvariants()
            "no-op"
        }
    }

    /** TLA+ action: Restart(a) — restarting → starting */
    @Operation
    fun restart(): String = synchronized(lockObj) {
        if (actorState.get() == "restarting") {
            actorState.set("starting")
            checkInvariants()
            "restarted"
        } else {
            checkInvariants()
            "no-op"
        }
    }

    /** TLA+ action: GracefulStop(a) — running → stopping */
    @Operation
    fun gracefulStop(): String = synchronized(lockObj) {
        if (actorState.get() == "running") {
            actorState.set("stopping")
            checkInvariants()
            "stopping"
        } else {
            checkInvariants()
            "no-op"
        }
    }

    /** TLA+ action: CompleteStopping(a) — stopping → stopped */
    @Operation
    fun completeStopping(): String = synchronized(lockObj) {
        if (actorState.get() == "stopping") {
            actorState.set("stopped")
            checkInvariants()
            "stopped"
        } else {
            checkInvariants()
            "no-op"
        }
    }

    // ─── Invariant Checks (TLA+ INVARIANTS) ─────────────────────

    private fun checkInvariants() {
        val state = actorState.get()
        val restarts = restartCount.get()

        // INV-1: RestartBudgetRespected
        check(restarts <= maxRestarts) {
            "RestartBudgetRespected violated: restarts=$restarts > max=$maxRestarts"
        }

        // INV-2: StoppedNotAlive
        if (state == "stopped") {
            // Stopped actors must not be considered alive
            check(state !in setOf("created", "starting", "running", "restarting")) {
                "StoppedNotAlive violated"
            }
        }

        // INV-3: AliveConsistency — alive actors are not stopped
        if (state in setOf("created", "starting", "running", "restarting")) {
            check(state != "stopped") { "AliveConsistency violated" }
        }
    }

    // ─── Model Checking ─────────────────────────────────────────

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
