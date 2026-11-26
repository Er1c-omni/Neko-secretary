package com.neko.secretary.live2d

import android.content.Context
import android.util.Log
import com.neko.secretary.protocol.CharacterStateId
import java.io.File

/** What the character should look like / do. Mapped from CharacterDirective.live2d + state. */
data class CharacterLook(
    val state: CharacterStateId,
    val expression: String = "",
    val motion: String = "",
    val costume: String = "default"
)

/**
 * Rendering backend contract. Every method is invoked on the dedicated GL thread with the
 * EGL context current. Implementations must output premultiplied alpha over a transparent clear.
 */
interface Live2DModelBackend {
    val name: String

    /** (Re)create all GL resources. Called after the EGL context is (re)created. */
    fun onGlContextCreated()

    fun onSurfaceSizeChanged(width: Int, height: Int)

    fun onDrawFrame(deltaSeconds: Float)

    fun applyLook(look: CharacterLook)

    /** Tap reaction; coordinates normalised to the canvas, (0,0) = top-left. */
    fun onTap(xNorm: Float, yNorm: Float) = Unit

    /** True while a non-idle motion is playing (keeps 60 FPS boosted). */
    val isMotionActive: Boolean get() = false

    /** Free GL resources. The context is still current. */
    fun release()
}

/**
 * Model pack location. Live2D models are user-supplied at runtime (never committed to Git):
 * imported .zip packs live under filesDir/live2d/active, test packs may sit in assets/live2d.
 */
sealed interface Live2DModelSource {
    /** Path of the *.model3.json relative to the pack root. */
    val modelJson: String
    val displayName: String
    fun readBytes(context: Context, relativePath: String): ByteArray
}

data class FileModelSource(val rootDir: File, override val modelJson: String) : Live2DModelSource {
    override val displayName: String get() = modelJson.substringAfterLast('/').removeSuffix(".model3.json")
    override fun readBytes(context: Context, relativePath: String): ByteArray {
        val target = File(rootDir, relativePath).canonicalFile
        require(target.path.startsWith(rootDir.canonicalPath)) { "path escapes model root: $relativePath" }
        return target.readBytes()
    }
}

data class AssetModelSource(val assetDir: String, override val modelJson: String) : Live2DModelSource {
    override val displayName: String get() = modelJson.substringAfterLast('/').removeSuffix(".model3.json")
    override fun readBytes(context: Context, relativePath: String): ByteArray =
        context.assets.open("$assetDir/$relativePath").use { it.readBytes() }
}

/**
 * Picks the Cubism backend when the closed-source SDK was present at build time
 * (see app/build.gradle.kts `cubismEnabled`) and a model pack exists; otherwise falls back to the
 * procedural placeholder so the overlay, FPS governor and hit-testing work out of the box.
 */
object Live2DBackendFactory {
    private const val TAG = "Live2DBackendFactory"
    private const val CUBISM_BACKEND_CLASS = "com.neko.secretary.live2d.cubism.CubismModelBackend"

    fun create(context: Context, source: Live2DModelSource?): Live2DModelBackend {
        if (source != null) {
            try {
                val cls = Class.forName(CUBISM_BACKEND_CLASS)
                val ctor = cls.getConstructor(Context::class.java, Live2DModelSource::class.java)
                return ctor.newInstance(context.applicationContext, source) as Live2DModelBackend
            } catch (_: ClassNotFoundException) {
                Log.i(TAG, "Cubism SDK not bundled in this build; using placeholder renderer")
            } catch (t: Throwable) {
                Log.w(TAG, "Cubism backend unavailable, falling back to placeholder", t)
            }
        }
        return PlaceholderNekoBackend()
    }
}
