package dev.lilguy.app

import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.Scenario
import dev.lilguy.agent.TokenPrice
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.Endpoints
import dev.lilguy.decision.EndpointsFile
import dev.lilguy.decision.Scorer
import dev.lilguy.decision.withBeam
import dev.lilguy.sim.GameClock
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Which room the app runs, which model drives the guy, and where its decisions are logged.
 * Launchers build one: the desktop from `config/endpoints.yaml` and the shell environment, the TV
 * from files pushed over adb (`make tv-push-config`).
 */
class ModelSetup(
    /** A fresh model for each run. */
    val create: () -> DecisionModel,
    val scenario: Scenario = Scenario.Bedroom,
    /** The bedroom's clock scale, ticks per game minute: the first run's; the Run page changes it. */
    val ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
    /** Bounds one whole decision, retries included. */
    val timeout: Duration = 20.seconds,
    /** One JSONL file per run goes here; null to not log. */
    val logDir: File? = null,
    /** Shown in the dev view, e.g. why the configured endpoint couldn't be used. */
    val notice: String? = null,
    /** A fresh model for each run that writes long-term memories; null to record events without summarizing. */
    val memoryModel: (() -> DecisionModel)? = null,
    /** Bedroom: game days a run lasts before it ends, null for no end: the first run's; the Run page changes it. */
    val days: Int? = DEFAULT_DAYS,
    /** A fresh model for each run that writes his thoughts, his self-model and each day's commission; null and he isn't offered think. */
    val writerModel: (() -> DecisionModel)? = null,
    /** What each model costs, for the token report; null for free or unknown. */
    val prices: Prices = Prices(),
    /** The deciding model also draws his art, pick by pick; false for the scripted policy, which can't. */
    val paints: Boolean = false,
    /** A fresh scorer for each run that ranks recall and scores significance (typesafe); null for keywords. */
    val recallScorer: (() -> Scorer)? = null,
    /** Bedroom: items he starts with on top of the content's, id to count; a dev aid for screenshots (LILGUY_START_ITEMS). */
    val startItems: Map<String, Int> = emptyMap(),
) {
    class Prices(val decision: TokenPrice? = null, val memory: TokenPrice? = null, val writer: TokenPrice? = null, val recall: TokenPrice? = null)

    fun copy(
        memoryModel: (() -> DecisionModel)? = this.memoryModel,
        notice: String? = this.notice,
        writerModel: (() -> DecisionModel)? = this.writerModel,
        prices: Prices = this.prices,
        recallScorer: (() -> Scorer)? = this.recallScorer,
        startItems: Map<String, Int> = this.startItems,
    ) = ModelSetup(create, scenario, ticksPerMinute, timeout, logDir, notice, memoryModel, days, writerModel, prices, paints, recallScorer, startItems)

    /** The Run page's starting values; the viewer changes them from there. */
    val settings: RunSettings get() = RunSettings(days, ticksPerMinute = ticksPerMinute)

    fun logFor(run: Int, modelId: String): DecisionLog? =
        logDir?.let { DecisionLog(File(it, "run-%03d.jsonl".format(run)), run = "${it.name}-$run", model = modelId) }

    companion object {
        /** Fake model latency for the scripted policy, so a decision is visible as it happens. */
        val THINK_TIME = 600.milliseconds

        const val DEFAULT_DAYS = 3

        fun scripted(
            notice: String? = null,
            logDir: File? = null,
            scenario: Scenario = Scenario.Bedroom,
            ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
            days: Int? = DEFAULT_DAYS,
        ) = ModelSetup({ scenario.scriptedPolicy(THINK_TIME) }, scenario, ticksPerMinute, logDir = logDir, notice = notice, days = days)

        /**
         * The endpoint named [endpoint] (or the config's default agent endpoint) from [configFile].
         * Falls back to the scripted policy, with a notice saying why, when there's no config, no
         * endpoint chosen, or no key. [env] is the only place keys come from. Logs go in a new
         * timestamped directory under [logRoot]. Memories are written by [memoryEndpoint] (or the
         * config's `memory_endpoint`; "none" turns it off), whichever model decides; his thoughts,
         * self-model and commissions by [writerEndpoint] (or `writer_endpoint`) the same way; and
         * recall is scored by [recallEndpoint] (or `recall_endpoint`, else the deciding endpoint when
         * it's typesafe; "none" for keywords). [beam] overrides the deciding endpoint's `beam` (0 for
         * the flat list).
         */
        fun fromConfig(
            configFile: File,
            endpoint: String?,
            env: (String) -> String?,
            logRoot: File?,
            scenario: Scenario = Scenario.Bedroom,
            ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
            memoryEndpoint: String? = null,
            days: Int? = DEFAULT_DAYS,
            writerEndpoint: String? = null,
            recallEndpoint: String? = null,
            beam: Int? = null,
        ): ModelSetup {
            val logDir = logRoot?.let { File(it, "app-${scenario.id}-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))) }
            if (!configFile.isFile) {
                val notice = if (endpoint != null || memoryEndpoint != null || writerEndpoint != null) "no config at ${configFile.path}; using the scripted policy" else null
                return scripted(notice, logDir, scenario, ticksPerMinute, days)
            }
            val config = try {
                Endpoints.load(configFile).let { it.withBeam(endpoint ?: it.defaultEndpoint ?: "scripted", beam) }
            } catch (e: Exception) {
                return scripted("${e.message}; using the scripted policy", logDir, scenario, ticksPerMinute, days)
            }
            val notices = mutableListOf<String>()
            val writer = textWriter(config, memoryEndpoint ?: config.defaultMemoryEndpoint, env, notices, "memory", "memories won't be written")
            val writerName = writerEndpoint ?: config.defaultWriterEndpoint
            val textModel = textWriter(config, writerName, env, notices, "writer", "he won't think or keep a self-model")
            val base = scripted(null, logDir, scenario, ticksPerMinute, days)
            fun priceOf(name: String?) = name?.let { config.endpoints[it] }?.let(TokenPrice::of)
            var decisionPrice: TokenPrice? = null
            val deciding = endpoint ?: config.defaultEndpoint ?: "scripted"
            val agent = try {
                val name = deciding
                if (name == "scripted") {
                    base
                } else {
                    val spec = config.endpoints[name] ?: throw IllegalArgumentException("no endpoint '$name' in ${configFile.path}")
                    Endpoints.create(config, name, env).let { (it as? AutoCloseable)?.close() } // fail now on a missing key
                    decisionPrice = priceOf(name)
                    ModelSetup({ Endpoints.create(config, name, env) }, scenario, ticksPerMinute, spec.decisionTimeout, logDir, days = days, paints = true)
                }
            } catch (e: Exception) {
                notices.add(0, "${e.message}; using the scripted policy")
                base
            }
            // Recall is scored by a typesafe endpoint: the one named, or the deciding one when it can score.
            val recallName = (recallEndpoint ?: config.defaultRecallEndpoint)
                ?: deciding.takeIf { config.endpoints[it]?.kind == "typesafe" && agent !== base }
            val scorer = recallName?.takeIf { it != "none" }?.let { name ->
                try {
                    Endpoints.scorer(config, name, env).let { (it as? AutoCloseable)?.close() }
                    val factory: () -> Scorer = { Endpoints.scorer(config, name, env) }
                    factory
                } catch (e: Exception) {
                    notices += "${e.message}; recall goes by keywords"
                    null
                }
            }
            val prices = Prices(decisionPrice, priceOf(memoryEndpoint ?: config.defaultMemoryEndpoint), priceOf(writerName), priceOf(recallName))
            return agent.copy(memoryModel = writer, writerModel = textModel, notice = notices.joinToString("; ").ifEmpty { null }, prices = prices, recallScorer = scorer)
        }

        /**
         * A factory for a model that writes text ([what]: memories, or the writer), or null, with a
         * notice ending in [without] when one was asked for but can't be used.
         */
        private fun textWriter(config: EndpointsFile, name: String?, env: (String) -> String?, notices: MutableList<String>, what: String, without: String): (() -> DecisionModel)? {
            if (name == null || name == "none") return null
            return try {
                require(name in config.endpoints) { "no $what endpoint '$name'" }
                val probe = Endpoints.create(config, name, env)
                (probe as? AutoCloseable)?.close()
                require(!probe.capabilities.closedChoice) { "$what endpoint '$name' only picks from options" }
                val factory: () -> DecisionModel = { Endpoints.create(config, name, env) }
                factory
            } catch (e: Exception) {
                notices += "${e.message}; $without"
                null
            }
        }

        /**
         * `NAME=value` lines (an `export ` prefix and quotes allowed), `#` comments ignored. The TV has no shell environment, so
         * `make tv-push-config` writes the keys the config names into such a file on the device.
         * Dev only: a key on the TV can be read by anyone with adb access to it.
         */
        fun readEnvFile(file: File): Map<String, String> {
            if (!file.isFile) return emptyMap()
            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .map { it.removePrefix("export ").trim() }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'") }
        }
    }
}
