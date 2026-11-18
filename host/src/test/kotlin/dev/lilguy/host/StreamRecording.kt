package dev.lilguy.host

import dev.lilguy.agent.live.ModelSetup
import dev.lilguy.agent.live.RunSettings
import dev.lilguy.decision.RandomPicker
import dev.lilguy.protocol.Command
import dev.lilguy.protocol.Delta
import dev.lilguy.protocol.Logs
import dev.lilguy.protocol.PROTOCOL_VERSION
import dev.lilguy.protocol.ServerMessage
import dev.lilguy.protocol.Snapshot
import dev.lilguy.protocol.Welcome
import dev.lilguy.protocol.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Writes a real life's stream to the file in `args[0]`, one message a line in the order a viewer
 * gets them, with the host's own whole state at the last seq as the last line: what the web client's
 * test replays (`web/src/stream/state.test.ts`). The life paints, so the easel and every log move.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val setup = ModelSetup.scripted(days = null).copy(painter = { RandomPicker(5.milliseconds, seed = 7) })
    val life = Life("test", setup, db = null, frame = 5.milliseconds, publishEvery = 10.milliseconds)
    life.start(scope)
    life.command(Command.Configure(StateReader.settings(RunSettings()).copy(speed = 60, days = null)))
    life.command(Command.Start)
    val lines = ArrayList<String>()
    fun say(m: ServerMessage) { lines += Wire.encode(m) }
    say(Welcome(PROTOCOL_VERSION, listOfNotNull(life.summary), Logs.CAPS))
    var seq = -1L
    val viewer = launch {
        life.messages
            .onSubscription { life.latest!!.let { say(it.content); say(Snapshot(it.state)); seq = it.state.seq } }
            .collect { m -> if (m !is Delta || m.seq > seq) { say(m); if (m is Delta) seq = m.seq } }
    }
    withTimeout(60.seconds) {
        while (true) {
            val s = life.latest!!.state
            if (s.logs.decisions.size >= 8 && s.logs.transactions.isNotEmpty() && s.fast.tick > 1500) break
            delay(20)
        }
    }
    life.command(Command.Pause)
    // Paused, the state settles; memory writes can still finish, so wait until the viewer has seen the latest.
    withTimeout(10.seconds) { while (life.latest!!.state.seq != seq) delay(50) }
    viewer.cancelAndJoin()
    val final = life.latest!!.state
    check(final.seq == seq) { "the host moved on while the recording stopped; run it again" }
    lines += Wire.encode(Snapshot(final))
    File(args[0]).apply { parentFile?.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
    life.stop()
    scope.cancel()
    println("${lines.size} messages to ${args[0]}")
    kotlin.system.exitProcess(0)
}
