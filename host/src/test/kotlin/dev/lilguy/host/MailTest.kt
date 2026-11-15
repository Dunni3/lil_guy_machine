package dev.lilguy.host

import dev.lilguy.agent.live.ModelSetup
import dev.lilguy.agent.live.RunSettings
import dev.lilguy.protocol.Command
import dev.lilguy.protocol.Credentials
import dev.lilguy.protocol.LetterDraft
import dev.lilguy.protocol.Mailbag
import dev.lilguy.protocol.SignedIn
import dev.lilguy.protocol.Wire
import dev.lilguy.runlog.jdbc.JdbcSqlDriver
import dev.lilguy.sim.OutLetter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MailTest {
    /**
     * Someone makes an account, can't sign in with the wrong password, and writes him a letter; the life puts it
     * through the hatch of the run that's going, the scripted policy opens it, the sender sees it opened, and a
     * letter he writes back comes to them.
     */
    @Test
    fun `a letter goes from sign-up through the hatch to opened`() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accounts = Accounts(JdbcSqlDriver(null))
        val life = Life("main", ModelSetup.scripted(days = null), db = null, frame = 5.milliseconds, publishEvery = 10.milliseconds, mail = accounts, mailEvery = 20.milliseconds)
        runBlocking { life.start(scope) }
        application { host(mapOf(life.id to life), AdminPassword(null), accounts) }

        suspend fun credentials(path: String, password: String) = client.post(path) { setBody(Wire.encode(Credentials.serializer(), Credentials("Sam@Example.com ", password))) }
        assertEquals(HttpStatusCode.BadRequest, credentials("/v1/accounts", "short").status)
        assertEquals(HttpStatusCode.Created, credentials("/v1/accounts", "a long enough password").status)
        assertEquals(HttpStatusCode.BadRequest, credentials("/v1/accounts", "a long enough password").status, "the same email twice")
        assertEquals(HttpStatusCode.BadRequest, credentials("/v1/sessions", "the wrong password").status)
        val token = Wire.decode(SignedIn.serializer(), credentials("/v1/sessions", "a long enough password").bodyAsText()).token

        val draft = Wire.encode(LetterDraft.serializer(), LetterDraft("Sam", "Your sketches cheer me up."))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/lives/main/letters") { setBody(draft) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/v1/lives/main/letters") { header(HttpHeaders.Authorization, "Bearer $token"); setBody(draft) }.status)
        suspend fun mine() = Wire.decode(Mailbag.serializer(), client.get("/v1/lives/main/letters") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText())
        assertEquals("waiting", mine().letters.single().status, "no run is going")
        assertEquals(Accounts.PER_DAY - 1, mine().left)

        assertTrue(life.command(Command.Configure(StateReader.settings(RunSettings()).copy(speed = 60, days = null))).ok)
        assertTrue(life.command(Command.Start).ok)
        withTimeout(30.seconds) { while (mine().letters.single().status != "opened") delay(20) }
        assertTrue(life.latest!!.state.logs.decisions.any { "letter" in it.rationale.orEmpty() })

        // He knows who wrote it, so a letter he writes back reaches only that account's mailbag, once.
        val sam = accounts.who(token)!!
        val reply = OutLetter(1, to = sam.id, name = "Sam", body = "Dear Sam, thank you.", tick = 10, day = 1, time = "09:30", inReplyTo = mine().letters.single().id)
        accounts.fromHim("main", "run-1", listOf(reply))
        accounts.fromHim("main", "run-1", listOf(reply))
        assertEquals(listOf("Dear Sam, thank you." to mine().letters.single().id), mine().replies.map { it.body to it.inReplyTo })
        life.stop()
        scope.cancel()
    }
}
