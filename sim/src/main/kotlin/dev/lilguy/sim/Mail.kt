package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A letter someone outside the world sent him, through the mail hatch: [id] is the sender's store's
 * (the sim host's), so posting the same letter twice delivers it once. [from] is how the sender
 * signed it; [body] what they wrote. [sender] is who wrote it on the host (an account id, never an
 * email), so he can write back to the same person whatever name they sign; null for none, and then
 * he can't. Both come from strangers on the web, so the sim says them as a quoted letter and never
 * as instructions. [tick]: when he opened it, once he has.
 */
@Serializable
data class Letter(val id: Long, val from: String, val body: String, val sender: Long? = null, val tick: Long? = null)

/**
 * A letter he wrote and put through the hatch: [seq] counts his letters in this world, so the host
 * stores each once; [to] is the sender it goes to and [name] what he called them. [inReplyTo] is the
 * newest letter from them he had opened when he wrote. [day] and [time] are the game's.
 */
@Serializable
data class OutLetter(
    val seq: Int,
    val to: Long,
    val name: String,
    val body: String,
    val tick: Long,
    val day: Int,
    val time: String,
    @SerialName("in_reply_to") val inReplyTo: Long? = null,
)

/**
 * Someone he can write to, because they have written to him: [name] is how they signed their newest
 * letter; [letters] go both ways, oldest first. [unanswered]: their letters since his last to them,
 * the ones a reply answers. [blocked]: why he can't write to them now (rules.yaml `letters`), null
 * when he can.
 */
data class Correspondent(
    val sender: Long,
    val name: String,
    val letters: List<Exchanged>,
    val unanswered: List<Letter>,
    val blocked: String?,
) {
    /** One letter between them, [fromHim] or to him, at [tick] ("d3 14:20" as [stamp]). */
    data class Exchanged(val fromHim: Boolean, val body: String, val tick: Long, val stamp: String)
}

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

/**
 * Puts the letter he wrote through the mail hatch, to someone who has written to him. The letter
 * comes from the verb's `write: { with: letter, timing: before }`, which the agent layer stages
 * through [BedroomWorld.stage]: who it's to and what it says. Locked while there's nobody he can
 * write to (rules.yaml `letters`). With no letter staged, or one to someone he can't write to, the
 * verb fails.
 */
data object PostLetterOp : Op() {
    override val key get() = "post_letter"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        if (c.letters == null) "post_letter: the rules have no `letters`" else null,
        if (verb.effects.none { it is WriteOp && it.with == "letter" && it.timing == Timing.Before }) "post_letter: the verb needs a `write: { with: letter, timing: before }` to write the letter" else null,
    )

    override fun lockReason(w: OpWorld, b: Bound) = w.letterLock()

    override fun gone(w: OpWorld, b: Bound) = w.letterFailed(b.verb.id)

    override fun finish(w: OpWorld, b: Bound) = w.postLetter(b.verb.id)

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else listOfNotNull(w.letterText())
}

/**
 * Letters he writes: only to someone who has written to him, at most [perDay] a game day to each,
 * and at most [unanswered] in a row to someone before they write back, so nobody gets a pile.
 * [maxLength]: the longest a letter of his can be, the same as theirs.
 */
@Serializable
data class LetterRules(
    @SerialName("per_day") val perDay: Int = 1,
    val unanswered: Int = 2,
    @SerialName("max_length") val maxLength: Int = 1000,
)
