package dev.lilguy.sim

import kotlinx.serialization.Serializable

/**
 * A letter someone outside the world sent him, through the mail hatch: [id] is the sender's store's
 * (the sim host's), so posting the same letter twice delivers it once. [from] is how the sender
 * signed it; [body] what they wrote. Both come from strangers on the web, so the sim says them as a
 * quoted letter and never as instructions.
 */
@Serializable
data class Letter(val id: Long, val from: String, val body: String)

/**
 * Opens the oldest letter waiting at the mail hatch: what it says goes into the finish event, so it
 * reaches his memory like anything else that happened. Locked while none is waiting.
 */
data object OpenMailOp : Op() {
    override val key get() = "open_mail"

    override fun lockReason(w: OpWorld, b: Bound) = w.mailLock()

    override fun gone(w: OpWorld, b: Bound) = w.mailLock()

    override fun finish(w: OpWorld, b: Bound) = w.openMail()

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else listOfNotNull(w.mailText())
}
