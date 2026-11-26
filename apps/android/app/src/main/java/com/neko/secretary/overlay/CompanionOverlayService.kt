package com.neko.secretary.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.app.ServiceCompat
import com.neko.secretary.MainActivity
import com.neko.secretary.live2d.CharacterLook
import com.neko.secretary.live2d.CharacterStateMachine
import com.neko.secretary.live2d.FramePacingPolicy
import com.neko.secretary.live2d.Live2DModelRepository
import com.neko.secretary.live2d.Live2DTextureRenderer
import com.neko.secretary.live2d.RenderConditions
import com.neko.secretary.protocol.AssistantMode
import com.neko.secretary.protocol.CharacterDirective
import com.neko.secretary.protocol.CharacterStateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service hosting the TYPE_APPLICATION_OVERLAY Live2D companion.
 *
 * Responsibilities (Week 1 Day 2–3):
 * - Transparent TextureView window rendered by [Live2DTextureRenderer] on its own EGL thread.
 * - Drag anywhere -> release snaps to the nearest (or flung) edge and tucks into PEEK (42% visible).
 *   Tap expands / toggles the speech bubble; idle auto-retracts back to the edge.
 * - Click-through: touches on transparent canvas pixels flip the window to FLAG_NOT_TOUCHABLE for a
 *   short grace period (alpha clamped to InputManager max obscuring opacity on Android 12+), so the
 *   app underneath receives the next taps. The window itself stays tight around the character.
 * - 职场防社死 one-tap stealth (notification action / QS tile / console): collapses to a tiny badge,
 *   GL loop parked at 0 FPS, bubbles become minimal and silent.
 * - Screen off / keyguard -> 0 FPS. 60/20/10/0 governed by [FramePacingPolicy].
 */
class CompanionOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var renderer: Live2DTextureRenderer
    private lateinit var bubble: BubbleWindow
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var badge: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var density = 1f
    private var petWidth = 0
    private var petHeight = 0
    private var badgeWidth = 0
    private var badgeHeight = 0
    private var edgeMargin = 0

    private var side = DockSide.RIGHT
    private var docked = true
    private var stealthLayout = false
    private var screenInteractive = true
    private var interacting = false
    private var boostUntil = 0L
    private var passThroughActive = false
    private var badgeUnread = false
    private var lastDirectiveId: String? = null
    private var snapAnimator: ValueAnimator? = null

    // touch tracking
    private var touchSlop = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var dragging = false
    private var longPressed = false
    private var velocityTracker: VelocityTracker? = null

    private val refreshFpsRunnable = Runnable { refreshFps() }
    private val autoRetractRunnable = Runnable { retractToEdge() }
    private val settleRunnable = Runnable { CharacterStateMachine.settle() }
    private val endPassThroughRunnable = Runnable { setPassThrough(false) }
    private val longPressRunnable = Runnable { onLongPress() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenInteractive = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> false
                Intent.ACTION_SCREEN_ON -> !isKeyguardLocked()
                Intent.ACTION_USER_PRESENT -> true
                else -> screenInteractive
            }
            if (!screenInteractive) bubble.hide()
            refreshFps()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(stealth = OverlayPrefs.stealth(this)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; stopping overlay")
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        density = resources.displayMetrics.density
        petWidth = dp(PET_WIDTH_DP)
        petHeight = dp(PET_HEIGHT_DP)
        badgeWidth = dp(BADGE_WIDTH_DP)
        badgeHeight = dp(BADGE_HEIGHT_DP)
        edgeMargin = dp(EDGE_MARGIN_DP)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        StealthController.restore(this)
        renderer = Live2DTextureRenderer(this) { Live2DModelRepository.resolveActiveModel(this) }
        bubble = BubbleWindow(this, windowManager) { onBubbleDismissed() }
        side = OverlayPrefs.side(this)
        screenInteractive = getSystemService(PowerManager::class.java).isInteractive && !isKeyguardLocked()

        attachOverlay()
        registerScreenReceiver()
        observeState()
        OverlayRuntime._running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_STEALTH -> StealthController.toggle(this)
            ACTION_STEALTH_ON -> StealthController.set(this, true)
            ACTION_STEALTH_OFF -> StealthController.set(this, false)
            ACTION_RELOAD_MODEL -> if (::renderer.isInitialized) renderer.reloadModel()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (root == null) return
        snapAnimator?.cancel()
        layoutForCurrentMode(animate = false)
    }

    override fun onDestroy() {
        OverlayRuntime._running.value = false
        OverlayRuntime._targetFps.value = 0
        OverlayRuntime._measuredFps.value = 0
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        if (::bubble.isInitialized) bubble.hide()
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        if (::renderer.isInitialized) renderer.release()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ window

    @SuppressLint("ClickableViewAccessibility")
    private fun attachOverlay() {
        val texture = TextureView(this).apply {
            isOpaque = false
            layoutParams = FrameLayout.LayoutParams(petWidth, petHeight, Gravity.CENTER)
        }
        renderer.attach(texture)
        textureView = texture

        val badgeView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(badgeWidth, badgeHeight, Gravity.CENTER)
            visibility = View.GONE
            contentDescription = "Neko stealth badge"
            setOnClickListener { onBadgeTap() }
            setOnLongClickListener {
                StealthController.set(this@CompanionOverlayService, false)
                true
            }
        }
        badge = badgeView
        styleBadge()

        val container = FrameLayout(this).apply {
            addView(texture)
            addView(badgeView)
            setOnTouchListener { _, event -> onPetTouch(event) }
        }
        root = container

        params = WindowManager.LayoutParams(
            petWidth,
            petHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "NekoCompanion"
        }
        stealthLayout = CharacterStateMachine.stealthOffice.value
        applyModeViews()
        positionForMode()
        windowManager.addView(container, params)
    }

    private fun applyModeViews() {
        textureView?.visibility = if (stealthLayout) View.GONE else View.VISIBLE
        badge?.visibility = if (stealthLayout) View.VISIBLE else View.GONE
        params.width = if (stealthLayout) badgeWidth else petWidth
        params.height = if (stealthLayout) badgeHeight else petHeight
    }

    private fun positionForMode() {
        val area = screenArea()
        val h = params.height
        params.y = EdgeDocking.clampY(EdgeDocking.ratioToY(OverlayPrefs.yRatio(this), h, area), h, area)
        params.x = when {
            stealthLayout -> EdgeDocking.badgeX(side, badgeWidth, area.width)
            docked -> EdgeDocking.peekX(side, petWidth, area.width)
            else -> EdgeDocking.expandedX(side, petWidth, area.width, edgeMargin)
        }
    }

    private fun layoutForCurrentMode(animate: Boolean) {
        val view = root ?: return
        applyModeViews()
        if (animate && !stealthLayout) {
            val area = screenArea()
            val targetX = if (docked) EdgeDocking.peekX(side, petWidth, area.width)
            else EdgeDocking.expandedX(side, petWidth, area.width, edgeMargin)
            params.y = EdgeDocking.clampY(params.y, params.height, area)
            animateX(targetX)
        } else {
            positionForMode()
            updateLayout(view)
        }
        repositionBubble()
    }

    private fun updateLayout(view: View? = root) {
        if (view?.isAttachedToWindow == true) runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun animateX(targetX: Int) {
        snapAnimator?.cancel()
        val from = params.x
        if (from == targetX) {
            updateLayout()
            return
        }
        snapAnimator = ValueAnimator.ofInt(from, targetX).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                params.x = it.animatedValue as Int
                updateLayout()
                repositionBubble()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapAnimator = null
                    refreshFps()
                }
            })
            start()
        }
        refreshFps()
    }

    // ------------------------------------------------------------------ touch

    private fun onPetTouch(event: MotionEvent): Boolean {
        if (stealthLayout) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!renderer.hitTest(event.x, event.y)) {
                    // Transparent canvas: release the window to the app below for the next taps.
                    setPassThrough(true)
                    return false
                }
                snapAnimator?.cancel()
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = params.x
                downParamY = params.y
                dragging = false
                longPressed = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { trackRaw(it, event) }
                setInteracting(true)
                mainHandler.removeCallbacks(autoRetractRunnable)
                mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.let { trackRaw(it, event) }
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (docked) {
                        docked = false
                        OverlayRuntime._docked.value = false
                    }
                    bubble.hide()
                }
                if (dragging) {
                    val area = screenArea()
                    params.x = downParamX + dx.roundToInt()
                    params.y = EdgeDocking.clampY(downParamY + dy.roundToInt(), params.height, area)
                    updateLayout()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                val tracker = velocityTracker
                var vx = 0f
                if (tracker != null) {
                    trackRaw(tracker, event)
                    tracker.computeCurrentVelocity(1000)
                    vx = tracker.xVelocity
                    tracker.recycle()
                }
                velocityTracker = null
                when {
                    dragging -> dockAfterDrag(vx)
                    event.actionMasked == MotionEvent.ACTION_UP && !longPressed -> onTap(event.x, event.y)
                }
                dragging = false
                setInteracting(false)
                return true
            }
        }
        return false
    }

    private fun trackRaw(tracker: VelocityTracker, event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun dockAfterDrag(velocityX: Float) {
        val area = screenArea()
        side = EdgeDocking.chooseSide(params.x, petWidth, area.width, velocityX)
        docked = true
        OverlayRuntime._docked.value = true
        OverlayPrefs.saveDock(this, side, EdgeDocking.yToRatio(params.y, params.height, area))
        animateX(EdgeDocking.peekX(side, petWidth, area.width))
    }

    private fun onTap(x: Float, y: Float) {
        renderer.onTap(x / petWidth.coerceAtLeast(1), y / petHeight.coerceAtLeast(1))
        boost(TAP_BOOST_MS)
        if (docked) {
            expandFromEdge()
            showBubble(currentBubbleText())
        } else if (bubble.isShowing) {
            bubble.hide()
            retractToEdge()
        } else {
            showBubble(currentBubbleText())
        }
        scheduleAutoRetract()
    }

    private fun onLongPress() {
        longPressed = true
        boost(TAP_BOOST_MS)
        // Week 1 Day 4 hook: VoicePipeline long-press streaming ASR starts here.
        if (docked) expandFromEdge()
        showBubble("（长按说话：流式 ASR 将在 Day 4 接入）")
        scheduleAutoRetract()
    }

    private fun setInteracting(value: Boolean) {
        if (interacting == value) return
        interacting = value
        renderer.setInteracting(value)
        refreshFps()
    }

    private fun setPassThrough(enabled: Boolean) {
        val view = root ?: return
        mainHandler.removeCallbacks(endPassThroughRunnable)
        if (enabled) mainHandler.postDelayed(endPassThroughRunnable, PASS_THROUGH_GRACE_MS)
        if (passThroughActive == enabled) return
        passThroughActive = enabled
        OverlayRuntime._passThrough.value = enabled
        if (enabled) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Android 12+ blocks touches through overlays more opaque than this threshold.
            params.alpha = min(1f, maxObscuringOpacity())
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.alpha = 1f
        }
        updateLayout(view)
    }

    private fun maxObscuringOpacity(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: DEFAULT_MAX_OBSCURING
        } else {
            1f
        }

    // ------------------------------------------------------------------ peek / expand / bubble

    private fun expandFromEdge() {
        if (!docked || stealthLayout) return
        docked = false
        OverlayRuntime._docked.value = false
        animateX(EdgeDocking.expandedX(side, petWidth, screenArea().width, edgeMargin))
    }

    private fun retractToEdge() {
        mainHandler.removeCallbacks(autoRetractRunnable)
        bubble.hide()
        if (stealthLayout || interacting) return
        if (!docked) {
            docked = true
            OverlayRuntime._docked.value = true
            animateX(EdgeDocking.peekX(side, petWidth, screenArea().width))
        }
    }

    private fun scheduleAutoRetract() {
        mainHandler.removeCallbacks(autoRetractRunnable)
        mainHandler.postDelayed(autoRetractRunnable, AUTO_RETRACT_MS)
    }

    private fun currentBubbleText(): String =
        CharacterStateMachine.lastDirective.value?.text?.takeIf { it.isNotBlank() }
            ?: if (CharacterStateMachine.mode.value == AssistantMode.SECRETARY) "指挥官，待命中。" else "喵~ 我在边上陪着你哦"

    private fun showBubble(text: String, minimal: Boolean = stealthLayout) {
        if (!screenInteractive) return
        bubble.show(text, minimal)
        repositionBubble()
    }

    private fun repositionBubble() {
        if (!bubble.isShowing) return
        val area = screenArea()
        val w = bubble.measuredWidth(area.width)
        val petW = if (stealthLayout) badgeWidth else petWidth
        val x = EdgeDocking.bubbleX(side, params.x, petW, w, area.width, dp(4))
        val y = EdgeDocking.clampY(params.y + dp(8), dp(48), area)
        bubble.moveTo(x, y)
    }

    private fun onBubbleDismissed() {
        mainHandler.removeCallbacks(settleRunnable)
        CharacterStateMachine.settle()
        scheduleAutoRetract()
    }

    private fun onBadgeTap() {
        badgeUnread = false
        styleBadge()
        val text = CharacterStateMachine.lastDirective.value?.text?.takeIf { it.isNotBlank() } ?: "静默中"
        showBubble(text, minimal = true)
        mainHandler.removeCallbacks(autoRetractRunnable)
        mainHandler.postDelayed({ bubble.hide() }, STEALTH_BUBBLE_MS)
    }

    private fun styleBadge() {
        badge?.background = GradientDrawable().apply {
            cornerRadius = dp(BADGE_WIDTH_DP).toFloat()
            setColor(if (badgeUnread) Color.argb(210, 255, 120, 140) else Color.argb(150, 90, 90, 96))
        }
    }

    // ------------------------------------------------------------------ state

    private fun observeState() {
        scope.launch {
            combine(CharacterStateMachine.currentState, CharacterStateMachine.mode) { state, mode -> state to mode }
                .distinctUntilChanged()
                .collect { (state, mode) -> onStateChanged(state, mode) }
        }
        scope.launch {
            CharacterStateMachine.lastDirective.collect { directive -> directive?.let { onDirective(it) } }
        }
        scope.launch {
            CharacterStateMachine.stealthOffice.collect { stealth -> onStealthChanged(stealth) }
        }
        scope.launch { renderer.motionActive.collect { refreshFps() } }
        scope.launch { renderer.measuredFps.collect { OverlayRuntime._measuredFps.value = it } }
        scope.launch { renderer.backendName.collect { OverlayRuntime._backend.value = it } }
    }

    private fun onStateChanged(state: CharacterStateId, mode: AssistantMode) {
        val directive = CharacterStateMachine.lastDirective.value
        renderer.applyLook(
            CharacterLook(
                state = state,
                expression = directive?.expression.orEmpty(),
                motion = directive?.motion.orEmpty(),
                costume = directive?.costume?.takeIf { it.isNotBlank() }
                    ?: if (mode == AssistantMode.SECRETARY) "secretary" else "default"
            )
        )
        refreshFps()
    }

    private fun onDirective(directive: CharacterDirective) {
        if (directive.directiveId == lastDirectiveId) return
        lastDirectiveId = directive.directiveId
        if (stealthLayout || directive.stealthOverride) {
            badgeUnread = true
            styleBadge()
            return
        }
        boost(DIRECTIVE_BOOST_MS)
        if (directive.state != CharacterStateId.PEEK_IDLE && directive.state != CharacterStateId.DOCK_STANDBY) {
            expandFromEdge()
            if (directive.text.isNotBlank()) showBubble(directive.text, minimal = false)
            mainHandler.removeCallbacks(settleRunnable)
            mainHandler.postDelayed(settleRunnable, DIRECTIVE_SETTLE_MS)
            scheduleAutoRetract()
        }
    }

    private fun onStealthChanged(stealth: Boolean) {
        updateNotification(stealth)
        if (stealth == stealthLayout || root == null) return
        stealthLayout = stealth
        snapAnimator?.cancel()
        bubble.hide()
        if (stealth) {
            setPassThrough(false)
            docked = true
        } else {
            badgeUnread = false
            styleBadge()
            docked = true
        }
        OverlayRuntime._docked.value = docked
        layoutForCurrentMode(animate = false)
        refreshFps()
    }

    private fun boost(durationMs: Long) {
        boostUntil = maxOf(boostUntil, SystemClock.uptimeMillis() + durationMs)
        refreshFps()
    }

    private fun refreshFps() {
        if (!::renderer.isInitialized) return
        val now = SystemClock.uptimeMillis()
        val conditions = RenderConditions(
            state = CharacterStateMachine.currentState.value,
            screenInteractive = screenInteractive,
            overlayVisible = root?.isAttachedToWindow != false && !stealthLayout,
            interacting = interacting || snapAnimator != null,
            motionActive = now < boostUntil || renderer.motionActive.value,
            docked = docked
        )
        val fps = FramePacingPolicy.targetFps(conditions)
        renderer.setTargetFps(fps)
        OverlayRuntime._targetFps.value = fps
        mainHandler.removeCallbacks(refreshFpsRunnable)
        if (boostUntil > now) mainHandler.postDelayed(refreshFpsRunnable, boostUntil - now + 16)
    }

    // ------------------------------------------------------------------ system

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun isKeyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun screenArea(): ScreenArea {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            ScreenArea(metrics.bounds.width(), metrics.bounds.height(), insets.top, insets.bottom)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            ScreenArea(dm.widthPixels, dm.heightPixels, statusBarHeightLegacy(), 0)
        }
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun statusBarHeightLegacy(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()

    // ------------------------------------------------------------------ notification

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Companion Overlay", NotificationManager.IMPORTANCE_MIN)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(stealth: Boolean): Notification {
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val toggle = PendingIntent.getService(this, 1, intent(this, ACTION_TOGGLE_STEALTH), immutable)
        val stop = PendingIntent.getService(this, 2, intent(this, ACTION_STOP), immutable)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), immutable)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Neko-Secretary")
            .setContentText(if (stealth) "防社死隐身中 · 0 FPS · 静音" else "桌宠贴边潜伏中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, if (stealth) "恢复显示" else "一键隐身", toggle).build()
            )
            .addAction(Notification.Action.Builder(null, "收起桌宠", stop).build())
            .build()
    }

    private fun updateNotification(stealth: Boolean) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(stealth))
    }

    companion object {
        private const val TAG = "CompanionOverlay"
        private const val CHANNEL_ID = "neko_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_TOGGLE_STEALTH = "com.neko.secretary.overlay.TOGGLE_STEALTH"
        const val ACTION_STEALTH_ON = "com.neko.secretary.overlay.STEALTH_ON"
        const val ACTION_STEALTH_OFF = "com.neko.secretary.overlay.STEALTH_OFF"
        const val ACTION_RELOAD_MODEL = "com.neko.secretary.overlay.RELOAD_MODEL"
        const val ACTION_STOP = "com.neko.secretary.overlay.STOP"

        private const val PET_WIDTH_DP = 132
        private const val PET_HEIGHT_DP = 168
        private const val BADGE_WIDTH_DP = 14
        private const val BADGE_HEIGHT_DP = 40
        private const val EDGE_MARGIN_DP = 4

        private const val SNAP_DURATION_MS = 240L
        private const val TAP_BOOST_MS = 1_500L
        private const val DIRECTIVE_BOOST_MS = 3_500L
        private const val DIRECTIVE_SETTLE_MS = 10_000L
        private const val AUTO_RETRACT_MS = 8_000L
        private const val STEALTH_BUBBLE_MS = 3_000L
        private const val PASS_THROUGH_GRACE_MS = 1_500L
        private const val DEFAULT_MAX_OBSCURING = 0.8f

        fun intent(context: Context, action: String? = null): Intent =
            Intent(context, CompanionOverlayService::class.java).apply { this.action = action }

        fun start(context: Context, action: String? = null) {
            context.startForegroundService(intent(context, action))
        }
    }
}
