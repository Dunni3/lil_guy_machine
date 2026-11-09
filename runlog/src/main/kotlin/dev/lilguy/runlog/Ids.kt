package dev.lilguy.runlog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Ids made on the device, so offline writers never collide and sync can upsert on them. */
object Ids {
    private val random = SecureRandom()

    /** A UUIDv7: 48 bits of Unix milliseconds, then random bits, so ids sort by when they were made. */
    fun uuid7(millis: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        for (i in 0 until 6) bytes[i] = (millis ushr (40 - 8 * i)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /** The same id every time for [name]: for rows imported from old run logs, so importing twice changes nothing. */
    fun named(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    fun now(): String = Instant.now().toString()
}

/** Bodies stored once by SHA-256 of the plain bytes, gzipped. */
object Blobs {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
