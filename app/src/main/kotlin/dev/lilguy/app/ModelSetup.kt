package dev.lilguy.app

import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.Scenario
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.Endpoints
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
    /** A fresh model for each episode. */
    val create: () -> DecisionModel,
    val scenario: Scenario = Scenario.Bedroom,
    /** The bedroom's clock scale: ticks per game minute. */
    val ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
    /** Bounds one whole decision, retries included. */
    val timeout: Duration = 20.seconds,
    /** One JSONL file per episode goes here; null to not log. */
    val logDir: File? = null,
    /** Shown in the dev view, e.g. why the configured endpoint couldn't be used. */
    val notice: String? = null,
) {
    fun logFor(episode: Int, modelId: String): DecisionLog? =
        logDir?.let { DecisionLog(File(it, "episode-%03d.jsonl".format(episode)), run = "${it.name}-$episode", model = modelId) }

    companion object {
        /** Fake model latency for the scripted policy, so a decision is visible as it happens. */
        val THINK_TIME = 600.milliseconds

        fun scripted(
            notice: String? = null,
            logDir: File? = null,
            scenario: Scenario = Scenario.Bedroom,
            ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
        ) = ModelSetup({ scenario.scriptedPolicy(THINK_TIME) }, scenario, ticksPerMinute, logDir = logDir, notice = notice)

        /**
         * The endpoint named [endpoint] (or the config's default agent endpoint) from [configFile].
         * Falls back to the scripted policy, with a notice saying why, when there's no config, no
         * endpoint chosen, or no key. [env] is the only place keys come from. Logs go in a new
         * timestamped directory under [logRoot].
         */
        fun fromConfig(
            configFile: File,
            endpoint: String?,
            env: (String) -> String?,
            logRoot: File?,
            scenario: Scenario = Scenario.Bedroom,
            ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
        ): ModelSetup {
            val logDir = logRoot?.let { File(it, "app-${scenario.id}-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))) }
            fun scripted(notice: String? = null) = scripted(notice, logDir, scenario, ticksPerMinute)
            if (!configFile.isFile) {
                return scripted(if (endpoint != null) "no config at ${configFile.path}; using the scripted policy" else null)
            }
            return try {
                val config = Endpoints.load(configFile)
                val name = endpoint ?: config.defaultEndpoint ?: return scripted()
                if (name == "scripted") return scripted()
                val spec = config.endpoints[name] ?: throw IllegalArgumentException("no endpoint '$name' in ${configFile.path}")
                Endpoints.create(config, name, env).let { (it as? AutoCloseable)?.close() } // fail now on a missing key
                ModelSetup({ Endpoints.create(config, name, env) }, scenario, ticksPerMinute, spec.decisionTimeout, logDir)
            } catch (e: Exception) {
                scripted("${e.message}; using the scripted policy")
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
