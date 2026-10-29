package dev.lilguy.app

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.ScreenUtils

/**
 * Milestone 0: a lit, rotating cube plus an FPS readout, to prove the toolchain and measure the
 * Streamer's GPU. The world renderer replaces the cube at milestone 2.
 */
class LilGuyGame : ApplicationAdapter() {
    private lateinit var camera: PerspectiveCamera
    private lateinit var environment: Environment
    private lateinit var modelBatch: ModelBatch
    private lateinit var cubeModel: Model
    private lateinit var cube: ModelInstance
    private lateinit var hudBatch: SpriteBatch
    private lateinit var font: BitmapFont
    private var angle = 0f

    override fun create() {
        Gdx.app.log(TAG, "GL ${Gdx.gl.glGetString(GL20.GL_RENDERER)}; " +
            "backbuffer ${Gdx.graphics.backBufferWidth}x${Gdx.graphics.backBufferHeight}")

        camera = PerspectiveCamera(60f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat()).apply {
            position.set(3f, 2.5f, 3f)
            lookAt(0f, 0f, 0f)
            near = 0.1f
            far = 100f
            update()
        }
        environment = Environment().apply {
            set(ColorAttribute(ColorAttribute.AmbientLight, 0.35f, 0.35f, 0.4f, 1f))
            add(DirectionalLight().set(0.9f, 0.85f, 0.8f, -0.6f, -1f, -0.3f))
        }
        modelBatch = ModelBatch()
        cubeModel = ModelBuilder().createBox(
            1.5f, 1.5f, 1.5f,
            Material(ColorAttribute.createDiffuse(0.85f, 0.55f, 0.25f, 1f)),
            (Usage.Position or Usage.Normal).toLong(),
        )
        cube = ModelInstance(cubeModel)
        hudBatch = SpriteBatch()
        font = BitmapFont().apply { data.setScale(2f) }
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        hudBatch.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
    }

    override fun render() {
        angle = (angle + Gdx.graphics.deltaTime * 45f) % 360f
        cube.transform.setToRotation(Vector3.Y, angle).rotate(Vector3.X, 20f)

        ScreenUtils.clear(0.16f, 0.18f, 0.14f, 1f, true)
        modelBatch.begin(camera)
        modelBatch.render(cube, environment)
        modelBatch.end()

        hudBatch.begin()
        font.draw(hudBatch, "lil guy machine - ${Gdx.graphics.framesPerSecond} fps", 32f, Gdx.graphics.height - 32f)
        hudBatch.end()
    }

    override fun dispose() {
        modelBatch.dispose()
        cubeModel.dispose()
        hudBatch.dispose()
        font.dispose()
    }

    companion object {
        const val TAG = "LilGuy"
    }
}
