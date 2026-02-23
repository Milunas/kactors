package com.actors

import kotlinx.coroutines.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ═══════════════════════════════════════════════════════════════════
 * TIMER SCHEDULER TESTS
 * ═══════════════════════════════════════════════════════════════════
 *
 * Verifies the TimerScheduler component:
 *   - Single-shot timers fire exactly once after delay
 *   - Periodic timers fire repeatedly at interval
 *   - Timer cancellation works correctly
 *   - Key replacement cancels the previous timer
 *   - cancelAll stops everything
 */
class TimerSchedulerTest {

    private lateinit var system: ActorSystem
    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setup() {
        system = ActorSystem.create("timer-test")
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @AfterEach
    fun teardown() = runBlocking {
        scope.cancel()
        system.terminate()
    }

    @Test
    fun `single timer fires after delay`() = runBlocking {
        val received = AtomicInteger(0)
        val ref = system.spawn<String>("timer-target", statelessBehavior { _ ->
            received.incrementAndGet()
        })

        val timers = TimerScheduler<String>(scope)
        timers.startSingleTimer("test", "hello", 100.milliseconds, ref)

        assertThat(timers.isTimerActive("test")).isTrue()

        delay(200.milliseconds)

        assertThat(received.get()).isEqualTo(1)
        assertThat(timers.isTimerActive("test")).isFalse()
    }

    @Test
    fun `periodic timer fires multiple times`() = runBlocking {
        val received = AtomicInteger(0)
        val ref = system.spawn<String>("periodic-target", statelessBehavior { _ ->
            received.incrementAndGet()
        })

        val timers = TimerScheduler<String>(scope)
        timers.startPeriodicTimer("tick", "tick", 50.milliseconds, ref,
            initialDelay = 50.milliseconds)

        delay(280.milliseconds)
        timers.cancel("tick")

        // Should have fired ~5 times (50ms initial + 4 intervals at 50ms each = 250ms)
        val count = received.get()
        assertThat(count).isBetween(3, 7)
    }

    @Test
    fun `cancel stops timer from firing`() = runBlocking {
        val received = AtomicInteger(0)
        val ref = system.spawn<String>("cancel-target", statelessBehavior { _ ->
            received.incrementAndGet()
        })

        val timers = TimerScheduler<String>(scope)
        timers.startSingleTimer("cancelme", "msg", 200.milliseconds, ref)

        assertThat(timers.isTimerActive("cancelme")).isTrue()
        timers.cancel("cancelme")
        assertThat(timers.isTimerActive("cancelme")).isFalse()

        delay(300.milliseconds)
        assertThat(received.get()).isEqualTo(0)
    }

    @Test
    fun `key replacement cancels previous timer`() = runBlocking {
        val received = AtomicInteger(0)
        val ref = system.spawn<String>("replace-target", statelessBehavior { msg ->
            if (msg == "second") received.incrementAndGet()
        })

        val timers = TimerScheduler<String>(scope)

        // Start first timer (should be cancelled)
        timers.startSingleTimer("key", "first", 200.milliseconds, ref)

        delay(50.milliseconds)

        // Replace with second timer
        timers.startSingleTimer("key", "second", 100.milliseconds, ref)

        delay(200.milliseconds)

        // Only "second" should have fired
        assertThat(received.get()).isEqualTo(1)
    }

    @Test
    fun `cancelAll stops all timers`() = runBlocking {
        val received = AtomicInteger(0)
        val ref = system.spawn<String>("cancelall-target", statelessBehavior { _ ->
            received.incrementAndGet()
        })

        val timers = TimerScheduler<String>(scope)
        timers.startSingleTimer("a", "msg", 200.milliseconds, ref)
        timers.startSingleTimer("b", "msg", 200.milliseconds, ref)
        timers.startPeriodicTimer("c", "msg", 100.milliseconds, ref)

        assertThat(timers.activeCount).isEqualTo(3)

        timers.cancelAll()
        assertThat(timers.activeCount).isEqualTo(0)

        delay(300.milliseconds)
        assertThat(received.get()).isEqualTo(0)
    }
}
