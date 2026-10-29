package dev.lilguy.app

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.profiling.GLProfiler
import com.badlogic.gdx.utils.ScreenUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.milliseconds

/**
 * The 2D dev view over the milestone 1 sim: the scripted guy solves the key-and-door room in a
 * loop. The 3D world renderer replaces [DevView] at milestone 2.
 */
class LilGuyGame : ApplicationAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: SimSession
    private lateinit var view: DevView
    private lateinit var profiler: GLProfiler

    // Perf log, every LOG_SECONDS: fps, worst frame, CPU time in update+draw (after the clear,
    // which is where the driver blocks for vsync), draw calls per frame.
    private var logTimer = 0f
    private var worstDelta = 0f
    private var frames = 0
    private var renderNanos = 0L
    private var drawCalls = 0

    override fun create() {
        Gdx.app.log(TAG, "GL ${Gdx.gl.glGetString(GL20.GL_RENDERER)}; " +
            "backbuffer ${Gdx.graphics.backBufferWidth}x${Gdx.graphics.backBufferHeight}")
        session = SimSession(scope, thinkTime = THINK_TIME)
        view = DevView()
        profiler = GLProfiler(Gdx.graphics).apply { enable() }
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        Gdx.input.inputProcessor = RemoteInput({ session }, view)
    }

    override fun resize(width: Int, height: Int) {
        Gdx.app.log(TAG, "resize ${width}x$height; backbuffer ${Gdx.graphics.backBufferWidth}x${Gdx.graphics.backBufferHeight}")
        view.resize(width, height)
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime
        ScreenUtils.clear(DevView.BACKGROUND)
        val start = System.nanoTime()
        session.update(delta)
        view.render(session, Gdx.graphics.framesPerSecond)
        renderNanos += System.nanoTime() - start
        drawCalls += profiler.drawCalls
        profiler.reset()
        frames++
        worstDelta = maxOf(worstDelta, delta)
        logTimer += delta
        if (logTimer >= LOG_SECONDS) {
            Gdx.app.log(TAG, "fps ${Gdx.graphics.framesPerSecond}; worst frame %.1f ms; update+draw cpu %.2f ms; %d draw calls/frame; backbuffer %dx%d; tick %d".format(
                worstDelta * 1000, renderNanos / 1e6 / frames, drawCalls / frames,
                Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight, session.current.tick,
            ))
            logTimer = 0f
            frames = 0
            worstDelta = 0f
            renderNanos = 0
            drawCalls = 0
        }
    }

    override fun dispose() {
        session.dispose()
        scope.cancel()
        view.dispose()
        profiler.disable()
    }

    companion object {
        const val TAG = "LilGuy"
        /** Fake model latency for the scripted policy, so a decision is visible as it happens. */
        val THINK_TIME = 600.milliseconds
        private const val LOG_SECONDS = 5f
    }
}
