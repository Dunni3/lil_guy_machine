package dev.lilguy.sim

/**
 * What the agent layer's writers made for the bedroom's art, handed to [BedroomWorld.annotate]
 * from any thread and applied at the start of the next tick. The sim never waits for one: a piece
 * sells with or without its drawing, and a commission shows with or without its wording.
 */
sealed interface ArtNote {
    /** Piece [piece] was drawn: its [title] and the [tags] the drawing model claimed for it. */
    data class PieceDrawn(val piece: Int, val title: String, val tags: List<String>) : ArtNote

    /** Commission [commission] in the customer's own words. */
    data class CommissionWorded(val commission: Int, val request: String) : ArtNote
}
