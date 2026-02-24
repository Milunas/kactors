package com.actors

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ═══════════════════════════════════════════════════════════════════
 * TESTS: Actor Hierarchy — Supervision Trees
 * ═══════════════════════════════════════════════════════════════════
 *
 * Tests parent-child relationships, cascading stop, and child
 * spawning via ActorContext.
 *
 * Corresponds to the supervision tree structure from Erlang/OTP and
 * Akka Typed, implemented with Kotlin coroutines and channels.
 */
class ActorHierarchyTest {

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.create("hierarchy-test")
    }

    @AfterEach
    fun teardown() = runBlocking {
        if (!system.isTerminated) {
            system.terminate()
        }
    }

    // ─── Child Spawning via ActorContext ──────────────────────────

    sealed class ParentMsg {
        data class SpawnChild(val name: String) : ParentMsg()
        data class GetChildren(val replyTo: ActorRef<Set<String>>) : ParentMsg(), Request<Set<String>>
        data class TellChild(val name: String, val msg: String) : ParentMsg()
        data object StopSelf : ParentMsg()
    }

    @Test
    fun `parent can spawn child actors via context`() = runBlocking {
        val childStarted = AtomicBoolean(false)

        val parentBehavior = receive<ParentMsg> { ctx, msg ->
            when (msg) {
                is ParentMsg.SpawnChild -> {
                    ctx.spawn(msg.name, statelessBehavior<String> { _ ->
                        childStarted.set(true)
                    })
                    Behavior.same()
                }
                is ParentMsg.GetChildren -> {
                    msg.replyTo.tell(ctx.children.map { it.name }.toSet())
                    Behavior.same()
                }
                else -> Behavior.same()
            }
        }

        val parent = system.spawn("parent", parentBehavior)
        delay(50.milliseconds)

        // Spawn a child
        parent.tell(ParentMsg.SpawnChild("worker-1"))
        delay(100.milliseconds)

        // Verify child exists in parent's children
        val children: Set<String> = parent.ask(2.seconds) { ParentMsg.GetChildren(it) }
        assertThat(children).hasSize(1)
        assertThat(children.first()).endsWith("worker-1")
    }

    @Test
    fun `parent can spawn multiple children`() = runBlocking {
        val parentBehavior = receive<ParentMsg> { ctx, msg ->
            when (msg) {
                is ParentMsg.SpawnChild -> {
                    ctx.spawn(msg.name, statelessBehavior<String> { })
                    Behavior.same()
                }
                is ParentMsg.GetChildren -> {
                    msg.replyTo.tell(ctx.children.map { it.name }.toSet())
                    Behavior.same()
                }
                else -> Behavior.same()
            }
        }

        val parent = system.spawn("parent", parentBehavior)
        delay(50.milliseconds)

        parent.tell(ParentMsg.SpawnChild("child-a"))
        parent.tell(ParentMsg.SpawnChild("child-b"))
        parent.tell(ParentMsg.SpawnChild("child-c"))
        delay(200.milliseconds)

        val children: Set<String> = parent.ask(2.seconds) { ParentMsg.GetChildren(it) }
        assertThat(children).hasSize(3)
    }

    @Test
    fun `child names are scoped under parent path`() = runBlocking {
        val childRef = AtomicReference<ActorRef<*>>()

        val parentBehavior = receive<ParentMsg> { ctx, msg ->
            when (msg) {
                is ParentMsg.SpawnChild -> {
                    childRef.set(ctx.spawn(msg.name, statelessBehavior<String> { }))
                    Behavior.same()
                }
                else -> Behavior.same()
            }
        }

        val parent = system.spawn("parent", parentBehavior)
        delay(50.milliseconds)

        parent.tell(ParentMsg.SpawnChild("worker"))
        delay(100.milliseconds)

        // Child name = "system/parent/worker"
        assertThat(childRef.get().name).isEqualTo("hierarchy-test/parent/worker")
    }

    // ─── Cascading Stop ──────────────────────────────────────────

    @Test
    fun `stopping parent stops all children`() = runBlocking {
        val childStopped = AtomicBoolean(false)
        val childRef = AtomicReference<ActorRef<*>>()

        val parentBehavior = setup<ParentMsg> { ctx ->
            val child = ctx.spawn("child", lifecycleBehavior<String>(
                onStop = { childStopped.set(true) }
            ) { Behavior.same() })
            childRef.set(child)

            behavior { msg ->
                when (msg) {
                    is ParentMsg.StopSelf -> Behavior.stopped()
                    else -> Behavior.same()
                }
            }
        }

        val parent = system.spawn("parent", parentBehavior)
        delay(100.milliseconds)

        // Verify child is alive
        assertThat(childRef.get().isAlive).isTrue()

        // Stop parent → should cascade to child
        parent.tell(ParentMsg.StopSelf)
        delay(300.milliseconds)

        assertThat(parent.actorCell.state).isEqualTo(ActorState.STOPPED)
        assertThat(childStopped.get()).isTrue()
    }

    @Test
    fun `system terminate stops entire hierarchy`() = runBlocking {
        val grandchildStopped = AtomicBoolean(false)

        val parentBehavior = setup<String> { ctx ->
            ctx.spawn("child", setup<String> { childCtx ->
                childCtx.spawn("grandchild", lifecycleBehavior<String>(
                    onStop = { grandchildStopped.set(true) }
                ) { Behavior.same() })
                statelessBehavior { }
            })
            statelessBehavior { }
        }

        system.spawn("root", parentBehavior)
        delay(200.milliseconds)

        system.terminate()

        assertThat(grandchildStopped.get()).isTrue()
    }

    // ─── Context.stop(child) ─────────────────────────────────────

    sealed class StopChildMsg {
        data class SpawnChild(val name: String) : StopChildMsg()
        data class StopChild(val name: String) : StopChildMsg()
        data class GetChildren(val replyTo: ActorRef<Int>) : StopChildMsg(), Request<Int>
    }

    @Test
    fun `parent can stop specific child via context`() = runBlocking {
        val childRefs = mutableMapOf<String, ActorRef<String>>()

        val parentBehavior = receive<StopChildMsg> { ctx, msg ->
            when (msg) {
                is StopChildMsg.SpawnChild -> {
                    childRefs[msg.name] = ctx.spawn(msg.name, statelessBehavior<String> { })
                    Behavior.same()
                }
                is StopChildMsg.StopChild -> {
                    childRefs[msg.name]?.let { ctx.stop(it) }
                    Behavior.same()
                }
                is StopChildMsg.GetChildren -> {
                    msg.replyTo.tell(ctx.children.size)
                    Behavior.same()
                }
            }
        }

        val parent = system.spawn("parent", parentBehavior)
        delay(50.milliseconds)

        parent.tell(StopChildMsg.SpawnChild("a"))
        parent.tell(StopChildMsg.SpawnChild("b"))
        delay(100.milliseconds)

        val before: Int = parent.ask(2.seconds) { StopChildMsg.GetChildren(it) }
        assertThat(before).isEqualTo(2)

        // Stop one child
        parent.tell(StopChildMsg.StopChild("a"))
        delay(200.milliseconds)

        val after: Int = parent.ask(2.seconds) { StopChildMsg.GetChildren(it) }
        assertThat(after).isEqualTo(1)
    }
}
