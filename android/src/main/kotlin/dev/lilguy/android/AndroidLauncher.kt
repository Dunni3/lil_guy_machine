package dev.lilguy.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import dev.lilguy.app.LilGuyGame
import dev.lilguy.app.ModelSetup
import dev.lilguy.agent.Scenario
import dev.lilguy.sim.GameClock
import java.io.File

class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useAccelerometer = false
            useCompass = false
            numSamples = 4
        }
        initialize(LilGuyGame(modelSetup()), config)
    }

    /**
     * Endpoint config pushed by `make tv-push-config` into the app's external files dir
     * (/sdcard/Android/data/dev.lilguy.machine/files): endpoints.yaml, plus secrets.env holding
     * the API keys it names. Keys on the TV are for development only. Pick the endpoint with
     * `make tv-run ENDPOINT=<name>` (an `endpoint` intent extra), or let the config's default
     * agent endpoint apply. With no config the scripted policy runs. Extras `scenario`
     * (bedroom or key-door) and `minute_ticks` (the clock scale) pick the room; `memory_endpoint`
     * the memory writer (default the config's `memory_endpoint`, "none" for off); `journal_endpoint`
     * the journal writer, the same way; `days` the
     * bedroom run length (0 for no end).
     */
    private fun modelSetup(): ModelSetup {
        val dir = getExternalFilesDir(null) ?: filesDir
        val env = ModelSetup.readEnvFile(File(dir, "secrets.env"))
        return ModelSetup.fromConfig(
            configFile = File(dir, "endpoints.yaml"),
            endpoint = intent?.getStringExtra("endpoint")?.takeIf { it.isNotBlank() },
            env = { env[it] },
            logRoot = File(dir, "runs"),
            scenario = intent?.getStringExtra("scenario")?.takeIf { it.isNotBlank() }?.let(Scenario::parse) ?: Scenario.Bedroom,
            ticksPerMinute = intent?.getIntExtra("minute_ticks", GameClock.DEFAULT_TICKS_PER_MINUTE) ?: GameClock.DEFAULT_TICKS_PER_MINUTE,
            memoryEndpoint = intent?.getStringExtra("memory_endpoint")?.takeIf { it.isNotBlank() },
            journalEndpoint = intent?.getStringExtra("journal_endpoint")?.takeIf { it.isNotBlank() },
            days = (intent?.getIntExtra("days", ModelSetup.DEFAULT_DAYS) ?: ModelSetup.DEFAULT_DAYS).takeIf { it > 0 },
        )
    }
}
