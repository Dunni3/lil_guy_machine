package dev.lilguy.cli

import dev.lilguy.agent.KeyDoorPolicy
import dev.lilguy.agent.keyDoorScenario
import dev.lilguy.agent.runLockstep
import dev.lilguy.sim.Intent
import dev.lilguy.sim.World
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * Headless runner: the key-and-door room with the scripted policy, through the real agent loop,
 * printing every decision and world event. Exits non-zero if the room isn't solved.
 *
 * Args: --ticks N (default 2000).
 */
fun main(args: Array<String>) {
    val maxTicks = args.toList().zipWithNext().firstOrNull { it.first == "--ticks" }?.second?.toLongOrNull() ?: 2000L
    val (world, runtime) = keyDoorScenario(KeyDoorPolicy()) { d ->
        val intent = Json.encodeToString(Intent.serializer(), d.intent)
        println("t=%4d  decide  %-46s %s".format(d.tick, intent, d.rationale ?: "fallback: ${d.error}"))
    }
    println("lil guy machine cli: key-and-door room, model '${runtime.modelId}', max $maxTicks ticks")

    var printed = 0L
    val started = TimeSource.Monotonic.markNow()
    val endTick = runBlocking {
        runLockstep(world, runtime, maxTicks) {
            for (e in world.snapshot().events) {
                if (e.tick > printed) println("t=%4d  event   %s".format(e.tick, e.text))
            }
            printed = world.tick
        }
    }
    val wall = started.elapsedNow()

    val snapshot = world.snapshot()
    if (snapshot.completed) {
        val simSeconds = endTick.toDouble() / World.TICKS_PER_SECOND
        println("SOLVED in $endTick ticks (%.1f s of sim time, %d ms wall)".format(simSeconds, wall.inWholeMilliseconds))
    } else {
        println("NOT SOLVED after $endTick ticks; last status ${snapshot.agents.first().status}")
        exitProcess(1)
    }
}
