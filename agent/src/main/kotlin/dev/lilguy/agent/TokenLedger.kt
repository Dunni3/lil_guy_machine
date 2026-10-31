package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.EndpointConfig
import dev.lilguy.decision.ScoreRequest
import dev.lilguy.decision.ScoreResult
import dev.lilguy.decision.Scorer
import dev.lilguy.decision.Usage
import dev.lilguy.sim.GameClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a model call was for. */
@Serializable
enum class TokenPurpose {
    /** The agent loop picking his next intent. */
    @SerialName("decision") Decision,
    /** The think intent's thought. */
    @SerialName("think") Think,
    /** A memory: a stretch of events (level 1) or a day (level 2). */
    @SerialName("summary") Summary,
    /** A drawing, or the day's commission. */
    @SerialName("art") Art,
    /** The nightly revision of his self-model. */
    @SerialName("self") SelfModel,
    /** Scoring which memories and thoughts reach a prompt. */
    @SerialName("recall") Recall,
    /** Scoring how significant new events are. */
    @SerialName("significance") Significance,
}

/** Dollars per million tokens, from an endpoint's `price_in` and `price_out`. */
data class TokenPrice(val inPerMillion: Double, val outPerMillion: Double) {
    fun cost(usage: Usage) = (usage.promptTokens * inPerMillion + usage.completionTokens * outPerMillion) / 1_000_000

    companion object {
        fun of(config: EndpointConfig): TokenPrice? =
            if (config.priceIn == null && config.priceOut == null) null else TokenPrice(config.priceIn ?: 0.0, config.priceOut ?: 0.0)
    }
}

/**
 * One finished [DecisionModel.decide], failed or not. [calls] is HTTP calls (retries after a bad
 * reply are extra calls), [tick] the sim tick it started at.
 */
@Serializable
data class TokenCall(
    val tick: Long,
    val day: Int?,
    val time: String?,
    val purpose: TokenPurpose,
    val model: String,
    val calls: Int,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("latency_ms") val latencyMs: Long,
    @SerialName("cost_usd") val costUsd: Double? = null,
    val failed: Boolean = false,
)

/** Sums of [TokenCall]s: a day, a purpose, a whole run. */
@Serializable
data class TokenTotals(
    /** `decide` invocations. */
    val decides: Int = 0,
    /** HTTP calls, retries included. */
    val calls: Int = 0,
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("latency_ms") val latencyMs: Long = 0,
    @SerialName("cost_usd") val costUsd: Double? = null,
) {
    val tokens: Int get() = promptTokens + completionTokens

    operator fun plus(c: TokenCall) = TokenTotals(
        decides + 1, calls + c.calls, promptTokens + c.promptTokens, completionTokens + c.completionTokens, latencyMs + c.latencyMs,
        if (costUsd == null && c.costUsd == null) null else (costUsd ?: 0.0) + (c.costUsd ?: 0.0),
    )

    companion object {
        fun of(calls: Iterable<TokenCall>) = calls.fold(TokenTotals()) { t, c -> t + c }
    }
}

/** One game day: what was spent, and on what. */
@Serializable
data class TokenDay(val day: Int, val total: TokenTotals, val byPurpose: Map<TokenPurpose, TokenTotals>)

/**
 * Every model call of a run with the sim time it was made at, so token use reads as a function of
 * game time: per day, per purpose, and where it is heading. Fill it by wrapping each model with
 * [meter]; [attach] it to the world once there is one, or calls are stamped tick 0.
 *
 * Safe to write from any coroutine: summaries, thoughts and scoring run in the background.
 */
class TokenLedger {
    private val log = java.util.concurrent.CopyOnWriteArrayList<TokenCall>()

    @Volatile private var tickNow: () -> Long = { 0L }
    @Volatile private var clock: GameClock? = null

    /** Stamps calls from here on with [tick] and [clock]'s day and time. */
    fun attach(tick: () -> Long, clock: GameClock? = null) {
        tickNow = tick
        this.clock = clock
    }

    /** Every call so far, in the order they finished. */
    fun calls(): List<TokenCall> = log.toList()

    /** [model], reporting every `decide` here as [purpose]. Closing stays with whoever owns [model]. */
    fun meter(model: DecisionModel, purpose: TokenPurpose, price: TokenPrice? = null): DecisionModel = Metered(model, purpose, price)

    /** [scorer], reporting every `score` here as [purpose]. */
    fun meter(scorer: Scorer, purpose: TokenPurpose, price: TokenPrice? = null): Scorer = object : Scorer {
        override val id: String get() = scorer.id

        override suspend fun score(request: ScoreRequest): ScoreResult {
            val tick = tickNow()
            try {
                return scorer.score(request).also { record(tick, purpose, id, price, it.usage, 1, it.latency.inWholeMilliseconds, failed = false) }
            } catch (e: DecisionFailed) {
                record(tick, purpose, id, price, e.usage, 1, 0, failed = true)
                throw e
            }
        }
    }

    /** Writes every call so far to [file] as JSONL, replacing it. */
    fun writeJsonl(file: java.io.File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out -> calls().forEach { out.write(DecisionLog.json.encodeToString(TokenCall.serializer(), it)); out.newLine() } }
    }

    fun total(): TokenTotals = TokenTotals.of(log)

    fun byPurpose(): Map<TokenPurpose, TokenTotals> =
        log.groupBy { it.purpose }.mapValues { TokenTotals.of(it.value) }.toSortedMap()

    /** One row per game day with any call, in order. Calls with no clock count as day 1. */
    fun byDay(): List<TokenDay> = log.groupBy { it.day ?: 1 }.toSortedMap().map { (day, calls) ->
        TokenDay(day, TokenTotals.of(calls), calls.groupBy { it.purpose }.mapValues { TokenTotals.of(it.value) }.toSortedMap())
    }

    private inner class Metered(
        private val inner: DecisionModel,
        private val purpose: TokenPurpose,
        private val price: TokenPrice?,
    ) : DecisionModel {
        override val id: String get() = inner.id
        override val capabilities: Capabilities get() = inner.capabilities

        override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
            val tick = tickNow()
            try {
                val d = inner.decide(request, schema)
                record(tick, purpose, id, price, d.usage, d.attempts.size, d.latency.inWholeMilliseconds, failed = false)
                return d
            } catch (e: DecisionFailed) {
                record(tick, purpose, id, price, e.usage, e.attempts.size, 0, failed = true)
                throw e
            }
        }
    }

    private fun record(tick: Long, purpose: TokenPurpose, model: String, price: TokenPrice?, usage: Usage, attempts: Int, latencyMs: Long, failed: Boolean) {
        val reading = clock?.reading(tick)
        log += TokenCall(
            tick, reading?.day, reading?.time, purpose, model,
            calls = attempts.coerceAtLeast(1), usage.promptTokens, usage.completionTokens, latencyMs,
            costUsd = price?.cost(usage), failed = failed,
        )
    }
}

/** Text for a run's end: tokens per game day by purpose, and what that implies for a long run. */
object TokenReport {
    fun format(ledger: TokenLedger, ticksPerMinute: Int, endTick: Long): String = buildString {
        val days = ledger.byDay()
        if (days.isEmpty()) return "tokens: no model calls"
        val priced = ledger.total().costUsd != null
        val purposes = ledger.byPurpose().keys.toList()
        appendLine("tokens by game day (prompt+completion, calls):")
        days.forEach { d ->
            val parts = purposes.joinToString("  ") { p -> d.byPurpose[p]?.let { "${p.name.lowercase()} ${it.promptTokens}+${it.completionTokens}" } ?: "${p.name.lowercase()} -" }
            val cost = if (priced) "  $%.4f".format(d.total.costUsd ?: 0.0) else ""
            appendLine("  day %2d: %8d tokens  %4d calls  %s%s".format(d.day, d.total.tokens, d.total.calls, parts, cost))
        }
        val total = ledger.total()
        val simDays = endTick.toDouble() / (ticksPerMinute * GameClock.MINUTES_PER_DAY)
        if (simDays > 0) {
            val perDay = total.tokens / simDays
            appendLine("total: ${total.promptTokens}+${total.completionTokens} tokens over %.2f game days = %.0f tokens/day (%.0f prompt, %.0f completion)".format(
                simDays, perDay, total.promptTokens / simDays, total.completionTokens / simDays,
            ))
            total.costUsd?.let { appendLine("cost: $%.4f over the run = $%.4f/day, $%.2f per 30 days".format(it, it / simDays, it / simDays * 30)) }
            appendLine("at that rate: %.1fM tokens per 30 game days".format(perDay * 30 / 1_000_000))
        }
        // Does a call cost more as the run goes on? Recall grows the prompt; this shows if it levels off.
        val decisions = ledger.calls().filter { it.purpose == TokenPurpose.Decision && !it.failed }
        if (decisions.size >= 2) {
            val perDay = decisions.groupBy { it.day ?: 1 }.toSortedMap().mapValues { (_, c) -> c.sumOf { it.promptTokens } / c.size }
            appendLine("prompt tokens per decision, by day: ${perDay.entries.joinToString(", ") { "d${it.key} ${it.value}" }}")
        }
    }.trimEnd()
}
