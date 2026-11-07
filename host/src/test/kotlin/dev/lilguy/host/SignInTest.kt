package dev.lilguy.host

import dev.lilguy.agent.Scenario
import dev.lilguy.agent.live.ModelSetup
import dev.lilguy.protocol.Command
import dev.lilguy.protocol.RunState
import dev.lilguy.protocol.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds

/** GitHub sign-in through to a command, with GitHub's token and user endpoints mocked. */
class SignInTest {
    @Test
    fun `a session for a GitHub id that isn't the admin's can't send commands`() = signedIn(githubId = 99) { send ->
        assertEquals(HttpStatusCode.Forbidden, send(true))
    }

    @Test
    fun `the admin's session can't send a command without the CSRF header`() = signedIn(githubId = ADMIN) { send ->
        assertEquals(HttpStatusCode.Forbidden, send(false))
        assertEquals(HttpStatusCode.OK, send(true), "the same session with the header")
    }

    /**
     * Signs a browser in as GitHub user [githubId] (the admin is [ADMIN]), then hands [check] a way to
     * post a start command from it, with or without the CSRF header. A refused command must leave
     * the run where it was.
     */
    private fun signedIn(githubId: Long, check: suspend (send: suspend (csrf: Boolean) -> HttpStatusCode) -> Unit) = testApplication {
        val github = HttpClient(MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.host + request.url.encodedPath) {
                "github.com/login/oauth/access_token" -> respond("""{"access_token":"gho_test","token_type":"bearer","scope":""}""", HttpStatusCode.OK, json)
                "api.github.com/user" -> respond("""{"login":"someone","id":$githubId}""", HttpStatusCode.OK, json)
                else -> error("unexpected call to ${request.url}")
            }
        })
        val signIn = GitHubSignIn("test-client", "test-secret", adminId = ADMIN, sessionKey = ByteArray(32) { it.toByte() }, publicUrl = "http://localhost", http = github)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val life = Life("main", ModelSetup({ Scenario.KeyDoor.scriptedPolicy(0.milliseconds) }, Scenario.KeyDoor), db = null)
        runBlocking { life.start(scope) }
        application { host(mapOf(life.id to life), HostAuth(DevTokenAuth(null), signIn)) }
        val browser = createClient {
            followRedirects = false
            install(HttpCookies)
        }
        val toGitHub = browser.get("/auth/login")
        assertEquals(HttpStatusCode.Found, toGitHub.status)
        val state = Url(toGitHub.headers[HttpHeaders.Location]!!).parameters["state"]!!
        assertEquals(HttpStatusCode.Found, browser.get("/auth/callback?code=test-code&state=$state").status)
        check { csrf ->
            val before = life.latest!!.state.run.state
            val status = browser.post("/v1/lives/main/commands") {
                header(HttpHeaders.Origin, "http://localhost")
                if (csrf) header(GitHubSignIn.CSRF_HEADER, "1")
                setBody(Wire.encode(Command.Start))
            }.status
            if (status != HttpStatusCode.OK) assertEquals(before, life.latest!!.state.run.state)
            else assertNotEquals(RunState.Ready, life.latest!!.state.run.state)
            status
        }
        life.stop()
        scope.cancel()
    }

    private companion object {
        const val ADMIN = 4242L
    }
}
