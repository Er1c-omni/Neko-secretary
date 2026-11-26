package com.neko.secretary.live2d.cubism

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismDefaultParameterId
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismFrameworkConfig
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.ICubismModelSetting
import com.live2d.sdk.cubism.framework.effect.CubismBreath
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.ACubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion
import com.live2d.sdk.cubism.framework.motion.CubismMotion
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import com.neko.secretary.live2d.CharacterLook
import com.neko.secretary.live2d.Live2DModelBackend
import com.neko.secretary.live2d.Live2DModelSource
import com.neko.secretary.protocol.CharacterStateId
import java.io.ByteArrayInputStream

/**
 * Real Live2D Cubism SDK 5 for Java/Android backend.
 *
 * Compiled conditionally via `app/build.gradle.kts` (`cubismEnabled`) only when the closed-source
 * Cubism Core library and CubismJavaFramework sources are placed locally under `apps/android/live2d-core/`.
 * Instantiated reflectively by [com.neko.secretary.live2d.Live2DBackendFactory].
 *
 * All methods run on [com.neko.secretary.live2d.Live2DRenderThread] with the RGBA8888 EGLContext current.
 */
class CubismModelBackend(
    private val context: Context,
    private val source: Live2DModelSource
) : Live2DModelBackend {

    override val name: String = "cubism:${source.displayName}"

    private var model: NekoCubismModel? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var pendingLook: CharacterLook? = null

    override val isMotionActive: Boolean
        get() = model?.isNonIdleMotionRunning == true

    override fun onGlContextCreated() {
        ensureFrameworkInitialized()
        val loaded = NekoCubismModel(context, source)
        loaded.load()
        model = loaded
        pendingLook?.let { loaded.applyLook(it) }
    }

    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        model?.updateProjection(viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(deltaSeconds: Float) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        model?.updateAndDraw(deltaSeconds)
    }

    override fun applyLook(look: CharacterLook) {
        pendingLook = look
        model?.applyLook(look)
    }

    override fun onTap(xNorm: Float, yNorm: Float) {
        model?.triggerTapMotion()
    }

    override fun setMouthOpen(level: Float) {
        model?.setMouthOpen(level.coerceIn(0f, 1f))
    }

    override fun release() {
        model?.releaseResources()
        model = null
    }

    private companion object {
        const val TAG = "CubismModelBackend"
        @Volatile
        private var frameworkStarted = false

        @Synchronized
        fun ensureFrameworkInitialized() {
            if (frameworkStarted) return
            val option = CubismFramework.Option().apply {
                logFunction = { msg -> Log.d(TAG, msg) }
                loggingLevel = CubismFrameworkConfig.LogLevel.VERBOSE
            }
            CubismFramework.startUp(option)
            CubismFramework.initialize()
            frameworkStarted = true
        }
    }
}

private class NekoCubismModel(
    private val context: Context,
    private val source: Live2DModelSource
) : CubismUserModel() {

    private val projection = CubismMatrix44.create()
    private val textureIds = mutableListOf<Int>()
    private val expressions = mutableMapOf<String, ACubismMotion>()
    private var modelSetting: ICubismModelSetting? = null
    private var eyeBlink: CubismEyeBlink? = null
    private var breath: CubismBreath? = null
    @Volatile private var mouthOpenValue = 0f

    fun setMouthOpen(value: Float) {
        mouthOpenValue = value
    }

    val isNonIdleMotionRunning: Boolean
        get() = !motionManager.isFinished && currentMotionPriority >= PRIORITY_NORMAL

    private var currentMotionPriority = PRIORITY_NONE

    fun load() {
        val settingBytes = source.readBytes(context, source.modelJson)
        val setting = CubismModelSettingJson(settingBytes)
        modelSetting = setting

        val mocPath = setting.modelFileName
        require(mocPath.isNotEmpty()) { "model3.json missing Moc file" }
        loadModel(source.readBytes(context, mocPath))

        val physicsPath = setting.physicsFileName
        if (physicsPath.isNotEmpty()) {
            runCatching { loadPhysics(source.readBytes(context, physicsPath)) }
        }
        val posePath = setting.poseFileName
        if (posePath.isNotEmpty()) {
            runCatching { loadPose(source.readBytes(context, posePath)) }
        }

        // Expressions
        for (i in 0 until setting.expressionCount) {
            val expName = setting.getExpressionName(i)
            val expPath = setting.getExpressionFileName(i)
            runCatching {
                val motion = loadExpression(source.readBytes(context, expPath))
                if (motion != null) expressions[expName] = motion
            }
        }

        // Eye blink + breathing
        eyeBlink = CubismEyeBlink.create(setting)
        breath = CubismBreath.create().apply {
            val params = mutableListOf<CubismBreath.BreathParameterData>()
            val idManager = CubismFramework.getIdManager()
            params.add(CubismBreath.BreathParameterData(idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_X.id), 0f, 15f, 6.5345f, 0.5f))
            params.add(CubismBreath.BreathParameterData(idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_Y.id), 0f, 8f, 3.5345f, 0.5f))
            params.add(CubismBreath.BreathParameterData(idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_Z.id), 0f, 10f, 5.5345f, 0.5f))
            params.add(CubismBreath.BreathParameterData(idManager.getId(CubismDefaultParameterId.ParameterId.BODY_ANGLE_X.id), 0f, 4f, 15.5345f, 0.5f))
            params.add(CubismBreath.BreathParameterData(idManager.getId(CubismDefaultParameterId.ParameterId.BREATH.id), 0.5f, 0.5f, 3.2345f, 0.5f))
            setParameters(params)
        }

        // Renderer & textures
        createRenderer(CubismRendererAndroid.create())
        val androidRenderer = getRenderer<CubismRendererAndroid>()
        androidRenderer.isPremultipliedAlpha(true)

        for (i in 0 until setting.textureCount) {
            val texPath = setting.getTextureFileName(i)
            if (texPath.isEmpty()) continue
            val glTexId = uploadTexture(source.readBytes(context, texPath))
            if (glTexId != 0) {
                textureIds.add(glTexId)
                androidRenderer.bindTexture(i, glTexId)
            }
        }

        startIdleMotion()
    }

    fun updateProjection(width: Int, height: Int) {
        projection.loadIdentity()
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        if (width > height) {
            projection.scale(1f / aspect, 1f)
        } else {
            projection.scale(1f, aspect)
        }
    }

    fun applyLook(look: CharacterLook) {
        if (look.expression.isNotBlank()) {
            expressions[look.expression]?.let { expressionManager.startMotion(it) }
        }
        val group = when {
            look.motion.isNotBlank() -> look.motion
            look.state == CharacterStateId.PEEK_IDLE -> "Idle"
            look.state == CharacterStateId.DOCK_STANDBY -> "Idle"
            else -> "TapBody"
        }
        val priority = if (group == "Idle") PRIORITY_IDLE else PRIORITY_NORMAL
        startGroupMotion(group, priority)
    }

    fun triggerTapMotion() {
        if (!startGroupMotion("TapBody", PRIORITY_NORMAL)) {
            startGroupMotion("Tap", PRIORITY_NORMAL)
        }
    }

    fun updateAndDraw(deltaSeconds: Float) {
        model.loadParameters()
        val motionUpdated = if (motionManager.isFinished) {
            startIdleMotion()
            false
        } else {
            motionManager.updateMotion(model, deltaSeconds)
        }
        model.saveParameters()

        if (!motionUpdated) {
            eyeBlink?.updateParameters(model, deltaSeconds)
        }
        expressionManager.updateMotion(model, deltaSeconds)
        breath?.updateParameters(model, deltaSeconds)
        if (mouthOpenValue > 0.001f) {
            val mouthId = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.MOUTH_OPEN_Y.id)
            model.addParameterValue(mouthId, mouthOpenValue, 0.8f)
        }
        physics?.evaluate(model, deltaSeconds)
        pose?.updateParameters(model, deltaSeconds)
        model.update()

        val mvp = CubismMatrix44.create()
        mvp.setMatrix(projection.array)
        mvp.multiplyByMatrix(modelMatrix)
        val androidRenderer = getRenderer<CubismRendererAndroid>()
        androidRenderer.setMvpMatrix(mvp)
        androidRenderer.drawModel()
    }

    fun releaseResources() {
        if (textureIds.isNotEmpty()) {
            GLES20.glDeleteTextures(textureIds.size, textureIds.toIntArray(), 0)
            textureIds.clear()
        }
        expressions.clear()
        delete()
    }

    private fun startIdleMotion() {
        currentMotionPriority = PRIORITY_IDLE
        startGroupMotion("Idle", PRIORITY_IDLE)
    }

    private fun startGroupMotion(group: String, priority: Int): Boolean {
        val setting = modelSetting ?: return false
        val count = setting.getMotionCount(group)
        if (count <= 0) return false
        val index = (0 until count).random()
        val path = setting.getMotionFileName(group, index)
        if (path.isEmpty()) return false
        val motion = runCatching {
            loadMotion(source.readBytes(context, path)) as? CubismMotion
        }.getOrNull() ?: return false
        val fadeIn = setting.getMotionFadeInTimeValue(group, index)
        if (fadeIn >= 0f) motion.fadeInTime = fadeIn
        val fadeOut = setting.getMotionFadeOutTimeValue(group, index)
        if (fadeOut >= 0f) motion.fadeOutTime = fadeOut
        currentMotionPriority = priority
        motionManager.startMotionPriority(motion, priority)
        return true
    }

    private fun uploadTexture(pngBytes: ByteArray): Int {
        val opts = BitmapFactory.Options().apply { inPremultiplied = true }
        val bitmap = BitmapFactory.decodeStream(ByteArrayInputStream(pngBytes), null, opts) ?: return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        bitmap.recycle()
        return ids[0]
    }

    private companion object {
        const val PRIORITY_NONE = 0
        const val PRIORITY_IDLE = 1
        const val PRIORITY_NORMAL = 2
    }
}
