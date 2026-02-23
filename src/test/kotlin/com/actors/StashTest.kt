package com.actors

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * ═══════════════════════════════════════════════════════════════════
 * STASH TESTS
 * ═══════════════════════════════════════════════════════════════════
 *
 * Verifies the Stash component:
 *   - Messages are buffered in FIFO order
 *   - unstashAll replays all messages through a behavior
 *   - Behavior switching during unstash works correctly
 *   - Capacity limits are enforced
 *   - clear discards all stashed messages
 */
class StashTest {

    @Test
    fun `stash buffers messages in FIFO order`() {
        val stash = Stash<String>(capacity = 10)

        stash.stash("first")
        stash.stash("second")
        stash.stash("third")

        assertThat(stash.size).isEqualTo(3)
        assertThat(stash.isEmpty).isFalse()
    }

    @Test
    fun `unstashAll replays messages in order`() = runBlocking {
        val stash = Stash<String>(capacity = 10)
        val received = mutableListOf<String>()

        stash.stash("A")
        stash.stash("B")
        stash.stash("C")

        val behavior = statelessBehavior<String> { msg ->
            received.add(msg)
        }

        stash.unstashAll(behavior)

        assertThat(received).containsExactly("A", "B", "C")
        assertThat(stash.isEmpty).isTrue()
    }

    @Test
    fun `unstashAll with behavior switching`() = runBlocking {
        val stash = Stash<String>(capacity = 10)
        val log = mutableListOf<String>()

        stash.stash("msg1")
        stash.stash("switch")
        stash.stash("msg2")

        val secondBehavior = statelessBehavior<String> { msg ->
            log.add("second:$msg")
        }

        val firstBehavior = behavior<String> { msg ->
            if (msg == "switch") {
                log.add("switching")
                secondBehavior
            } else {
                log.add("first:$msg")
                Behavior.same()
            }
        }

        stash.unstashAll(firstBehavior)

        assertThat(log).containsExactly("first:msg1", "switching", "second:msg2")
    }

    @Test
    fun `unstashAll with stopped behavior stops early`() = runBlocking {
        val stash = Stash<String>(capacity = 10)
        val received = mutableListOf<String>()

        stash.stash("proceed")
        stash.stash("stop")
        stash.stash("should-not-reach")

        val behavior = behavior<String> { msg ->
            received.add(msg)
            if (msg == "stop") Behavior.stopped() else Behavior.same()
        }

        val result = stash.unstashAll(behavior)

        assertThat(received).containsExactly("proceed", "stop")
        assertThat(Behavior.isStopped(result)).isTrue()
        assertThat(stash.isEmpty).isTrue() // Cleared on stop
    }

    @Test
    fun `capacity is enforced`() {
        val stash = Stash<String>(capacity = 3)

        stash.stash("1")
        stash.stash("2")
        stash.stash("3")

        assertThat(stash.isFull).isTrue()

        assertThatThrownBy { stash.stash("4") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("full")
    }

    @Test
    fun `clear discards all messages`() {
        val stash = Stash<String>(capacity = 10)

        stash.stash("A")
        stash.stash("B")
        stash.clear()

        assertThat(stash.isEmpty).isTrue()
        assertThat(stash.size).isEqualTo(0)
    }

    @Test
    fun `unstashAll on empty stash returns behavior unchanged`() = runBlocking {
        val stash = Stash<String>()
        val target = statelessBehavior<String> { }

        val result = stash.unstashAll(target)
        assertThat(result).isSameAs(target)
    }

    @Test
    fun `stash in actor initialization pattern`() = runBlocking {
        // Simulates the "stash while initializing, unstash when ready" pattern
        val stash = Stash<String>(capacity = 100)
        val processedInReady = mutableListOf<String>()

        // Phase 1: initializing — stash all messages except "config-loaded"
        stash.stash("request-1")
        stash.stash("request-2")
        stash.stash("request-3")

        // Phase 2: config loaded — transition to ready & unstash
        val readyBehavior = statelessBehavior<String> { msg ->
            processedInReady.add(msg)
        }

        stash.unstashAll(readyBehavior)

        assertThat(processedInReady).containsExactly("request-1", "request-2", "request-3")
    }
}
