package com.actors

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ═══════════════════════════════════════════════════════════════════
 * CONCURRENT STRESS TESTS: Actor System Under Load
 * ═══════════════════════════════════════════════════════════════════
 *
 * Complements Lincheck (which tests simplified sequential models)
 * by driving the REAL actor implementation with concurrent workloads.
 * Invariants from all three TLA+ specs are checked throughout.
 */
class ActorConcurrencyTest {

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.create("concurrency-test")
    }

    @AfterEach
    fun teardown() = runBlocking {
        if (!system.isTerminated) {
            system.terminate()
        }
    }

    /**
     * Multiple senders flood a single actor's mailbox concurrently.
     * Verifies: message conservation (ActorMailbox.tla INV-2)
     */
    @RepeatedTest(5)
    fun `concurrent sends preserve message count`() = runBlocking {
        val receivedCount = AtomicInteger(0)
        val totalMessages = 1000
        val senderCount = 4
        val messagesPerSender = totalMessages / senderCount

        val ref = system.spawn<Int>("flood-actor", statelessBehavior { _ ->
            receivedCount.incrementAndGet()
        }, mailboxCapacity = totalMessages)

        val barrier = CyclicBarrier(senderCount)

        val senderThreads = (1..senderCount).map { senderId ->
            Thread {
                barrier.await()
                runBlocking {
                    repeat(messagesPerSender) { i ->
                        ref.tell(senderId * 1000 + i)
                    }
                }
            }.also { it.start() }
        }

        senderThreads.forEach { it.join() }
        delay(500.milliseconds)

        // ActorMailbox.tla INV-2: MessageConservation
        assertThat(receivedCount.get()).isEqualTo(totalMessages)
    }

    /**
     * Multiple actors processing messages concurrently.
     * Verifies: actor isolation (no shared state corruption)
     */
    @RepeatedTest(3)
    fun `multiple actors process independently`() = runBlocking {
        val actorCount = 10
        val messagesPerActor = 100
        val results = ConcurrentLinkedQueue<Pair<String, Int>>()

        val refs = (1..actorCount).map { id ->
            val counter = AtomicInteger(0)
            system.spawn<String>("actor-$id", statelessBehavior { msg ->
                val count = counter.incrementAndGet()
                results.add("actor-$id" to count)
            })
        }

        // Send messages to all actors concurrently
        refs.forEach { ref ->
            repeat(messagesPerActor) { i ->
                ref.tell("msg-$i")
            }
        }

        delay(1.seconds)

        // Each actor should have processed exactly messagesPerActor
        val grouped = results.groupBy({ it.first }, { it.second })
        assertThat(grouped).hasSize(actorCount)
        grouped.values.forEach { counts ->
            assertThat(counts).hasSize(messagesPerActor)
            assertThat(counts.max()).isEqualTo(messagesPerActor)
        }
    }

    // Message type for ask pattern test
    sealed class MathMsg {
        data class Dbl(val n: Int, override val replyTo: ActorRef<Int>) : MathMsg(), Request<Int>
    }

    /**
     * Concurrent ask (request-reply) operations.
     * Verifies: RequestReply.tla invariants under real concurrency
     */
    @Test
    fun `concurrent ask pattern returns correct replies`() = runBlocking {
        val ref = system.spawn<MathMsg>("math-actor", statelessBehavior { msg ->
            when (msg) {
                is MathMsg.Dbl -> msg.replyTo.tell(msg.n * 2)
            }
        })

        delay(50.milliseconds)

        val results = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val askCount = 20

        val threads = (1..askCount).map { n ->
            Thread {
                runBlocking {
                    val result: Int = ref.ask(timeout = 5.seconds) { replyTo ->
                        MathMsg.Dbl(n, replyTo)
                    }
                    results.add(n to result)
                }
            }.also { it.start() }
        }

        threads.forEach { it.join() }

        // Each reply should be double the input
        assertThat(results).hasSize(askCount)
        results.forEach { (input, output) ->
            assertThat(output).isEqualTo(input * 2)
        }
    }

    /**
     * Actor restart under concurrent message load.
     * Verifies: ActorLifecycle.tla restart budget invariants
     */
    @Test
    fun `restart under concurrent load preserves invariants`() = runBlocking {
        val processedAfterRestart = AtomicInteger(0)
        val failOnFirst = AtomicInteger(0)

        val behavior = statelessBehavior<String> { msg ->
            if (msg == "fail" && failOnFirst.getAndIncrement() < 1) {
                throw RuntimeException("Intentional failure")
            }
            processedAfterRestart.incrementAndGet()
        }

        val ref = system.spawn("resilient-actor", behavior,
            supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 3))

        delay(50.milliseconds)

        // Send failure trigger followed by normal messages
        ref.tell("fail")
        delay(100.milliseconds)

        repeat(10) { ref.tell("ok-$it") }
        delay(300.milliseconds)

        // Actor should have restarted and processed subsequent messages
        assertThat(ref.actorCell.state).isEqualTo(ActorState.RUNNING)
        assertThat(ref.actorCell.restartCount).isLessThanOrEqualTo(3)
        assertThat(processedAfterRestart.get()).isGreaterThan(0)

        // All TLA+ lifecycle invariants
        ref.actorCell.checkAllInvariants()
    }
}
