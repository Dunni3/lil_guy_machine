package dev.lilguy.host

import dev.lilguy.protocol.CommandResult
import dev.lilguy.protocol.Content
import dev.lilguy.protocol.Delta
import dev.lilguy.protocol.LifeSummary
import dev.lilguy.protocol.PROTOCOL_VERSION
import dev.lilguy.protocol.Refused
import dev.lilguy.protocol.ServerMessage
import dev.lilguy.protocol.Snapshot
import dev.lilguy.protocol.Welcome
import dev.lilguy.protocol.Wire
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * The host's HTTP side, for one set of [lives]:
 * - `GET /`: the debug page (plain JS) that shows a life updating and sends commands;
 * - `GET /v1/lives`: the lives, as [LifeSummary]s;
 * - `GET /v1/lives/{life}/state`: one life's [Snapshot], for clients without a socket;
 * - `WS /v1/lives/{life}/stream?v=<version>&have=<content hash>`: the state stream, open to
 *   anyone, read-only (anything a client sends is ignored);
 * - `POST /v1/lives/{life}/commands`: one [dev.lilguy.protocol.Command], for admins only ([auth]).
 */
fun Application.host(lives: Map<String, Life>, auth: AdminAuth) {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }
    val page = Life::class.java.getResource("/debug/index.html")?.readText() ?: "<p>debug page missing from the build</p>"
    routing {
        get("/") { call.respondText(page, ContentType.Text.Html) }
        get("/v1/lives") {
            call.respondText(Wire.json.encodeToString(ListSerializer(LifeSummary.serializer()), lives.values.mapNotNull { it.summary }), ContentType.Application.Json)
        }
        get("/v1/lives/{life}/state") {
            val p = lives[call.parameters["life"]]?.latest ?: return@get call.respondText("no such life", status = HttpStatusCode.NotFound)
            call.respondText(Wire.encode(Snapshot(p.state)), ContentType.Application.Json)
        }
        webSocket("/v1/lives/{life}/stream") { stream(lives) }
        post("/v1/lives/{life}/commands") {
            if (!auth.admits(call.request.headers[HttpHeaders.Authorization])) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                return@post call.respondText(Wire.encode(CommandResult(ok = false, error = "admin only")), ContentType.Application.Json, HttpStatusCode.Unauthorized)
            }
            val life = lives[call.parameters["life"]]
                ?: return@post call.respondText(Wire.encode(CommandResult(ok = false, error = "no such life")), ContentType.Application.Json, HttpStatusCode.NotFound)
            val command = runCatching { Wire.command(call.receiveText()) }.getOrElse { e ->
                return@post call.respondText(Wire.encode(CommandResult(ok = false, error = "bad command: ${e.message}")), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
            val result = life.command(command)
            call.respondText(Wire.encode(result), ContentType.Application.Json, if (result.ok) HttpStatusCode.OK else HttpStatusCode.Conflict)
        }
    }
}

/**
 * One viewer's stream: welcome, the content unless it has it, a snapshot, then every delta in
 * order. A viewer that fell behind (a gap in seq: the life dropped messages it hadn't sent) gets
 * a fresh snapshot instead of the deltas it missed.
 */
private suspend fun DefaultWebSocketServerSession.stream(lives: Map<String, Life>) {
    suspend fun say(m: ServerMessage) = send(Frame.Text(Wire.encode(m)))
    val asked = call.request.queryParameters["v"]?.toIntOrNull()
    if (asked != null && asked != PROTOCOL_VERSION) {
        say(Refused(PROTOCOL_VERSION, "this host speaks protocol $PROTOCOL_VERSION, not $asked"))
        return close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "protocol version"))
    }
    val life = lives[call.parameters["life"]]
    val first = life?.latest
    if (life == null || first == null) {
        say(Refused(PROTOCOL_VERSION, "no life '${call.parameters["life"]}'"))
        return close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such life"))
    }
    say(Welcome(PROTOCOL_VERSION, lives.values.mapNotNull { it.summary }))
    var contentSent = call.request.queryParameters["have"]
    var content: Content = first.content
    var seq = -1L
    suspend fun snapshot(c: Content, s: Snapshot) {
        if (c.hash != contentSent) {
            say(c)
            contentSent = c.hash
        }
        say(s)
        seq = s.state.seq
    }
    val sending = launch {
        life.messages
            .onSubscription { life.latest!!.let { snapshot(it.content, Snapshot(it.state)) } }
            .collect { m ->
                when (m) {
                    is Content -> content = m
                    is Snapshot -> snapshot(content, m)
                    is Delta -> when {
                        m.seq <= seq -> Unit // already in the snapshot it joined with
                        m.seq == seq + 1 -> { say(m); seq = m.seq }
                        else -> life.latest!!.let { snapshot(it.content, Snapshot(it.state)) }
                    }
                    else -> Unit
                }
            }
    }
    // Read-only: whatever the viewer sends is dropped. The loop ends when it hangs up.
    for (@Suppress("UNUSED_VARIABLE") frame in incoming) Unit
    sending.cancel()
}
