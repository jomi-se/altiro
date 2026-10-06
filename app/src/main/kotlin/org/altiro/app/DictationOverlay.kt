package org.altiro.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.altiro.core.EditorState
import org.altiro.core.Phase
import org.altiro.core.Session

/** The real, non-focusable accessibility overlay. It never owns the host editor's focus. */
class DictationOverlay(
    private val context: Context,
    private val controller: DictationController,
    private val record: () -> Unit,
    private val insert: () -> Unit,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val preferences = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val dark
        get() =
            context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val ink
        get() = if (dark) Color.rgb(233, 242, 230) else Color.rgb(34, 38, 33)

    private val chalk
        get() = if (dark) Color.rgb(42, 52, 46) else Color.rgb(244, 246, 240)

    private val forest
        get() = if (dark) Color.rgb(182, 212, 190) else Color.rgb(56, 93, 72)

    private val layout =
        object : LinearLayout(context) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                    dispatchDrag(event) { super.dispatchTouchEvent(it) }
            }
            .apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                clipChildren = false
                clipToPadding = false
            }
    private val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
        }
    private val capsule = FrameLayout(context).apply { clipChildren = false }
    private val backdrop = BubbleBackdrop(context)
    private val controls =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    private val primary = iconButton(Glyph.MIC, "Start dictation")
    private val language =
        TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(
                10,
                14,
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP,
            )
            typeface =
                android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL,
                )
            setTextColor(ink)
            isClickable = true
            isFocusable = false
            background = touchBackground()
            contentDescription =
                "Dictation language. Tap to switch English and Spanish. Hold for the explicit Hide action."
            setOnClickListener {
                controller.selectLanguage(if (controller.language.value == "en") "es" else "en")
            }
            setOnLongClickListener {
                hideUntil = android.os.SystemClock.uptimeMillis() + 5000
                showMessage("Hide microphone in this app?")
                true
            }
        }
    private val cancel =
        iconButton(Glyph.CLOSE, "Cancel and discard recording").apply {
            background =
                GradientDrawable().apply {
                    setColor(withAlpha(chalk, 235))
                    cornerRadius = dp(24).toFloat()
                }
            setOnClickListener { controller.cancel() }
        }
    private val copy =
        iconButton(Glyph.COPY, "Copy transcript").apply { setOnClickListener { controller.copy() } }
    private val discard =
        iconButton(Glyph.DELETE, "Discard transcript").apply {
            setOnClickListener { controller.discard() }
        }
    private val hide =
        TextView(context).apply {
            text = "Hide"
            textSize = 13f
            maxLines = 1
            gravity = Gravity.CENTER
            isFocusable = false
            isClickable = true
            setAutoSizeTextTypeUniformWithConfiguration(
                10,
                13,
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP,
            )
            contentDescription = "Hide the floating microphone in this app"
            setOnClickListener {
                controller.disableCurrentApp()
                hideUntil = 0
                close()
            }
        }
    private val status =
        TextView(context).apply {
            textSize = 12f
            setTextColor(ink)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background =
                GradientDrawable().apply {
                    setColor(withAlpha(chalk, 235))
                    cornerRadius = dp(12).toFloat()
                }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    private val params =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            )
            .apply { gravity = Gravity.TOP or Gravity.LEFT }
    private var attached = false
    private var knownWindowId: Int? = null
    val windowId: Int?
        get() =
            layout.createAccessibilityNodeInfo().windowId.takeIf { attached && it >= 0 }
                ?: knownWindowId

    private var orientation = context.resources.configuration.orientation
    private var lastPhase: Phase? = null
    private var lastBusy = false
    private var message: String? = null
    private var messageUntil = 0L
    private var pending = false
    private var recording = false
    private var disposed = false
    private var draggingWindow = false
    private var dockRight = true
    private var paletteNight = dark
    private var hideUntil = 0L
    private val clearMessage = Runnable {
        if (!disposed) {
            message = null
            hideUntil = 0
            render(controller.editor.current, controller.session.value)
        }
    }

    init {
        restorePosition()
        capsule.addView(backdrop, FrameLayout.LayoutParams(dp(104), dp(52)))
        controls.addView(primary, LinearLayout.LayoutParams(dp(52), dp(52)))
        controls.addView(language, LinearLayout.LayoutParams(dp(52), dp(52)))
        capsule.addView(controls, FrameLayout.LayoutParams(dp(104), dp(52)))
        row.addView(capsule, LinearLayout.LayoutParams(dp(104), dp(52)))
        for (view in listOf(cancel, copy, discard, hide)) row.addView(
            view,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
        )
        layout.addView(row)
        layout.addView(
            status,
            LinearLayout.LayoutParams(dp(168), WindowManager.LayoutParams.WRAP_CONTENT),
        )
        primary.setOnClickListener {
            when {
                recording -> controller.stop()
                pending -> insert()
                else -> record()
            }
        }
        // No long-press handler on the primary action: slow Stop/Insert presses must click.
        primary.isLongClickable = false
        controls.layoutDirection = View.LAYOUT_DIRECTION_LTR
        capsule.layoutDirection = View.LAYOUT_DIRECTION_LTR
        applyPalette()
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampAndUpdate() }
        layout.setOnApplyWindowInsetsListener { _, insets ->
            layout.post { clampAndUpdate() }
            insets
        }
    }

    fun render(editor: EditorState, session: Session) {
        if (disposed) return
        val nativeBusy = controller.recognition.busy.value
        val busy = session.busy || nativeBusy
        if (busy && !lastBusy) {
            message = null
            hideUntil = 0
            layout.removeCallbacks(clearMessage)
        }
        val eligible =
            editor.identity?.displayId == 0 &&
                editor.connectionAvailable &&
                !editor.password &&
                !editor.blocked
        pending = session.text != null && !session.attemptConsumed
        if (
            editor.locked ||
                editor.identity?.displayId?.let { it != 0 } == true ||
                (!busy && !eligible)
        ) {
            close()
            return
        }
        recording = session.phase in setOf(Phase.STARTING, Phase.RECORDING)
        primary.isEnabled =
            recording ||
                (pending && eligible) ||
                (!busy &&
                    controller.models.ready.value &&
                    !controller.models.busy.value &&
                    !pending &&
                    eligible)
        primary.setImageDrawable(
            GlyphDrawable(
                when {
                    recording -> Glyph.STOP
                    pending -> Glyph.ARROW
                    else -> Glyph.MIC
                },
                if (recording) chalk else ink,
            )
        )
        primary.alpha = if (primary.isEnabled) 1f else 0.42f
        primary.contentDescription =
            when {
                recording -> "Stop and transcribe"
                pending -> "Insert transcript in selected field"
                busy -> "Microphone stopped; recognition working"
                else -> "Start dictation. Drag to move."
            }
        primary.stateDescription =
            when {
                recording -> "Recording"
                busy -> "Processing"
                pending -> "Text ready; insertion needs your action"
                else -> "Ready"
            }
        language.text =
            when (controller.language.value) {
                "auto" -> "Auto"
                else -> controller.language.value.uppercase(java.util.Locale.ROOT)
            }
        language.isEnabled = !busy && !controller.models.busy.value
        language.alpha = if (language.isEnabled) 1f else 0.55f
        language.stateDescription =
            if (busy) "Language fixed for this recording" else "${language.text} selected"
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        cancel.contentDescription =
            if (recording) "Cancel and discard recording" else "Cancel recognition"
        copy.visibility = if (pending && eligible) View.VISIBLE else View.GONE
        discard.visibility = copy.visibility
        hide.visibility =
            if (!busy && !pending && android.os.SystemClock.uptimeMillis() < hideUntil) View.VISIBLE
            else View.GONE
        val currentMessage = message?.takeIf {
            android.os.SystemClock.uptimeMillis() < messageUntil
        }
        val nextStatus =
            currentMessage
                ?: when {
                    recording -> "Recording · ${session.elapsedSeconds}s"
                    session.phase == Phase.STARTING -> "Starting microphone…"
                    busy ->
                        if (session.phase == Phase.TRANSCRIBING) controller.processingLabel()
                        else if (nativeBusy && !session.busy) "Cancelling…"
                        else "Finishing recording…"
                    pending -> session.message ?: "Text ready · tap to insert"
                    session.phase == Phase.DISPATCHED_UNCONFIRMED && session.dispatchFailed ->
                        "Insertion uncertain · check the field"
                    session.phase == Phase.FAILED -> session.message ?: "Recognition failed"
                    controller.models.busy.value -> "Preparing model…"
                    !controller.models.ready.value -> "Install a model in Altiro"
                    else -> ""
                }
        // Announce meaningful recovery changes, but never the elapsed-time/progress ticker.
        if (status.text.toString() != nextStatus) {
            status.accessibilityLiveRegion =
                if (lastPhase != session.phase || !busy || currentMessage != null)
                    View.ACCESSIBILITY_LIVE_REGION_POLITE
                else View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        status.text = nextStatus
        status.contentDescription =
            if (pending) session.message ?: "Text ready; focus an eligible field to insert or copy"
            else status.text
        status.visibility = if (status.text.isNotEmpty()) View.VISIBLE else View.GONE
        backdrop.recording = recording
        backdrop.active = busy
        if (lastPhase != session.phase || lastBusy != busy || !attached) {
            backdrop.setWorking(busy)
            lastPhase = session.phase
            lastBusy = busy
        }
        backdrop.invalidate()
        val flags = params.flags
        params.flags =
            if (busy) flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (!attached) {
            runCatching { windows.addView(layout, params) }
                .onSuccess {
                    attached = true
                    knownWindowId = layout.createAccessibilityNodeInfo().windowId.takeIf { it >= 0 }
                }
                .onFailure { backdrop.setWorking(false) }
            layout.post {
                knownWindowId = layout.createAccessibilityNodeInfo().windowId.takeIf { it >= 0 }
                clampAndUpdate()
            }
        } else if (flags != params.flags) windows.updateViewLayout(layout, params)
        // Layout changes and IME appearance can move the safe bounds, but never animate window
        // size.
        layout.post { clampAndUpdate() }
    }

    fun showMessage(text: String) {
        if (disposed) return
        message = text
        messageUntil = android.os.SystemClock.uptimeMillis() + 5000
        layout.removeCallbacks(clearMessage)
        render(controller.editor.current, controller.session.value)
        layout.postDelayed(clearMessage, 5000)
    }

    fun dispose() {
        disposed = true
        layout.removeCallbacks(clearMessage)
        close()
    }

    fun close() {
        backdrop.setWorking(false)
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (attached) {
            knownWindowId = windowId
            runCatching { windows.removeView(layout) }
            attached = false
        }
    }

    fun reposition() {
        if (paletteNight != dark) {
            paletteNight = dark
            applyPalette()
        }
        val next = context.resources.configuration.orientation
        if (next != orientation) {
            orientation = next
            restorePosition()
        }
        layout.post { clampAndUpdate() }
    }

    private fun restorePosition() {
        val b = windows.currentWindowMetrics.bounds
        dockRight =
            preferences.getBoolean(
                "dock-right-$orientation",
                preferences.getFloat("x-$orientation", 0.85f) >= 0.5f,
            )
        row.layoutDirection =
            if (dockRight) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        layout.gravity = if (dockRight) Gravity.RIGHT else Gravity.LEFT
        params.x = if (dockRight) b.width() else 0
        params.y = (preferences.getFloat("y-$orientation", 0.25f) * b.height()).toInt()
    }

    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false
    private var dragPointerId = 0

    // Own gestures at the window root so disabled microphone/language controls remain draggable.
    // Child views retain their real disabled semantics and receive normal taps below the slop.
    private fun dispatchDrag(event: MotionEvent, dispatch: (MotionEvent) -> Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragPointerId = event.getPointerId(0)
                downX = event.rawX
                downY = event.rawY
                originX = params.x
                originY = params.y
                dragging = false
                dispatch(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(dragPointerId)
                if (index < 0) return if (dragging) true else dispatch(event)
                val deltaX = event.getRawX(index) - downX
                val deltaY = event.getRawY(index) - downY
                if (!dragging && kotlin.math.hypot(deltaX, deltaY) > dragSlop) {
                    dragging = true
                    draggingWindow = true
                    originX = params.x
                    originY = params.y
                    val cancelled = MotionEvent.obtain(event)
                    cancelled.action = MotionEvent.ACTION_CANCEL
                    dispatch(cancelled)
                    cancelled.recycle()
                }
                if (dragging) {
                    params.x = originX + deltaX.toInt()
                    params.y = originY + deltaY.toInt()
                    clampAndUpdate(force = true)
                }
                return if (dragging) true else dispatch(event)
            }
            MotionEvent.ACTION_UP -> {
                val moved = dragging
                if (moved) {
                    val b = windows.currentWindowMetrics.bounds
                    dockRight = params.x + layout.width / 2 >= b.width() / 2
                    draggingWindow = false
                    row.layoutDirection =
                        if (dockRight) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    layout.gravity = if (dockRight) Gravity.RIGHT else Gravity.LEFT
                    clampAndUpdate(force = true)
                    preferences
                        .edit()
                        .putBoolean("dock-right-$orientation", dockRight)
                        .putFloat("y-$orientation", params.y.toFloat() / b.height())
                        .apply()
                }
                dragging = false
                return if (moved) true else dispatch(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                draggingWindow = false
                clampAndUpdate(force = true)
                return dispatch(event)
            }
            else -> return if (dragging) true else dispatch(event)
        }
    }

    private fun clampAndUpdate(force: Boolean = false) {
        if (!attached || layout.width == 0) return
        val metrics = windows.currentWindowMetrics
        val insets =
            metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
            )
        val oldX = params.x
        val oldY = params.y
        params.x =
            (if (draggingWindow) params.x
                else if (dockRight) metrics.bounds.width() - layout.width - insets.right
                else insets.left)
                .coerceIn(
                    insets.left,
                    maxOf(insets.left, metrics.bounds.width() - layout.width - insets.right),
                )
        params.y =
            params.y.coerceIn(
                insets.top,
                maxOf(
                    insets.top,
                    metrics.bounds.height() -
                        maxOf(layout.height, dp(60) + 2 * status.lineHeight + dp(4)) -
                        insets.bottom,
                ),
            )
        if (force || oldX != params.x || oldY != params.y) windows.updateViewLayout(layout, params)
    }

    private fun applyPalette() {
        language.setTextColor(ink)
        status.setTextColor(ink)
        hide.setTextColor(ink)
        language.background = touchBackground()
        primary.background = touchBackground()
        status.background =
            GradientDrawable().apply {
                setColor(withAlpha(chalk, 235))
                cornerRadius = dp(12).toFloat()
            }
        for (view in listOf(cancel, copy, discard, hide)) view.background =
            GradientDrawable().apply {
                setColor(withAlpha(chalk, 235))
                cornerRadius = dp(24).toFloat()
            }
        cancel.setImageDrawable(GlyphDrawable(Glyph.CLOSE, ink))
        copy.setImageDrawable(GlyphDrawable(Glyph.COPY, ink))
        discard.setImageDrawable(GlyphDrawable(Glyph.DELETE, ink))
        backdrop.invalidate()
    }

    private fun iconButton(glyph: Glyph, label: String) =
        ImageButton(context).apply {
            setImageDrawable(GlyphDrawable(glyph, ink))
            contentDescription = label
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = touchBackground()
            isFocusable = false
        }

    private fun touchBackground() =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(withAlpha(chalk, 255))
                    cornerRadius = dp(26).toFloat()
                },
            )
            addState(
                intArrayOf(),
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(26).toFloat()
                },
            )
        }

    private fun dp(value: Int) = (value * density).toInt()

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private inner class BubbleBackdrop(context: Context) : View(context) {
        var recording = false
        var active = false
        private var angle = 0f
        private var animation: ValueAnimator? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setWorking(working: Boolean) {
            if (!working) {
                animation?.cancel()
                animation = null
                angle = 0f
                invalidate()
                return
            }
            if (animation != null || !ValueAnimator.areAnimatorsEnabled()) return
            animation =
                ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1400
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener {
                        angle = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }

        override fun onDetachedFromWindow() {
            setWorking(false)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val d = density
            p.style = Paint.Style.FILL
            p.color = withAlpha(chalk, if (active) 238 else 86)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 26 * d, 26 * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = d * 0.7f
            p.color = withAlpha(if (dark) Color.WHITE else Color.WHITE, if (active) 110 else 85)
            canvas.drawRoundRect(d / 2, d / 2, width - d / 2, height - d / 2, 26 * d, 26 * d, p)
            p.style = Paint.Style.FILL
            p.color = if (recording) forest else withAlpha(chalk, 212)
            canvas.drawCircle(26 * d, 26 * d, 20 * d, p)
            if (!active) {
                p.color = withAlpha(chalk, 212)
                canvas.drawCircle(78 * d, 26 * d, 20 * d, p)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = d * 0.7f
            p.color = withAlpha(ink, 48)
            canvas.drawLine(52 * d, 14 * d, 52 * d, 38 * d, p)
            if (active) {
                p.strokeWidth = 2 * d
                p.strokeCap = Paint.Cap.ROUND
                p.color = forest
                canvas.drawArc(
                    RectF(3 * d, 3 * d, 49 * d, 49 * d),
                    angle,
                    if (recording) 38f else 100f,
                    false,
                    p,
                )
            }
        }
    }
}
