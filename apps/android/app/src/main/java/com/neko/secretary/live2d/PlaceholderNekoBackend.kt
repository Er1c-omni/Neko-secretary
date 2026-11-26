package com.neko.secretary.live2d

import android.opengl.GLES20
import com.neko.secretary.protocol.CharacterStateId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * Procedural chibi-cat silhouette rendered with a signed-distance-field fragment shader.
 *
 * Used when the Live2D Cubism SDK / model pack is not present (fresh clone, CI, OSS users), so the
 * overlay pipeline (transparent compositing, adaptive FPS, hit-testing, edge peek) is fully usable
 * without any closed-source binary. Outputs premultiplied alpha.
 */
class PlaceholderNekoBackend : Live2DModelBackend {
    override val name: String = "placeholder-sdf"

    private var program = 0
    private var aPos = 0
    private var uTime = 0
    private var uAspect = 0
    private var uBlink = 0
    private var uBounce = 0
    private var uTint = 0
    private var uGlasses = 0
    private var uSleepy = 0
    private var uAlert = 0
    private var uMouth = 0
    private var quad: FloatBuffer? = null
    private var aspect = 1f

    private var time = 0f
    private var nextBlinkAt = 2.5f
    private var blinkPhase = -1f
    private var bounceRemaining = 0f
    @Volatile private var mouthOpen = 0f
    @Volatile private var look = CharacterLook(CharacterStateId.PEEK_IDLE)

    override val isMotionActive: Boolean get() = bounceRemaining > 0f || mouthOpen > 0.02f

    override fun onGlContextCreated() {
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uAspect = GLES20.glGetUniformLocation(program, "uAspect")
        uBlink = GLES20.glGetUniformLocation(program, "uBlink")
        uBounce = GLES20.glGetUniformLocation(program, "uBounce")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uGlasses = GLES20.glGetUniformLocation(program, "uGlasses")
        uSleepy = GLES20.glGetUniformLocation(program, "uSleepy")
        uAlert = GLES20.glGetUniformLocation(program, "uAlert")
        uMouth = GLES20.glGetUniformLocation(program, "uMouth")
        quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(QUAD); position(0)
        }
    }

    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun applyLook(look: CharacterLook) {
        val changed = look.state != this.look.state || look.motion != this.look.motion
        this.look = look
        if (changed && look.state != CharacterStateId.PEEK_IDLE) bounceRemaining = BOUNCE_SECONDS
    }

    override fun onTap(xNorm: Float, yNorm: Float) {
        bounceRemaining = BOUNCE_SECONDS
    }

    override fun setMouthOpen(level: Float) {
        mouthOpen = level.coerceIn(0f, 1f)
    }

    override fun onDrawFrame(deltaSeconds: Float) {
        time += deltaSeconds
        bounceRemaining = (bounceRemaining - deltaSeconds).coerceAtLeast(0f)
        val blink = updateBlink(deltaSeconds)

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val l = look
        val secretary = l.costume == "secretary" ||
            l.state == CharacterStateId.SECRETARY_ACTIVE || l.state == CharacterStateId.SECRETARY_ALERT
        val tint = when {
            l.state == CharacterStateId.SLEEPY_NIGHT -> floatArrayOf(0.72f, 0.74f, 0.92f)
            secretary -> floatArrayOf(0.36f, 0.40f, 0.52f)
            else -> floatArrayOf(0.98f, 0.86f, 0.70f)
        }
        val alert = l.state == CharacterStateId.SECRETARY_ALERT || l.state == CharacterStateId.ANTI_PROCRASTINATE

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTime, time)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uBlink, blink)
        GLES20.glUniform1f(uBounce, bounceRemaining / BOUNCE_SECONDS)
        GLES20.glUniform3f(uTint, tint[0], tint[1], tint[2])
        GLES20.glUniform1f(uGlasses, if (secretary) 1f else 0f)
        GLES20.glUniform1f(uSleepy, if (l.state == CharacterStateId.SLEEPY_NIGHT) 1f else 0f)
        GLES20.glUniform1f(uAlert, if (alert) 1f else 0f)
        GLES20.glUniform1f(uMouth, mouthOpen)
        val buffer = quad ?: return
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun updateBlink(dt: Float): Float {
        if (blinkPhase < 0f && time >= nextBlinkAt) blinkPhase = 0f
        if (blinkPhase >= 0f) {
            blinkPhase += dt
            if (blinkPhase >= BLINK_SECONDS) {
                blinkPhase = -1f
                nextBlinkAt = time + 2.5f + Random.nextFloat() * 3f
                return 0f
            }
            val t = blinkPhase / BLINK_SECONDS
            return 1f - kotlin.math.abs(t * 2f - 1f)
        }
        return 0f
    }

    override fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
        quad = null
    }

    private companion object {
        const val BOUNCE_SECONDS = 1.2f
        const val BLINK_SECONDS = 0.14f
        val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        const val VERTEX_SHADER = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() {
                vUv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform float uTime;
            uniform float uAspect;
            uniform float uBlink;
            uniform float uBounce;
            uniform vec3 uTint;
            uniform float uGlasses;
            uniform float uSleepy;
            uniform float uAlert;
            uniform float uMouth;

            float sdTri(vec2 p, vec2 p0, vec2 p1, vec2 p2) {
                vec2 e0 = p1 - p0; vec2 e1 = p2 - p1; vec2 e2 = p0 - p2;
                vec2 v0 = p - p0; vec2 v1 = p - p1; vec2 v2 = p - p2;
                vec2 pq0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
                vec2 pq1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
                vec2 pq2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
                float s = sign(e0.x * e2.y - e0.y * e2.x);
                vec2 d = min(min(vec2(dot(pq0, pq0), s * (v0.x * e0.y - v0.y * e0.x)),
                                 vec2(dot(pq1, pq1), s * (v1.x * e1.y - v1.y * e1.x))),
                                 vec2(dot(pq2, pq2), s * (v2.x * e2.y - v2.y * e2.x)));
                return -sqrt(d.x) * sign(d.y);
            }

            float fill(float d) { return 1.0 - smoothstep(-0.012, 0.012, d); }

            void main() {
                vec2 p = vUv * 2.0 - 1.0;
                p.x *= uAspect;
                float breathe = 1.0 + 0.018 * sin(uTime * 2.2);
                p.y -= 0.10 * abs(sin(uBounce * 9.42)) * uBounce;
                p /= breathe;

                vec2 hp = p - vec2(0.0, 0.18);
                float head = length(hp * vec2(1.0, 1.12)) - 0.50;
                float earL = sdTri(hp, vec2(-0.46, 0.18), vec2(-0.12, 0.40), vec2(-0.40, 0.66));
                float earR = sdTri(hp, vec2(0.46, 0.18), vec2(0.12, 0.40), vec2(0.40, 0.66));
                vec2 bp = p - vec2(0.0, -0.58);
                float body = length(bp * vec2(1.25, 1.6)) - 0.46;
                float silhouette = min(min(head, body), min(earL, earR));
                float a = fill(silhouette);
                if (a <= 0.0) { gl_FragColor = vec4(0.0); return; }

                vec3 col = uTint;
                float innerL = sdTri(hp, vec2(-0.38, 0.26), vec2(-0.18, 0.40), vec2(-0.36, 0.56));
                float innerR = sdTri(hp, vec2(0.38, 0.26), vec2(0.18, 0.40), vec2(0.36, 0.56));
                col = mix(col, vec3(1.0, 0.68, 0.74), fill(min(innerL, innerR)));

                float openness = mix(1.0, 0.25, uSleepy) * (1.0 - uBlink * 0.92);
                vec2 eyeScale = vec2(1.0, 1.0 / max(openness, 0.08));
                float eyeL = length((hp - vec2(-0.19, -0.02)) * eyeScale) - 0.065;
                float eyeR = length((hp - vec2(0.19, -0.02)) * eyeScale) - 0.065;
                col = mix(col, vec3(0.16, 0.12, 0.14), fill(min(eyeL, eyeR)));

                float blushL = length((hp - vec2(-0.30, -0.15)) * vec2(1.0, 1.8)) - 0.07;
                float blushR = length((hp - vec2(0.30, -0.15)) * vec2(1.0, 1.8)) - 0.07;
                col = mix(col, vec3(1.0, 0.55, 0.6), 0.55 * fill(min(blushL, blushR)));

                float mouthH = mix(0.012, 0.085, uMouth);
                float mouth = length((hp - vec2(0.0, -0.19)) * vec2(1.0 / 0.07, 1.0 / mouthH)) - 1.0;
                col = mix(col, vec3(0.45, 0.15, 0.22), step(0.02, uMouth) * fill(mouth * 0.04));

                float ringL = abs(length(hp - vec2(-0.19, -0.02)) - 0.11) - 0.012;
                float ringR = abs(length(hp - vec2(0.19, -0.02)) - 0.11) - 0.012;
                col = mix(col, vec3(0.08, 0.08, 0.10), uGlasses * fill(min(ringL, ringR)));

                float pulse = uAlert * (0.5 + 0.5 * sin(uTime * 8.0)) * 0.35;
                col = mix(col, vec3(1.0, 0.25, 0.2), pulse);

                gl_FragColor = vec4(col * a, a);
            }
        """

        fun linkProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, v)
            GLES20.glAttachShader(program, f)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            check(status[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(program)}" }
            return program
        }

        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
    }
}
