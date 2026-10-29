package dev.lilguy.cli

import dev.lilguy.sim.Intent
import dev.lilguy.sim.Target
import kotlinx.serialization.json.Json

/**
 * Headless entry point. Until the sim exists (milestone 1) it prints the action space as the
 * model will see it on the wire.
 */
fun main(args: Array<String>) {
    val examples: List<Intent> = listOf(
        Intent.MoveTo(Target.Entity("door")),
        Intent.PickUp("key"),
        Intent.Use("key", on = "door"),
        Intent.Say("found it"),
        Intent.Wait(20),
    )
    println("lil guy machine cli (args: ${args.joinToString(" ").ifEmpty { "none" }})")
    println("Intent wire format:")
    examples.forEach { println("  " + Json.encodeToString(Intent.serializer(), it)) }
}
