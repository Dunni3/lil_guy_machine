package dev.lilguy.host

import dev.lilguy.protocol.HisLetter
import dev.lilguy.protocol.Mailbag
import dev.lilguy.protocol.SentLetter
import dev.lilguy.runlog.SqlDriver
import dev.lilguy.sim.Letter
import dev.lilguy.sim.OutLetter
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Viewers' accounts, their sessions and the letters they write him, in their own SQLite file
 * (`LILGUY_ACCOUNTS_DB`, runs/accounts.db), apart from the run database, which syncs to the server:
 * emails and password hashes never leave the host.
 *
 * An account is an email and a password, nothing more: no confirmation email, so the address is only
 * a login name. Passwords are kept as PBKDF2-SHA256 with a salt each; a session is a random token, of
 * which only the SHA-256 is kept. A login that fails [MAX_FAILURES] times in a row locks that email
 * for [LOCKOUT_MS], so nobody can guess a password at speed. Letters are at most [MAX_LENGTH]
 * characters, as many as anyone likes. The letters he writes back are kept here too, each
 * for the account whose letters he was answering, which only that account can read.
 *
 * One connection, so every call is synchronized. [now] is epoch milliseconds, for tests.
 */
class Accounts(private val db: SqlDriver, private val now: () -> Long = System::currentTimeMillis) {
    /** An account, by its row id. */
    data class Who(val id: Long, val email: String)

    /** A new session: [token] is what the client sends; it ends at [expires] (epoch ms). */
    data class Session(val who: Who, val token: String, val expires: Long)

    /** Thrown for anything the caller got wrong; its message is said to them. */
    class Refused(message: String) : Exception(message)

    private val random = SecureRandom()
    private val failures = mutableMapOf<String, Pair<Int, Long>>()

    init {
        synchronized(this) {
            db.execute("CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE, salt BLOB NOT NULL, hash BLOB NOT NULL, iterations INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS sessions (token_hash BLOB PRIMARY KEY, account INTEGER NOT NULL REFERENCES accounts(id), created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
            db.execute(
                "CREATE TABLE IF NOT EXISTS letters (id INTEGER PRIMARY KEY, account INTEGER NOT NULL REFERENCES accounts(id), life TEXT NOT NULL, signed TEXT NOT NULL, body TEXT NOT NULL, " +
                    "sent_at INTEGER NOT NULL, run TEXT, delivered_at INTEGER, opened_at INTEGER)",
            )
            db.execute("CREATE INDEX IF NOT EXISTS letters_by_life ON letters(life, delivered_at)")
            db.execute("CREATE INDEX IF NOT EXISTS letters_by_account ON letters(account, sent_at)")
            db.execute(
                "CREATE TABLE IF NOT EXISTS his_letters (id INTEGER PRIMARY KEY, account INTEGER NOT NULL REFERENCES accounts(id), life TEXT NOT NULL, run TEXT NOT NULL, seq INTEGER NOT NULL, " +
                    "name TEXT NOT NULL, body TEXT NOT NULL, written_at INTEGER NOT NULL, day INTEGER NOT NULL, time TEXT NOT NULL, in_reply_to INTEGER, UNIQUE (life, run, seq))",
            )
            db.execute("CREATE INDEX IF NOT EXISTS his_letters_by_account ON his_letters(account, life, id)")
        }
    }

    /** Makes an account and signs it in. */
    @Synchronized
    fun signUp(email: String, password: String): Session {
        val e = normal(email)
        if (!EMAIL.matches(e) || e.length > 254) throw Refused("that doesn't look like an email address")
        if (password.length < MIN_PASSWORD) throw Refused("the password must be at least $MIN_PASSWORD characters")
        if (password.length > MAX_PASSWORD) throw Refused("the password can be at most $MAX_PASSWORD characters")
        if (db.query("SELECT 1 FROM accounts WHERE email = ?", listOf(e)).isNotEmpty()) throw Refused("there's already an account with that email: sign in instead")
        val salt = ByteArray(16).also(random::nextBytes)
        db.execute("INSERT INTO accounts (email, salt, hash, iterations, created_at) VALUES (?, ?, ?, ?, ?)", listOf(e, salt, hash(password, salt, ITERATIONS), ITERATIONS.toLong(), now()))
        val id = db.query("SELECT id FROM accounts WHERE email = ?", listOf(e)).single()["id"] as Long
        return session(Who(id, e))
    }

    /** Signs in, or refuses with the same words whether the email or the password was wrong. */
    @Synchronized
    fun signIn(email: String, password: String): Session {
        val e = normal(email)
        val t = now()
        failures[e]?.let { (n, at) -> if (n >= MAX_FAILURES && t - at < LOCKOUT_MS) throw Refused("too many wrong passwords: try again in a few minutes") }
        val row = db.query("SELECT id, salt, hash, iterations FROM accounts WHERE email = ?", listOf(e)).singleOrNull()
        // Hash even when there's no such account, so the time taken doesn't say whether there is.
        val salt = row?.get("salt") as? ByteArray ?: ByteArray(16)
        val given = hash(password.take(MAX_PASSWORD), salt, (row?.get("iterations") as? Long)?.toInt() ?: ITERATIONS)
        if (row == null || !MessageDigest.isEqual(given, row["hash"] as ByteArray)) {
            val (n, _) = failures[e] ?: (0 to 0L)
            failures[e] = (if (failures[e]?.let { t - it.second >= LOCKOUT_MS } == true) 1 else n + 1) to t
            throw Refused("wrong email or password")
        }
        failures.remove(e)
        return session(Who(row["id"] as Long, e))
    }

    /** Who [token] signs in as, or null for none or an expired one. */
    @Synchronized
    fun who(token: String?): Who? {
        val t = token?.takeIf { it.isNotBlank() } ?: return null
        val row = db.query(
            "SELECT a.id, a.email FROM sessions s JOIN accounts a ON a.id = s.account WHERE s.token_hash = ? AND s.expires_at > ?",
            listOf(sha256(t), now()),
        ).singleOrNull() ?: return null
        return Who(row["id"] as Long, row["email"] as String)
    }

    @Synchronized
    fun signOut(token: String) {
        db.execute("DELETE FROM sessions WHERE token_hash = ?", listOf(sha256(token)))
    }

    /** Sends a letter from [who] to [life], signed [from]; returns its id. */
    @Synchronized
    fun send(who: Who, life: String, from: String, body: String): Long {
        val signed = from.trim().replace(WHITESPACE, " ")
        val text = body.trim().replace(CONTROL, "")
        if (signed.isEmpty()) throw Refused("sign the letter with a name")
        if (signed.length > MAX_NAME) throw Refused("the name can be at most $MAX_NAME characters")
        if (text.isEmpty()) throw Refused("the letter is empty")
        if (text.length > MAX_LENGTH) throw Refused("a letter can be at most $MAX_LENGTH characters")
        db.execute("INSERT INTO letters (account, life, signed, body, sent_at) VALUES (?, ?, ?, ?, ?)", listOf(who.id, life, signed, text, now()))
        return db.query("SELECT last_insert_rowid() AS id").single()["id"] as Long
    }

    /** [who]'s letters to [life], newest first. */
    @Synchronized
    fun letters(who: Who, life: String, limit: Int = 50): List<SentLetter> =
        db.query("SELECT id, signed, body, sent_at, delivered_at, opened_at FROM letters WHERE account = ? AND life = ? ORDER BY id DESC LIMIT ?", listOf(who.id, life, limit.toLong())).map { r ->
            val opened = r["opened_at"] as Long?
            SentLetter(
                id = r["id"] as Long, from = r["signed"] as String, body = r["body"] as String, sentAt = iso(r["sent_at"] as Long),
                status = when {
                    opened != null -> "opened"
                    r["delivered_at"] != null -> "delivered"
                    else -> "waiting"
                },
                openedAt = opened?.let(::iso),
            )
        }

    /** Letters to [life] not through the hatch yet, oldest first. */
    @Synchronized
    fun waiting(life: String): List<Letter> =
        db.query("SELECT id, account, signed, body FROM letters WHERE life = ? AND delivered_at IS NULL ORDER BY id", listOf(life)).map { r ->
            Letter(r["id"] as Long, r["signed"] as String, r["body"] as String, sender = r["account"] as Long)
        }

    /**
     * Keeps the letters he wrote in [run] of [life], each for the account it's to (its [OutLetter.to]);
     * one kept already ([OutLetter.seq] in that run) or to an account that's gone is skipped.
     */
    @Synchronized
    fun fromHim(life: String, run: String, letters: Collection<OutLetter>) = db.transaction {
        letters.forEach { l ->
            if (db.query("SELECT 1 FROM accounts WHERE id = ?", listOf(l.to)).isEmpty()) return@forEach
            db.execute(
                "INSERT OR IGNORE INTO his_letters (account, life, run, seq, name, body, written_at, day, time, in_reply_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(l.to, life, run, l.seq.toLong(), l.name, l.body, now(), l.day.toLong(), l.time, l.inReplyTo),
            )
        }
    }

    /** The letters he wrote [who] in [life], newest first. */
    @Synchronized
    fun replies(who: Who, life: String, limit: Int = 50): List<HisLetter> =
        db.query("SELECT id, name, body, written_at, day, time, in_reply_to FROM his_letters WHERE account = ? AND life = ? ORDER BY id DESC LIMIT ?", listOf(who.id, life, limit.toLong())).map { r ->
            HisLetter(r["id"] as Long, r["name"] as String, r["body"] as String, iso(r["written_at"] as Long), (r["day"] as Long).toInt(), r["time"] as String, r["in_reply_to"] as Long?)
        }

    /** [who]'s mailbag for [life]: their letters and his. */
    fun mailbag(who: Who, life: String) = Mailbag(letters(who, life), replies(who, life), MAX_LENGTH)

    /** Marks [ids] as put through the hatch of [run]. */
    @Synchronized
    fun delivered(ids: Collection<Long>, run: String) = db.transaction {
        ids.forEach { db.execute("UPDATE letters SET delivered_at = ?, run = ? WHERE id = ? AND delivered_at IS NULL", listOf(now(), run, it)) }
    }

    /** Marks [ids] as opened, those not marked yet. */
    @Synchronized
    fun opened(ids: Collection<Long>) = db.transaction {
        ids.forEach { db.execute("UPDATE letters SET opened_at = ? WHERE id = ? AND opened_at IS NULL", listOf(now(), it)) }
    }

    private fun session(who: Who): Session {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val t = now()
        db.execute("DELETE FROM sessions WHERE expires_at <= ?", listOf(t))
        db.execute("INSERT INTO sessions (token_hash, account, created_at, expires_at) VALUES (?, ?, ?, ?)", listOf(sha256(token), who.id, t, t + SESSION_MS))
        return Session(who, token, t + SESSION_MS)
    }

    companion object {
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 200
        const val MAX_NAME = 40
        const val MAX_LENGTH = 1000
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 15 * 60_000L
        const val SESSION_MS = 30 * 24 * 3600_000L
        private const val ITERATIONS = 210_000
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val WHITESPACE = Regex("\\s+")
        /** Control characters but newline and tab. */
        private val CONTROL = Regex("[\\p{Cc}&&[^\\n\\t]]")

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

        private fun normal(email: String) = email.trim().lowercase()

        private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

        private fun hash(password: String, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded
    }
}
