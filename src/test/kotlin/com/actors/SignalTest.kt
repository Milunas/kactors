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
 * TESTS: Signals — Lifecycle Events & DeathWatch
 * ═══════════════════════════════════════════════════════════════════
 *
 * Tests signal delivery: PreStart, PostStop, Terminated, ChildFailed.
 * Covers the DeathWatch pattern (context.watch/unwatch) and lifecycle
 * signal handling via Behavior.onSignal.
 */
class SignalTest {

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.create("signal-test")
    }

    @AfterEach
    fun teardown() = runBlocking {
        if (!system.isTerminated) {
            system.terminate()
        }
    }

    // ─── PreStart / PostStop Signals ─────────────────────────────

    @Test
    fun `PreStart signal fires before first message`() = runBlocking {
        val events = CopyOnWriteArrayList<String>()

        val behavior = receive<String> { _, msg ->
            events.add("msg:$msg")
            Behavior.same()
        }.onSignal { _, signal ->
            when (signal) {
                is Signal.PreStart -> { events.add("prestart"); Behavior.same() }
                else -> Behavior.same()
            }
        }

        val ref = system.spawn("signal-actor", behavior)
        delay(50.milliseconds)

        ref.tell("hello")
        delay(100.milliseconds)

        assertThat(events).containsExactly("prestart", "msg:hello")
    }

    @Test
    fun `PostStop signal fires on actor shutdown`() = runBlocking {
        val postStopFired = AtomicBoolean(false)

        val behavior = behavior<String> { msg ->
            if (msg == "stop") Behavior.stopped() else Behavior.same()
        }.onSignal { _, signal ->
            when (signal) {
                is Signal.PostStop -> { postStopFired.set(true); Behavior.same() }
                else -> Behavior.same()
            }
        }

        val ref = system.spawn("stop-signal", behavior)
        delay(50.milliseconds)

        ref.tell("stop")
        delay(200.milliseconds)

        assertThat(postStopFired.get()).isTrue()
        assertThat(ref.actorCell.state).isEqualTo(ActorState.STOPPED)
    }

    @Test
    fun `lifecycleBehavior DSL delivers PreStart and PostStop`() = runBlocking {
        val started = AtomicInteger(0)
        val stopped = AtomicInteger(0)

        val behavior = lifecycleBehavior<String>(
            onStart = { started.incrementAndGet() },
            onStop = { stopped.incrementAndGet() }
        ) { msg ->
            if (msg == "stop") Behavior.stopped() else Behavior.same()
        }

        val ref = system.spawn("lifecycle-signal", behavior)
        delay(50.milliseconds)

        assertThat(started.get()).isEqualTo(1)

        ref.tell("stop")
        delay(200.milliseconds)

        assertThat(stopped.get()).isEqualTo(1)
    }

    // ─── Setup Behavior ──────────────────────────────────────────

    @Test
    fun `setup behavior runs factory once on start`() = runBlocking {
        val setupCount = AtomicInteger(0)
        val messages = CopyOnWriteArrayList<String>()

        val behavior = setup<String> { ctx ->
            setupCount.incrementAndGet()
            ctx.log.info("Setup ran for ${ctx.name}")

            statelessBehavior { msg ->
                messages.add(msg)
            }
        }

        val ref = system.spawn("setup-actor", behavior)
        delay(50.milliseconds)

        ref.tell("a")
        ref.tell("b")
        delay(100.milliseconds)

        assertThat(setupCount.get()).isEqualTo(1)
        assertThat(messages).containsExactly("a", "b")
    }

    @Test
    fun `setup behavior provides access to self`() = runBlocking {
        val selfName = AtomicReference<String>()

        val behavior = setup<String> { ctx ->
            selfName.set(ctx.self.name)
            statelessBehavior { }
        }

        system.spawn("self-aware", behavior)
        delay(100.milliseconds)

        assertThat(selfName.get()).isEqualTo("signal-test/self-aware")
    }

    // ─── DeathWatch: context.watch() → Terminated signal ─────────

    @Test
    fun `watcher receives Terminated when watched actor dies`() = runBlocking {
        val terminatedRef = AtomicReference<ActorRef<*>>()

        // Actor that will be watched
        val watchedBehavior = behavior<String> { msg ->
            if (msg == "die") Behavior.stopped() else Behavior.same()
        }

        // Watcher: watches the other actor, receives Terminated
        val watcherBehavior = setup<String> { ctx ->
            // Will be told which actor to watch via a message
            behavior<String> { msg ->
                Behavior.same()
            }.onSignal { _, signal ->
                when (signal) {
                    is Signal.Terminated -> {
                        terminatedRef.set(signal.ref)
                        Behavior.same()
                    }
                    else -> Behavior.same()
                }
            }
        }

        val watched = system.spawn("watched", watchedBehavior)
        val watcher = system.spawn("watcher", watcherBehavior)
        delay(100.milliseconds)

        // Set up watch
        watcher.actorCell.watch(watched.actorCell)

        // Kill the watched actor
        watched.tell("die")
        delay(300.milliseconds)

        assertThat(terminatedRef.get()).isNotNull
        assertThat(terminatedRef.get()!!.name).isEqualTo("signal-test/watched")
    }

    @Test
    fun `unwatch prevents Terminated signal`() = runBlocking {
        val terminatedReceived = AtomicBoolean(false)

        val watchedBehavior = behavior<String> { msg ->
            if (msg == "die") Behavior.stopped() else Behavior.same()
        }

        val watcherBehavior = behavior<String> { _, _ ->
            Behavior.same()
        }.onSignal { _, signal ->
            when (signal) {
                is Signal.Terminated -> {
                    terminatedReceived.set(true)
                    Behavior.same()
                }
                else -> Behavior.same()
            }
        }

        val watched = system.spawn("watched", watchedBehavior)
        val watcher = system.spawn("watcher", watcherBehavior)
        delay(100.milliseconds)

        // Watch then unwatch
        watcher.actorCell.watch(watched.actorCell)
        watcher.actorCell.unwatch(watched.actorCell)

        // Kill the watched actor
        watched.tell("die")
        delay(300.milliseconds)

        assertThat(terminatedReceived.get()).isFalse()
    }

    @Test
    fun `watching already-dead actor delivers Terminated immediately`() = runBlocking {
        val terminatedReceived = AtomicBoolean(false)

        val dyingBehavior = behavior<String> { msg ->
            if (msg == "die") Behavior.stopped() else Behavior.same()
        }

        val watcherBehavior = behavior<String> { _, _ ->
            Behavior.same()
        }.onSignal { _, signal ->
            when (signal) {
                is Signal.Terminated -> {
                    terminatedReceived.set(true)
                    Behavior.same()
                }
                else -> Behavior.same()
            }
        }

        val watched = system.spawn("dead-actor", dyingBehavior)
        val watcher = system.spawn("watcher", watcherBehavior)
        delay(50.milliseconds)

        // Kill watched first
        watched.tell("die")
        delay(200.milliseconds)
        assertThat(watched.actorCell.state).isEqualTo(ActorState.STOPPED)

        // Now watch the already-dead actor
        watcher.actorCell.watch(watched.actorCell)
        delay(200.milliseconds)

        assertThat(terminatedReceived.get()).isTrue()
    }

    // ─── DeathWatch via ActorContext ─────────────────────────────

    sealed class WatcherMsg {
        data class Watch(val ref: ActorRef<*>) : WatcherMsg()
        data class GetTerminated(val replyTo: ActorRef<List<String>>) : WatcherMsg(), Request<List<String>>
    }

    @Test
    fun `context watch and Terminated signal integration`() = runBlocking {
        val terminatedActors = CopyOnWriteArrayList<String>()

        val watcherBehavior = receive<WatcherMsg> { ctx, msg ->
            when (msg) {
                is WatcherMsg.Watch -> {
                    @Suppress("UNCHECKED_CAST")
                    ctx.watch(msg.ref as ActorRef<Any>)
                    Behavior.same()
                }
                is WatcherMsg.GetTerminated -> {
                    msg.replyTo.tell(terminatedActors.toList())
                    Behavior.same()
                }
            }
        }.onSignal { _, signal ->
            when (signal) {
                is Signal.Terminated -> {
                    terminatedActors.add(signal.ref.name)
                    Behavior.same()
                }
                else -> Behavior.same()
            }
        }

        val target = system.spawn("target", behavior<String> { msg ->
            if (msg == "die") Behavior.stopped() else Behavior.same()
        })

        val watcher = system.spawn("watcher", watcherBehavior)
        delay(100.milliseconds)

        watcher.tell(WatcherMsg.Watch(target))
        delay(50.milliseconds)

        target.tell("die")
        delay(300.milliseconds)

        val terminated: List<String> = watcher.ask(2.seconds) { WatcherMsg.GetTerminated(it) }
        assertThat(terminated).containsExactly("signal-test/target")
    }

    // ─── ChildFailed Signal ──────────────────────────────────────

    @Test
    fun `parent receives ChildFailed signal when child throws`() = runBlocking {
        val childFailedReceived = AtomicBoolean(false)
        val failureCause = AtomicReference<String>()

        val parentBehavior = setup<String> { ctx ->
            ctx.spawn("failing-child", statelessBehavior<String> { msg ->
                if (msg == "crash") throw RuntimeException("Child explosion!")
            }, supervisorStrategy = SupervisorStrategy.stop())

            behavior<String> { _, _ -> Behavior.same() }
                .onSignal { _, signal ->
                    when (signal) {
                        is Signal.ChildFailed -> {
                            childFailedReceived.set(true)
                            failureCause.set(signal.cause.message)
                            Behavior.same()
                        }
                        else -> Behavior.same()
                    }
                }
        }

        val parent = system.spawn("parent", parentBehavior)
        delay(100.milliseconds)

        // Get reference to child and make it crash
        val children = parent.actorCell.childRefs
        assertThat(children).hasSize(1)
        val child = children.first()

        @Suppress("UNCHECKED_CAST")
        (child as ActorRef<String>).tell("crash")
        delay(300.milliseconds)

        assertThat(childFailedReceived.get()).isTrue()
        assertThat(failureCause.get()).isEqualTo("Child explosion!")
    }

    // ─── Context-Aware Behavior (receive DSL) ────────────────────

    sealed class ContextMsg {
        data class WhoAmI(val replyTo: ActorRef<String>) : ContextMsg(), Request<String>
        data class SpawnAndReply(val replyTo: ActorRef<String>) : ContextMsg(), Request<String>
    }

    @Test
    fun `receive DSL provides context with self reference`() = runBlocking {
        val behavior = receive<ContextMsg> { ctx, msg ->
            when (msg) {
                is ContextMsg.WhoAmI -> {
                    msg.replyTo.tell(ctx.self.name)
                    Behavior.same()
                }
                is ContextMsg.SpawnAndReply -> {
                    val child = ctx.spawn("ephemeral", statelessBehavior<String> { })
                    msg.replyTo.tell(child.name)
                    Behavior.same()
                }
            }
        }

        val ref = system.spawn("ctx-actor", behavior)
        delay(50.milliseconds)

        val name: String = ref.ask(2.seconds) { ContextMsg.WhoAmI(it) }
        assertThat(name).isEqualTo("signal-test/ctx-actor")

        val childName: String = ref.ask(2.seconds) { ContextMsg.SpawnAndReply(it) }
        assertThat(childName).isEqualTo("signal-test/ctx-actor/ephemeral")
    }
}
