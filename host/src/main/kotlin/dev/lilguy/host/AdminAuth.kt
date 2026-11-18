package dev.lilguy.host

import java.security.MessageDigest

/**
 * Who may send commands. v0 has one admin (Ian); everyone else only watches. The command endpoint
 * only asks [admits].
 */
fun interface AdminAuth {
    /** True when the request's `Authorization` header (null when absent) is an admin's. */
    fun admits(authorization: String?): Boolean
}

/**
 * v0's admin login: one password from the environment (`LILGUY_ADMIN_PASSWORD`), sent as a bearer
 * header. With none set, nobody is an admin and every command is refused. Compared in constant
 * time; never logged. A browser never adds this header by itself, so commands need no CSRF check.
 */
class AdminPassword(password: String?) : AdminAuth {
    private val expected: ByteArray? = password?.trim()?.takeIf { it.isNotEmpty() }?.toByteArray()

    val enabled: Boolean get() = expected != null

    override fun admits(authorization: String?): Boolean {
        val want = expected ?: return false
        val given = authorization?.trim()?.takeIf { it.startsWith(BEARER, ignoreCase = true) }?.substring(BEARER.length)?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), want)
    }

    companion object {
        /** Nothing limits guesses, so the host won't start with a password shorter than this. */
        const val MIN_LENGTH = 24
        private const val BEARER = "Bearer "
    }
}
