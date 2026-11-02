package dev.lilguy.runlog

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.UUID

/** Ids made on the writer, so writers that are offline never collide and a row sent twice is the same row. */
object Ids {
    private val random = SecureRandom()

    /** A UUIDv7: 48 bits of Unix milliseconds, then random, so ids sort by when they were made. */
    fun uuid7(nowMillis: Long = System.currentTimeMillis()): String {
        val rand = ByteArray(10).also(random::nextBytes)
        val msb = (nowMillis shl 16) or 0x7000L or ((rand[0].toLong() and 0x0f) shl 8) or (rand[1].toLong() and 0xff)
        var lsb = 0L
        for (i in 2 until 10) lsb = (lsb shl 8) or (rand[i].toLong() and 0xff)
        lsb = (lsb and 0x3fffffffffffffffL) or Long.MIN_VALUE // variant 10
        return UUID(msb, lsb).toString()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
}

/** The commit this build came from, written by the `buildInfo` task. */
object BuildInfo {
    private val props: Properties by lazy {
        Properties().also { p -> BuildInfo::class.java.getResourceAsStream("/dev/lilguy/runlog/build.properties")?.use(p::load) }
    }

    val gitSha: String? get() = props.getProperty("git_sha")?.takeIf { it != "unknown" }
    val gitDirty: Boolean? get() = props.getProperty("git_dirty")?.toBooleanStrictOrNull()
}
