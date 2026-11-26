package com.neko.secretary.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.neko.secretary.protocol.DirectiveAction
import kotlin.math.roundToInt

/**
 * Speech bubble in its own small overlay window, so the companion window stays tight around the
 * character (less transparent area to intercept touches). Supports optional quick-reply action
 * chips (e.g. `[喝完啦]` / `[5m后]`). Tap the text to dismiss.
 */
internal class BubbleWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onDismissed: () -> Unit,
    private val onActionClicked: (DirectiveAction) -> Unit = {}
) {
    private val density = context.resources.displayMetrics.density
    private var container: LinearLayout? = null
    private var textView: TextView? = null
    private var actionsRow: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var minimal = false

    val isShowing: Boolean get() = container?.isAttachedToWindow == true

    fun show(
        text: String,
        minimalStyle: Boolean,
        actions: List<DirectiveAction> = emptyList()
    ) {
        val view = container ?: build().also { container = it }
        minimal = minimalStyle
        styleFor(view, minimalStyle)
        textView?.text = text
        renderActions(if (minimalStyle) emptyList() else actions)
        if (!view.isAttachedToWindow) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            params = lp
            runCatching { windowManager.addView(view, lp) }
        }
    }

    /** Measure first so the bubble can be placed on the screen-interior side of the companion. */
    fun measuredWidth(screenWidth: Int): Int {
        val view = container ?: return 0
        val maxW = (screenWidth * 0.62f).roundToInt()
        textView?.maxWidth = maxW - dp(24)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return view.measuredWidth
    }

    fun moveTo(x: Int, y: Int) {
        val view = container ?: return
        val lp = params ?: return
        if (!view.isAttachedToWindow) return
        if (lp.x == x && lp.y == y) return
        lp.x = x
        lp.y = y
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    fun hide() {
        val view = container ?: return
        if (view.isAttachedToWindow) runCatching { windowManager.removeView(view) }
    }

    private fun build(): LinearLayout {
        val text = TextView(context).apply {
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setOnClickListener {
                hide()
                onDismissed()
            }
        }
        textView = text
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        actionsRow = row
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
            addView(row)
        }
    }

    private fun renderActions(actions: List<DirectiveAction>) {
        val row = actionsRow ?: return
        row.removeAllViews()
        if (actions.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        actions.forEachIndexed { idx, action ->
            val chip = TextView(context).apply {
                text = action.label
                textSize = 12f
                setTextColor(Color.rgb(88, 36, 56))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(235, 255, 224, 232))
                    setStroke(dp(1), Color.argb(140, 235, 120, 150))
                }
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener {
                    hide()
                    onActionClicked(action)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (idx > 0) marginStart = dp(6)
            }
            row.addView(chip, lp)
        }
    }

    private fun styleFor(view: LinearLayout, minimalStyle: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(if (minimalStyle) 8 else 16).toFloat()
            setColor(if (minimalStyle) Color.argb(200, 48, 48, 52) else Color.argb(240, 255, 250, 245))
            setStroke(dp(1), if (minimalStyle) Color.argb(120, 0, 0, 0) else Color.argb(90, 255, 140, 160))
        }
        view.background = bg
        val padH = dp(if (minimalStyle) 8 else 12)
        val padV = dp(if (minimalStyle) 4 else 8)
        view.setPadding(padH, padV, padH, padV)
        textView?.apply {
            setTextColor(if (minimalStyle) Color.argb(230, 235, 235, 235) else Color.rgb(60, 40, 48))
            textSize = if (minimalStyle) 12f else 14f
        }
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()
}
