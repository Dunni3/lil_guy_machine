package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The action space. This one sealed type is the contract with decision models: it generates
 * the JSON schema they are given and parses what they send back. Keep it flat.
 */
@Serializable
sealed interface Intent {
    @Serializable @SerialName("move_to")
    data class MoveTo(val target: Target) : Intent

    @Serializable @SerialName("pick_up")
    data class PickUp(val itemId: String) : Intent

    /** Key-and-door only: the bedroom does it through a verb's arguments ([Do]). */
    @Serializable @SerialName("use")
    data class Use(val itemId: String, val on: String? = null) : Intent

    @Serializable @SerialName("say")
    data class Say(val text: String) : Intent

    @Serializable @SerialName("wait")
    data class Wait(val ticks: Int) : Intent

    /**
     * Do a verb by id, such as "sketch" or "sleep". [what] and [on] fill its argument slots with
     * items he owns ("read", what = "novel"); one left out is filled with the first owned match.
     * The sim walks the character to its spot.
     */
    @Serializable @SerialName("do")
    data class Do(val activity: String, val what: String? = null, val on: String? = null) : Intent

    /** Buy an item from the shop by id. The sim walks the character to where buying happens. */
    @Serializable @SerialName("buy")
    data class Buy(val item: String) : Intent

    /**
     * Stop where he is and think. The sim only spends the time; the agent loop has a text model
     * write the thought (what he wants to do next and to achieve) into his memory.
     */
    @Serializable @SerialName("think")
    data object Think : Intent
}

@Serializable
sealed interface Target {
    @Serializable @SerialName("tile")
    data class Tile(val x: Int, val y: Int) : Target

    @Serializable @SerialName("entity")
    data class Entity(val id: String) : Target
}

/** Lifecycle of a submitted intent. Results go into the next observation. */
@Serializable
sealed interface IntentStatus {
    @Serializable @SerialName("submitted") data object Submitted : IntentStatus
    @Serializable @SerialName("validated") data object Validated : IntentStatus
    @Serializable @SerialName("executing") data object Executing : IntentStatus
    @Serializable @SerialName("succeeded") data object Succeeded : IntentStatus
    @Serializable @SerialName("failed") data class Failed(val reason: String) : IntentStatus
    @Serializable @SerialName("interrupted") data object Interrupted : IntentStatus
}

interface IntentHandle {
    val intent: Intent
    val status: IntentStatus
}
