package dev.lilguy.host

import java.security.MessageDigest

/**
 * Who may send commands. v0 has one admin (Ian); everyone else only watches. The next phase puts
 * GitHub OAuth behind this interface (a session cookie for one numeric GitHub user id); the
 * command endpoint only asks [admits].
 */
fun interface AdminAuth {
    /** True when the request's `Authorization` header (null when absent) is an admin's. */
    fun admits(authorization: String?): Boolean
}

/**
 * Dev only: one bearer token from the environment (`LILGUY_ADMIN_TOKEN`). With no token set,
 * nobody is an admin and every command is refused. Compared in constant time; never logged.
 */
class DevTokenAuth(token: String?) : AdminAuth {
    private val expected: ByteArray? = token?.trim()?.takeIf { it.isNotEmpty() }?.toByteArray()

    val enabled: Boolean get() = expected != null

    override fun admits(authorization: String?): Boolean {
        val want = expected ?: return false
        val given = authorization?.trim()?.takeIf { it.startsWith(BEARER, ignoreCase = true) }?.substring(BEARER.length)?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), want)
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}
