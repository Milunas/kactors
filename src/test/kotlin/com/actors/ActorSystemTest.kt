package com.actors

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ═══════════════════════════════════════════════════════════════════
 * UNIT TESTS: Actor System Core Functionality
 * ═══════════════════════════════════════════════════════════════════
 *
 * Tests the fundamental actor operations against expected behavior
 * derived from TLA+ specifications.
 */
class ActorSystemTest {

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.create("test-system")
    }

    @AfterEach
    fun teardown() = runBlocking {
        if (!system.isTerminated) {
            system.terminate()
        }
    }

    // ─── Mailbox Tests (ActorMailbox.tla) ────────────────────────

    @Test
    fun `mailbox send and receive preserves FIFO order`() = runBlocking {
        val received = mutableListOf<Int>()
        val ref = system.spawn<Int>("fifo-actor", statelessBehavior { msg ->
            received.add(msg)
        })

        ref.tell(1)
        ref.tell(2)
        ref.tell(3)
        delay(100.milliseconds)

        assertThat(received).containsExactly(1, 2, 3)
    }

    @Test
    fun `mailbox trySend returns false when full`() = runBlocking {
        val mailbox = Mailbox<Int>(capacity = 2)
        assertThat(mailbox.trySend(1)).isTrue()
        assertThat(mailbox.trySend(2)).isTrue()
        assertThat(mailbox.trySend(3)).isFalse() // Full!

        // INV-1: BoundedCapacity
        mailbox.checkBoundedCapacity()
    }

    @Test
    fun `mailbox tryReceive returns null when empty`() {
        val mailbox = Mailbox<Int>()
        assertThat(mailbox.tryReceive()).isNull()
    }

    @Test
    fun `mailbox invariants hold after operations`() = runBlocking {
        val mailbox = Mailbox<Int>(capacity = 5)
        mailbox.send(10)
        mailbox.send(20)
        val msg = mailbox.receive()
        assertThat(msg).isEqualTo(10)

        // All invariants from ActorMailbox.tla
        mailbox.checkAllInvariants()
    }

    // ─── Lifecycle Tests (ActorLifecycle.tla) ────────────────────

    @Test
    fun `actor transitions through lifecycle states`() = runBlocking {
        val started = AtomicInteger(0)
        val stopped = AtomicInteger(0)

        val behavior = lifecycleBehavior<String>(
            onStart = { started.incrementAndGet() },
            onStop = { stopped.incrementAndGet() }
        ) { msg ->
            if (msg == "stop") Behavior.stopped()
            else Behavior.same()
        }

        val ref = system.spawn("lifecycle-actor", behavior)
        delay(50.milliseconds)

        // Should be RUNNING now (TLA+: Start → BecomeRunning)
        assertThat(ref.actorCell.state).isEqualTo(ActorState.RUNNING)
        assertThat(started.get()).isEqualTo(1)

        // Send stop signal (TLA+: GracefulStop → CompleteStopping)
        ref.tell("stop")
        delay(100.milliseconds)

        assertThat(ref.actorCell.state).isEqualTo(ActorState.STOPPED)
        assertThat(stopped.get()).isEqualTo(1)
    }

    @Test
    fun `actor processes messages and counts them`() = runBlocking {
        val ref = system.spawn<String>("counter-actor", statelessBehavior { _ -> })
        ref.tell("a")
        ref.tell("b")
        ref.tell("c")
        delay(100.milliseconds)

        // TLA+ variable: processed[a]
        assertThat(ref.actorCell.processedCount).isEqualTo(3)
    }

    // ─── Supervisor Strategy Tests (ActorLifecycle.tla: Fail/Restart) ─

    @Test
    fun `restart strategy restarts actor on failure`() = runBlocking {
        val callCount = AtomicInteger(0)

        val behavior = statelessBehavior<String> { msg ->
            val count = callCount.incrementAndGet()
            if (msg == "fail" && count <= 2) {
                throw RuntimeException("Simulated failure #$count")
            }
        }

        val ref = system.spawn("restart-actor", behavior,
            supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 3))

        ref.tell("fail")
        delay(100.milliseconds)

        // TLA+ invariant: RestartBudgetRespected
        assertThat(ref.actorCell.restartCount).isLessThanOrEqualTo(3)
        assertThat(ref.actorCell.state).isIn(ActorState.RUNNING, ActorState.STOPPED)
        ref.actorCell.checkRestartBudget()
    }

    @Test
    fun `stop strategy stops actor on failure`() = runBlocking {
        val behavior = statelessBehavior<String> { msg ->
            if (msg == "fail") throw RuntimeException("Fatal!")
        }

        val ref = system.spawn("stop-actor", behavior,
            supervisorStrategy = SupervisorStrategy.stop())

        ref.tell("fail")
        delay(200.milliseconds)

        // TLA+: FailPermanent → Stopping → Stopped
        assertThat(ref.actorCell.state).isEqualTo(ActorState.STOPPED)
    }

    @Test
    fun `resume strategy skips failed message`() = runBlocking {
        val processed = mutableListOf<String>()
        val behavior = statelessBehavior<String> { msg ->
            if (msg == "bad") throw RuntimeException("Bad message!")
            processed.add(msg)
        }

        val ref = system.spawn("resume-actor", behavior,
            supervisorStrategy = SupervisorStrategy.resume())

        ref.tell("good1")
        ref.tell("bad")
        ref.tell("good2")
        delay(200.milliseconds)

        assertThat(processed).containsExactly("good1", "good2")
    }

    @Test
    fun `restart budget exhaustion leads to stop`() = runBlocking {
        val behavior = statelessBehavior<String> { msg ->
            if (msg == "fail") throw RuntimeException("Keep failing!")
        }

        val ref = system.spawn("exhaust-actor", behavior,
            supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 2))

        // Send more failures than the budget allows
        repeat(5) {
            ref.tell("fail")
            delay(50.milliseconds)
        }
        delay(200.milliseconds)

        // TLA+ invariant: RestartBudgetRespected
        assertThat(ref.actorCell.restartCount).isLessThanOrEqualTo(2)
        ref.actorCell.checkRestartBudget()
    }

    // ─── Ask Pattern Tests (RequestReply.tla) ────────────────────

    sealed class PingMsg {
        data class Ping(override val replyTo: ActorRef<String>) : PingMsg(), Request<String>
        data class SetValue(val value: String) : PingMsg()
    }

    @Test
    fun `ask pattern returns reply`() = runBlocking {
        var currentValue = "hello"
        val behavior = behavior<PingMsg> { msg ->
            when (msg) {
                is PingMsg.Ping -> {
                    msg.replyTo.tell(currentValue)
                    Behavior.same()
                }
                is PingMsg.SetValue -> {
                    currentValue = msg.value
                    Behavior.same()
                }
            }
        }

        val ref = system.spawn("ping-actor", behavior)
        delay(50.milliseconds)

        // TLA+: SendRequest → ProcessRequest → DeliverReply
        val reply: String = ref.ask(timeout = 2.seconds) { replyTo -> PingMsg.Ping(replyTo) }
        assertThat(reply).isEqualTo("hello")
    }

    @Test
    fun `ask pattern timeout throws`() = runBlocking {
        // Actor that never replies
        val behavior = statelessBehavior<PingMsg> { _ -> /* ignore */ }
        val ref = system.spawn("timeout-actor", behavior)
        delay(50.milliseconds)

        // TLA+: SendRequest → Timeout
        assertThrows<Exception> {
            runBlocking {
                ref.ask<String>(timeout = 100.milliseconds) { replyTo -> PingMsg.Ping(replyTo) }
            }
        }
    }

    // ─── Behavior Switching Tests ────────────────────────────────

    @Test
    fun `behavior can switch on message`() = runBlocking {
        val msgLog = mutableListOf<String>()

        // Use lateinit + object to break forward reference cycle
        lateinit var sadBehavior: () -> Behavior<String>

        val happyBehavior: () -> Behavior<String> = {
            behavior { msg ->
                msgLog.add("happy:$msg")
                if (msg == "switch") sadBehavior() else Behavior.same()
            }
        }

        sadBehavior = {
            behavior { msg ->
                msgLog.add("sad:$msg")
                if (msg == "switch") happyBehavior() else Behavior.same()
            }
        }

        val ref = system.spawn("switch-actor", happyBehavior())
        delay(50.milliseconds)

        ref.tell("a")
        ref.tell("switch")
        ref.tell("b")
        delay(200.milliseconds)

        assertThat(msgLog).containsExactly("happy:a", "happy:switch", "sad:b")
    }

    // ─── System Tests ────────────────────────────────────────────

    @Test
    fun `system spawns multiple actors`() = runBlocking {
        system.spawn<String>("actor1", statelessBehavior { })
        system.spawn<String>("actor2", statelessBehavior { })
        system.spawn<String>("actor3", statelessBehavior { })

        assertThat(system.actorCount).isEqualTo(3)
        assertThat(system.actorNames).containsExactlyInAnyOrder("actor1", "actor2", "actor3")
    }

    @Test
    fun `system rejects duplicate actor names`() {
        system.spawn<String>("unique", statelessBehavior { })

        assertThrows<IllegalStateException> {
            system.spawn<String>("unique", statelessBehavior { })
        }
    }

    @Test
    fun `system terminate stops all actors`() = runBlocking {
        val ref1 = system.spawn<String>("a1", statelessBehavior { })
        val ref2 = system.spawn<String>("a2", statelessBehavior { })
        delay(50.milliseconds)

        system.terminate()

        assertThat(system.isTerminated).isTrue()
        assertThat(ref1.actorCell.state).isEqualTo(ActorState.STOPPED)
        assertThat(ref2.actorCell.state).isEqualTo(ActorState.STOPPED)
    }

    @Test
    fun `cannot spawn after termination`() = runBlocking {
        system.terminate()

        assertThrows<IllegalStateException> {
            system.spawn<String>("late", statelessBehavior { })
        }
    }
}
