package dev.lilguy.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.OAuthAccessTokenResponse
import io.ktor.server.auth.OAuthServerSettings
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.oauth
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.param
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.CookieSessionBuilder
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.util.NonceManager
import io.ktor.util.hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Admin sign-in with GitHub (the OAuth web flow, no scopes: `/user`'s public profile has the id).
 * - `GET /auth/login` redirects to GitHub. The `state` it sends also goes in a short-lived signed
 *   cookie, so the sign-in can only finish in the browser that started it.
 * - `GET /auth/callback`: Ktor's OAuth plugin swaps the code for a token; then the state must match
 *   that cookie, the token reads `/user` once and is dropped, and the browser gets a session cookie
 *   for that GitHub user. Anyone can sign in; only [adminId], a numeric id (logins can be renamed),
 *   is an admin ([admit]), checked on every command so changing it takes effect at once.
 * - `POST /auth/logout` clears the session.
 *
 * The session is the cookie itself: signed (HMAC-SHA256 with [sessionKey]), HttpOnly, SameSite=Lax,
 * Secure with the `__Host-` prefix (except on http://localhost, where it can't be), carrying its own
 * expiry, which is checked here. Nothing is stored on the host; changing the key signs everyone out.
 * A cookie rides along on any request to the host, so commands with one also need the
 * [CSRF_HEADER] (which a cross-origin page can't send without a CORS preflight the host never
 * answers) and an `Origin` of [publicUrl].
 */
class GitHubSignIn(
    clientId: String,
    clientSecret: String,
    val adminId: Long,
    private val sessionKey: ByteArray,
    publicUrl: String,
    private val http: HttpClient = HttpClient(OkHttp),
    private val sessionLength: Duration = 7.days,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Where browsers reach the host; GitHub sends them back to [callbackUrl] under it. */
    val publicUrl: String = publicUrl.trimEnd('/')
    val callbackUrl: String get() = "$publicUrl/auth/callback"

    /** The only `Origin` a cookie-authenticated command may come from: [publicUrl]'s, as browsers write it. */
    val origin: String

    /** False only on http://localhost: a Secure cookie can't be set over plain http anywhere else. */
    val secure: Boolean

    init {
        val u = URI(this.publicUrl)
        val scheme = u.scheme?.lowercase()
        val host = requireNotNull(u.host) { "LILGUY_PUBLIC_URL needs a host: ${this.publicUrl}" }.lowercase()
        secure = scheme == "https"
        require(secure || (scheme == "http" && host in LOCAL_HOSTS)) { "GitHub sign-in needs an https LILGUY_PUBLIC_URL (http only on localhost): ${this.publicUrl}" }
        val defaultPort = if (secure) 443 else 80
        origin = "$scheme://$host${if (u.port == -1 || u.port == defaultPort) "" else ":${u.port}"}"
    }

    private val settings = OAuthServerSettings.OAuth2ServerSettings(
        name = PROVIDER,
        authorizeUrl = "https://github.com/login/oauth/authorize",
        accessTokenUrl = "https://github.com/login/oauth/access_token",
        requestMethod = HttpMethod.Post,
        clientId = clientId,
        clientSecret = clientSecret,
        defaultScopes = emptyList(),
        extraAuthParameters = listOf("allow_signup" to "false"),
        nonceManager = States,
        onStateCreated = { call, state -> call.sessions.set(OAuthState(state, now() + STATE_LIFETIME.inWholeMilliseconds)) },
    )

    /** Sessions and the OAuth provider; call before routing. */
    fun install(app: Application) {
        app.install(Sessions) {
            cookie<SignedIn>(cookieName("lilguy_session")) { configure(sessionLength) }
            cookie<OAuthState>(cookieName("lilguy_oauth_state")) { configure(STATE_LIFETIME) }
        }
        app.install(Authentication) {
            oauth(PROVIDER) {
                client = http
                urlProvider = { callbackUrl }
                providerLookup = { settings }
            }
        }
    }

    /** `/auth/login`, `/auth/callback` and `/auth/logout`. */
    fun routes(route: Route) = with(route) {
        route("/auth/callback") {
            // Declined on GitHub: say so, rather than the plugin's redirect back to GitHub.
            param("error") {
                get { call.respondText("GitHub sign-in didn't finish: ${call.parameters["error"]}", status = HttpStatusCode.Forbidden) }
            }
        }
        authenticate(PROVIDER) {
            get("/auth/login") { } // the plugin redirects to GitHub
            get("/auth/callback") { callback(call) }
        }
        post("/auth/logout") {
            call.sessions.clear<SignedIn>()
            call.respond(HttpStatusCode.NoContent)
        }
    }

    private suspend fun callback(call: ApplicationCall) {
        val token = call.principal<OAuthAccessTokenResponse.OAuth2>()
        val started = call.sessions.get<OAuthState>()
        call.sessions.clear<OAuthState>()
        val state = call.parameters["state"]
        if (token == null || started == null || started.expires < now() || state == null || !MessageDigest.isEqual(state.toByteArray(), started.state.toByteArray())) {
            return call.respondText("This sign-in wasn't started in this browser, or took too long. Start again at /auth/login.", status = HttpStatusCode.Forbidden)
        }
        val user = runCatching { user(token.accessToken) }.getOrElse { e ->
            return call.respondText("Couldn't read your GitHub account: ${e.message}", status = HttpStatusCode.BadGateway)
        }
        call.sessions.set(user)
        println("lil guy host: GitHub ${user.login} (${user.id}) signed in${if (user.id == adminId) " as the admin" else ", not the admin"}")
        call.respondRedirect("/")
    }

    /** Who [accessToken] belongs to, as a fresh session. */
    private suspend fun user(accessToken: String): SignedIn {
        val response = http.get("https://api.github.com/user") {
            bearerAuth(accessToken)
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "lil-guy-machine-host")
        }
        check(response.status.isSuccess()) { "api.github.com/user said ${response.status}" }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return SignedIn(id = body.getValue("id").jsonPrimitive.long, login = body.getValue("login").jsonPrimitive.content, expires = now() + sessionLength.inWholeMilliseconds)
    }

    /** The caller's unexpired sign-in, admin or not. */
    fun signedIn(call: ApplicationCall): SignedIn? = call.sessions.get<SignedIn>()?.takeIf { it.expires > now() }

    /** An admin's session plus the CSRF checks. */
    fun admit(call: ApplicationCall): Admission {
        val who = signedIn(call) ?: return Admission.Refused(HttpStatusCode.Unauthorized, "admin only: sign in at /auth/login")
        if (who.id != adminId) return Admission.Refused(HttpStatusCode.Forbidden, "signed in as ${who.login}, who isn't the admin")
        if (call.request.headers[CSRF_HEADER] != "1") return Admission.Refused(HttpStatusCode.Forbidden, "commands from a browser need the $CSRF_HEADER: 1 header")
        if (call.request.headers[HttpHeaders.Origin] != origin) return Admission.Refused(HttpStatusCode.Forbidden, "commands must come from $origin")
        return Admission.Admin("GitHub ${who.login}")
    }

    /**
     * 256-bit states (Ktor's default is 64). The plugin's check passes them all: the callback
     * checks the state against the browser's cookie instead, which the plugin can't see.
     */
    private object States : NonceManager {
        private val random = SecureRandom()
        override suspend fun newNonce(): String = hex(ByteArray(32).also(random::nextBytes))
        override suspend fun verifyNonce(nonce: String): Boolean = true
    }

    private fun cookieName(base: String) = if (secure) "__Host-$base" else base

    private fun <S : Any> CookieSessionBuilder<S>.configure(lifetime: Duration) {
        cookie.path = "/"
        cookie.httpOnly = true
        cookie.secure = secure
        cookie.maxAgeInSeconds = lifetime.inWholeSeconds
        cookie.extensions["SameSite"] = "Lax"
        transform(SessionTransportTransformerMessageAuthentication(sessionKey))
    }

    companion object {
        const val CSRF_HEADER = "X-Lilguy-Csrf"
        private const val PROVIDER = "github"
        private val STATE_LIFETIME = 10.minutes
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
        private val NAMES = listOf("LILGUY_GITHUB_CLIENT_ID", "LILGUY_GITHUB_CLIENT_SECRET", "LILGUY_ADMIN_GITHUB_ID", "LILGUY_SESSION_KEY")

        /**
         * From the environment: all of [NAMES] or none (sign-in off). Errors name what's wrong,
         * never a value. `LILGUY_PUBLIC_URL` defaults to [defaultPublicUrl].
         */
        fun fromEnv(get: (String) -> String?, defaultPublicUrl: String): GitHubSignIn? {
            val v = NAMES.associateWith(get)
            if (v.values.all { it == null }) return null
            val missing = NAMES.filter { v[it] == null }
            require(missing.isEmpty()) { "GitHub sign-in needs ${missing.joinToString()} as well (or none of ${NAMES.joinToString()})" }
            val id = requireNotNull(v.getValue("LILGUY_ADMIN_GITHUB_ID")!!.trim().toLongOrNull()) {
                "LILGUY_ADMIN_GITHUB_ID is the numeric GitHub user id (api.github.com/users/<login> has it), not the login"
            }
            val key = runCatching { hex(v.getValue("LILGUY_SESSION_KEY")!!.trim()) }.getOrNull()?.takeIf { it.size >= 32 }
            requireNotNull(key) { "LILGUY_SESSION_KEY must be at least 32 bytes as hex: openssl rand -hex 32" }
            return GitHubSignIn(v.getValue("LILGUY_GITHUB_CLIENT_ID")!!.trim(), v.getValue("LILGUY_GITHUB_CLIENT_SECRET")!!.trim(), id, key, get("LILGUY_PUBLIC_URL") ?: defaultPublicUrl)
        }
    }
}

/** A signed-in GitHub user: the session cookie's payload, signed by the host. [expires] is epoch ms. */
@Serializable
data class SignedIn(val id: Long, val login: String, val expires: Long)

/** The `state` sent to GitHub, kept in the browser that started the sign-in. */
@Serializable
data class OAuthState(val state: String, val expires: Long)
