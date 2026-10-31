package dev.lilguy.sim

/**
 * What the agent layer's writers made for the bedroom's art apart from drawings (those come
 * through [BedroomWorld.deliver]), handed to [BedroomWorld.annotate] from any thread and applied at
 * the start of the next tick. The sim never waits for one: a commission shows with or without its wording.
 */
sealed interface ArtNote {
    /** Commission [commission] in the customer's own words. */
    data class CommissionWorded(val commission: Int, val request: String) : ArtNote
}
