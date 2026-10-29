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

/**
 * The 2D dev view over one run of the room [setup] names, driven by whichever model it names (the
 * scripted policy unless an endpoint is configured). Runs start, pause and restart from the
 * remote ([RemoteInput]); nothing starts on its own. The 3D world renderer replaces [DevView]
 * later.
 */
class LilGuyGame(private val setup: ModelSetup = ModelSetup.scripted()) : ApplicationAdapter() {
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
        setup.notice?.let { Gdx.app.error(TAG, it) }
        session = SimSession(scope, setup)
        Gdx.app.log(TAG, "model ${session.runtime.modelId}; memory ${session.memory.writer ?: "off"}; decision logs ${setup.logDir?.path ?: "off"}")
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
            Gdx.app.log(TAG, "fps ${Gdx.graphics.framesPerSecond}; worst frame %.1f ms; update+draw cpu %.2f ms; %d draw calls/frame; backbuffer %dx%d; %s tick %d".format(
                worstDelta * 1000, renderNanos / 1e6 / frames, drawCalls / frames,
                Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight, session.state.name.lowercase(), session.current.tick,
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
        private const val LOG_SECONDS = 5f
    }
}
