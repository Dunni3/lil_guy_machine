package dev.lilguy.host

import dev.lilguy.protocol.Viewer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest

/**
 * Who may send commands. v0 has one admin (Ian); everyone else only watches. The command endpoint
 * only asks [admit].
 */
fun interface AdminAuth {
    fun admit(call: ApplicationCall): Admission
}

sealed interface Admission {
    /** An admin's call; [who] says how it got in, for the log. */
    data class Admin(val who: String) : Admission

    /** Not an admin's: 401 when nobody is signed in, 403 when someone is but may not. */
    data class Refused(val status: HttpStatusCode, val reason: String) : Admission
}

/**
 * The host's admin check: a GitHub sign-in ([github]: the admin's session cookie plus the CSRF
 * checks), or the dev token ([devToken]: a bearer header, for curl and local runs). With neither
 * configured every command is refused.
 */
class HostAuth(val devToken: DevTokenAuth, val github: GitHubSignIn?) : AdminAuth {
    override fun admit(call: ApplicationCall): Admission {
        val token = devToken.admit(call)
        if (token is Admission.Admin || github == null) return token
        // The session's answer, unless there's no session at all and a token was tried.
        val session = github.admit(call)
        return if (session is Admission.Refused && session.status == HttpStatusCode.Unauthorized && call.request.headers[HttpHeaders.Authorization] != null) token else session
    }

    /** What the caller is, for `GET /auth/me`. Admin here needs no CSRF header: it's a read. */
    fun viewer(call: ApplicationCall): Viewer {
        val signedIn = github?.signedIn(call)
        return Viewer(
            admin = devToken.admit(call) is Admission.Admin || (signedIn != null && signedIn.id == github?.adminId),
            login = signedIn?.login,
            signIn = github != null,
            devToken = devToken.enabled,
        )
    }
}

/**
 * Dev only: one bearer token from the environment (`LILGUY_ADMIN_TOKEN`), for curl and local runs.
 * Off unless set; the host says at startup when it's on. Compared in constant time; never logged.
 * It needs no CSRF header, since a browser never sends an `Authorization` header on its own.
 */
class DevTokenAuth(token: String?) : AdminAuth {
    private val expected: ByteArray? = token?.trim()?.takeIf { it.isNotEmpty() }?.toByteArray()

    val enabled: Boolean get() = expected != null

    override fun admit(call: ApplicationCall): Admission =
        if (admits(call.request.headers[HttpHeaders.Authorization])) Admission.Admin("dev token") else Admission.Refused(HttpStatusCode.Unauthorized, "admin only")

    /** True when [authorization] (the header, null when absent) carries the token. */
    fun admits(authorization: String?): Boolean {
        val want = expected ?: return false
        val given = authorization?.trim()?.takeIf { it.startsWith(BEARER, ignoreCase = true) }?.substring(BEARER.length)?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), want)
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}
