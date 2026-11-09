package dev.lilguy.host

import dev.lilguy.agent.Scenario
import dev.lilguy.agent.live.ModelSetup
import dev.lilguy.decision.RandomPicker
import dev.lilguy.protocol.Command
import dev.lilguy.protocol.Delta
import dev.lilguy.protocol.LifeState
import dev.lilguy.protocol.Snapshot
import dev.lilguy.protocol.Wire
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HostTest {
    /**
     * A viewer that joins with a snapshot and applies every delta after it, each through the wire's
     * JSON, holds the same state the host published at that seq: run, fast and slow sections, the
     * easel, and the logs with their caps. The life paints with random picks so the easel moves too.
     */
    @Test
    fun `a join snapshot plus the deltas reproduces the host's state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val setup = ModelSetup.scripted(days = null).copy(painter = { RandomPicker(5.milliseconds, seed = 7) })
        val life = Life("test", setup, db = null, frame = 5.milliseconds, publishEvery = 10.milliseconds)
        life.start(scope)
        val fast = StateReader.settings(dev.lilguy.agent.live.RunSettings()).copy(speed = 60, days = null)
        assertTrue(life.command(Command.Configure(fast)).ok)
        assertTrue(life.command(Command.Start).ok)
        val seen = ConcurrentHashMap<Long, LifeState>()
        var client: LifeState? = null
        var deltas = 0
        var logDeltas = 0
        val viewer = scope.launch {
            life.messages
                .onSubscription { client = (Wire.server(Wire.encode(Snapshot(life.latest!!.state))) as Snapshot).state.also { seen[it.seq] = it } }
                .collect { m ->
                    when (val decoded = Wire.server(Wire.encode(m))) {
                        is Snapshot -> client = decoded.state
                        is Delta -> {
                            val c = client!!
                            if (decoded.seq <= c.seq) return@collect
                            client = c.apply(decoded)
                            deltas++
                            if (decoded.logs != null) logDeltas++
                        }
                        else -> Unit
                    }
                    client?.let { seen[it.seq] = it }
                }
        }
        val joinedAt = withTimeout(5.seconds) { while (client == null) delay(5); client!!.fast.tick }
        // Long enough for decisions, money changing hands and a piece or two.
        val host = withTimeout(60.seconds) {
            while (true) {
                val p = life.latest!!.state
                if (p.logs.decisions.size >= 8 && p.logs.transactions.isNotEmpty() && p.fast.tick > joinedAt + 1500) break
                delay(20)
            }
            val p = life.latest!!.state
            while (seen[p.seq] == null) delay(5)
            p
        }
        viewer.cancel()
        life.stop()
        scope.cancel()
        assertTrue(deltas > 50 && logDeltas > 5, "$deltas deltas, $logDeltas with log entries")
        val rebuilt = assertNotNull(seen[host.seq])
        assertEquals(host, rebuilt)
    }

    /** Commands need the admin token: none, or a wrong one, is refused before the command is read. */
    @Test
    fun `the command endpoint rejects requests without a valid admin token`() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val life = Life("main", ModelSetup({ Scenario.KeyDoor.scriptedPolicy(0.milliseconds) }, Scenario.KeyDoor), db = null)
        runBlocking { life.start(scope) }
        application { host(mapOf(life.id to life), DevTokenAuth("test-admin-token")) }
        val start = Wire.encode(Command.Start)
        suspend fun post(auth: String?) = client.post("/v1/lives/main/commands") {
            auth?.let { header(HttpHeaders.Authorization, it) }
            setBody(start)
        }
        assertEquals(HttpStatusCode.Unauthorized, post(null).status)
        assertEquals(HttpStatusCode.Unauthorized, post("Bearer not-the-token").status)
        assertEquals(HttpStatusCode.Unauthorized, post("test-admin-token").status, "the token without the Bearer scheme")
        assertEquals(HttpStatusCode.OK, post("Bearer test-admin-token").status)
        assertEquals(dev.lilguy.protocol.RunState.Running, life.latest!!.state.run.state)
        life.stop()
        scope.cancel()
        // Without a token configured, nobody is an admin.
        assertTrue(!DevTokenAuth(null).admits("Bearer "))
    }
}
