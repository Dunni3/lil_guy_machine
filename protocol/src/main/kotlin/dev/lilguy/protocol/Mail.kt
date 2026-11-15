package dev.lilguy.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Accounts and letters: anyone may make an account (an email and a password) and write him letters,
 * which the host puts through his mail hatch. Over plain HTTP, not the stream:
 * - `POST /v1/accounts` with [Credentials] makes one; `POST /v1/sessions` with [Credentials] signs in;
 *   both answer [SignedIn], whose token goes on later calls as `Authorization: Bearer <token>`;
 * - `GET /v1/account` is the [Account] signed in; `DELETE /v1/sessions` signs out;
 * - `POST /v1/lives/{life}/letters` with a [LetterDraft] sends one; `GET /v1/lives/{life}/letters` is your [Mailbag],
 *   with the letters he wrote you.
 * A refusal is a [MailError] with a 4xx status.
 */

/** An email and a password, to make an account or sign in. */
@Serializable
data class Credentials(val email: String, val password: String)

/** Signed in: [token] is the session's, for the bearer header, until [expiresAt] (ISO). */
@Serializable
data class SignedIn(val email: String, val token: String, @SerialName("expires_at") val expiresAt: String)

/** Who is signed in. */
@Serializable
data class Account(val email: String)

/** A letter to send: [from] is how it's signed, all he sees of who wrote it (never the email). */
@Serializable
data class LetterDraft(val from: String, val body: String)

/**
 * A letter you sent: [status] is `waiting` (not through the hatch yet: no run is going), `delivered`
 * (at the hatch), or `opened` (he has read it). Times are ISO.
 */
@Serializable
data class SentLetter(
    val id: Long,
    val from: String,
    val body: String,
    @SerialName("sent_at") val sentAt: String,
    val status: String,
    @SerialName("opened_at") val openedAt: String?,
)

/**
 * A letter he wrote you, once he has opened one of yours: [to] is the name he wrote it to (how you
 * signed), [writtenAt] when he posted it (ISO), [day] and [time] the game's. [inReplyTo] is your
 * newest letter he had opened when he wrote it.
 */
@Serializable
data class HisLetter(
    val id: Long,
    val to: String,
    val body: String,
    @SerialName("written_at") val writtenAt: String,
    val day: Int,
    val time: String,
    @SerialName("in_reply_to") val inReplyTo: Long?,
)

/**
 * Your letters to a life and [replies], his to you, each newest first, and how many more you may send
 * today ([perDay] in any 24 hours).
 */
@Serializable
data class Mailbag(
    val letters: List<SentLetter>,
    val replies: List<HisLetter>,
    @SerialName("per_day") val perDay: Int,
    val left: Int,
    @SerialName("max_length") val maxLength: Int,
)

/** Why an account or letter call was refused. */
@Serializable
data class MailError(val error: String)
