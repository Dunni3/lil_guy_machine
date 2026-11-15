package dev.lilguy.host

import dev.lilguy.protocol.Account
import dev.lilguy.protocol.Credentials
import dev.lilguy.protocol.LetterDraft
import dev.lilguy.protocol.MailError
import dev.lilguy.protocol.Mailbag
import dev.lilguy.protocol.SignedIn
import dev.lilguy.protocol.Wire
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.KSerializer

/**
 * Accounts and letters ([dev.lilguy.protocol.Credentials] and the rest in protocol's Mail.kt say the
 * calls). The session token is a bearer header, which a browser never adds by itself, so like the
 * admin's commands these need no CSRF check.
 */
internal fun Route.mail(accounts: Accounts, lives: Map<String, Life>) {
    post("/v1/accounts") {
        val c = call.body(Credentials.serializer()) ?: return@post
        call.refusing { call.signedIn(accounts.signUp(c.email, c.password), HttpStatusCode.Created) }
    }
    post("/v1/sessions") {
        val c = call.body(Credentials.serializer()) ?: return@post
        call.refusing { call.signedIn(accounts.signIn(c.email, c.password), HttpStatusCode.OK) }
    }
    delete("/v1/sessions") {
        call.token()?.let(accounts::signOut)
        call.respondText("", status = HttpStatusCode.NoContent)
    }
    get("/v1/account") {
        val who = call.who(accounts) ?: return@get
        call.json(Account.serializer(), Account(who.email))
    }
    get("/v1/lives/{life}/letters") {
        val who = call.who(accounts) ?: return@get
        val life = call.parameters["life"]!!.takeIf { it in lives } ?: return@get call.error("no such life", HttpStatusCode.NotFound)
        call.json(Mailbag.serializer(), accounts.mailbag(who, life))
    }
    post("/v1/lives/{life}/letters") {
        val who = call.who(accounts) ?: return@post
        val life = lives[call.parameters["life"]] ?: return@post call.error("no such life", HttpStatusCode.NotFound)
        val d = call.body(LetterDraft.serializer()) ?: return@post
        call.refusing {
            accounts.send(who, life.id, d.from, d.body)
            life.mailSent()
            call.json(Mailbag.serializer(), accounts.mailbag(who, life.id), HttpStatusCode.Created)
        }
    }
}

private fun ApplicationCall.token(): String? =
    request.headers[HttpHeaders.Authorization]?.trim()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

/** Who the bearer token signs in as; otherwise answers 401 and returns null. */
private suspend fun ApplicationCall.who(accounts: Accounts): Accounts.Who? {
    accounts.who(token())?.let { return it }
    response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    error("sign in first", HttpStatusCode.Unauthorized)
    return null
}

private suspend fun <T> ApplicationCall.body(s: KSerializer<T>): T? {
    val text = receiveText()
    if (text.length > MAX_BODY) return null.also { error("too long", HttpStatusCode.PayloadTooLarge) }
    return runCatching { Wire.decode(s, text) }.getOrElse { null.also { error("bad request", HttpStatusCode.BadRequest) } }
}

private suspend fun ApplicationCall.refusing(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Accounts.Refused) {
        error(e.message ?: "refused", HttpStatusCode.BadRequest)
    }
}

private suspend fun ApplicationCall.signedIn(s: Accounts.Session, status: HttpStatusCode) =
    json(SignedIn.serializer(), SignedIn(s.who.email, s.token, Accounts.iso(s.expires)), status)

private suspend fun <T> ApplicationCall.json(s: KSerializer<T>, v: T, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(Wire.encode(s, v), ContentType.Application.Json, status)

private suspend fun ApplicationCall.error(message: String, status: HttpStatusCode) = json(MailError.serializer(), MailError(message), status)

/** No call here needs more than a letter and some room. */
private const val MAX_BODY = 16_000
