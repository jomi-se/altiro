package org.altiro.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.altiro.core.EditorState
import org.altiro.core.Phase
import org.altiro.core.RecognitionStage
import org.altiro.core.Session
import org.altiro.core.Wav

/** The real, non-focusable accessibility overlay. It never owns the host editor's focus. */
class DictationOverlay(
    private val context: Context,
    private val controller: DictationController,
    private val record: () -> Unit,
    private val insert: () -> Unit,
    private val openApp: () -> Unit,
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
    // Room around the capsule for its soft shadow; the window is clamped including this inset.
    private val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipChildren = false
            clipToPadding = false
        }
    private val material = CapsuleMaterial()
    // The capsule mirrors with the dock so the primary stays at the screen edge and never moves
    // under a finger when status text widens the capsule toward the interior.
    private val capsule =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = material
            clipChildren = false
        }
    private val primary = PrimaryFace(context)
    private val separator = View(context)
    private val language =
        TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            typeface =
                android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL,
                )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val phase =
        TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            maxLines = 1
            includeFontPadding = false
            fontFeatureSettings = "tnum"
        }
    private val slot =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            setPaddingRelative(dp(10), 0, dp(14), 0)
            isClickable = true
            isFocusable = false
            accessibilityDelegate =
                object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                    }
                }
            setOnClickListener {
                controller.selectLanguage(if (controller.language.value == "en") "es" else "en")
            }
        }
    private val cancel =
        iconButton(Glyph.CLOSE, "Cancel and discard recording").apply {
            setOnClickListener { controller.cancel() }
        }
    private val copy =
        iconButton(Glyph.COPY, "Copy transcript").apply { setOnClickListener { controller.copy() } }
    private val discard =
        iconButton(Glyph.DELETE, "Discard transcript").apply {
            setOnClickListener { controller.discard() }
        }
    private val note =
        TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(232)
            minHeight = dp(32)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isFocusable = false
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
    private val target = DismissTarget(context)
    private val targetParams =
        WindowManager.LayoutParams(
                dp(72),
                dp(72),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            .apply { gravity = Gravity.TOP or Gravity.LEFT }
    private var attached = false
    private var targetAttached = false
    private var knownWindowId: Int? = null

    /** Whether the bubble window is currently attached and visible to the user. */
    val visible: Boolean
        get() = attached

    val windowId: Int?
        get() =
            layout.createAccessibilityNodeInfo().windowId.takeIf { attached && it >= 0 }
                ?: knownWindowId

    private class Notice(
        val text: String,
        val action: String?,
        val onTap: (() -> Unit)?,
        val until: Long,
    )

    private var orientation = context.resources.configuration.orientation
    private var lastPhase: Phase? = null
    private var lastBusy = false
    private var lastRecordingWarning = false
    private var notice: Notice? = null
    private var undoPackage: String? = null
    private var mode = Mode.IDLE
    private var hideAllowed = false
    private var quiet = false
    private var restAnimation: ValueAnimator? = null
    private var disposed = false
    private var draggingWindow = false
    private var dockRight = true
    private var paletteNight = dark
    private val clearNotice = Runnable {
        if (!disposed) {
            notice = null
            undoPackage = null
            render(controller.editor.current, controller.session.value)
        }
    }
    private val rest = Runnable {
        if (!disposed && quiet && !draggingWindow) {
            if (ValueAnimator.areAnimatorsEnabled()) {
                restAnimation?.cancel()
                restAnimation =
                    ValueAnimator.ofFloat(material.restFraction, 1f).apply {
                        duration = 240
                        addUpdateListener { material.restFraction = it.animatedValue as Float }
                        start()
                    }
            } else material.restFraction = 1f
        }
    }

    init {
        restorePosition()
        capsule.addView(primary, LinearLayout.LayoutParams(dp(52), dp(52)))
        capsule.addView(separator, LinearLayout.LayoutParams(hairline(), dp(22)))
        slot.addView(language)
        slot.addView(phase, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        capsule.addView(slot, LinearLayout.LayoutParams(-2, dp(52)))
        row.addView(capsule, LinearLayout.LayoutParams(-2, dp(52)))
        for (view in listOf(cancel, copy, discard)) row.addView(
            view,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
        )
        layout.addView(row)
        layout.addView(
            note,
            LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            },
        )
        primary.setOnClickListener {
            when (mode) {
                Mode.RECORDING -> controller.stop()
                Mode.PENDING -> insert()
                Mode.NO_MODEL -> openApp()
                Mode.IDLE -> record()
                Mode.WORKING -> Unit
            }
        }
        // No long-press handler on the primary action: slow Stop/Insert presses must click.
        primary.isLongClickable = false
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
            notice = null
            undoPackage = null
            layout.removeCallbacks(clearNotice)
        }
        val eligible =
            editor.identity?.displayId == 0 &&
                editor.connectionAvailable &&
                !editor.password &&
                !editor.blocked
        val pending = session.text != null && !session.attemptConsumed
        val now = SystemClock.uptimeMillis()
        val current = notice?.takeIf { now < it.until }
        // A just-hidden app keeps only the Undo note until it expires; nothing else is shown.
        val undoing = current != null && undoPackage != null
        if (
            editor.locked ||
                editor.password ||
                editor.identity?.displayId?.let { it != 0 } == true ||
                (!busy && !eligible && !undoing)
        ) {
            close()
            return
        }
        val modelReady = controller.models.ready.value
        val modelBusy = controller.models.busy.value
        val recording = session.phase in setOf(Phase.STARTING, Phase.RECORDING)
        mode =
            when {
                recording -> Mode.RECORDING
                busy || modelBusy -> Mode.WORKING
                pending -> Mode.PENDING
                !modelReady -> Mode.NO_MODEL
                else -> Mode.IDLE
            }
        hideAllowed = mode == Mode.IDLE || mode == Mode.NO_MODEL
        capsule.visibility = if (undoing) View.GONE else View.VISIBLE

        val inference =
            session.phase == Phase.TRANSCRIBING &&
                controller.diagnostics.report.value?.steps?.lastOrNull()?.stage ==
                    RecognitionStage.INFERENCE
        primary.face =
            when (mode) {
                Mode.RECORDING -> Face.STOP
                Mode.WORKING -> Face.PROGRESS
                Mode.PENDING -> Face.ARROW
                Mode.NO_MODEL -> Face.DOWNLOAD
                Mode.IDLE -> Face.MIC
            }
        primary.progress = if (inference) controller.progress.value / 100f else null
        primary.isEnabled =
            when (mode) {
                Mode.RECORDING,
                Mode.NO_MODEL -> true
                Mode.PENDING,
                Mode.IDLE -> eligible
                Mode.WORKING -> false
            }
        primary.alpha = if (primary.isEnabled || mode == Mode.WORKING) 1f else 0.42f
        primary.contentDescription =
            when (mode) {
                Mode.RECORDING -> "Stop and transcribe"
                Mode.PENDING -> "Insert transcript in selected field"
                Mode.WORKING ->
                    if (modelBusy && !busy) "Preparing model" else "Microphone off; recognizing"
                Mode.NO_MODEL -> "Install a model in Altiro"
                Mode.IDLE -> "Start dictation. Drag to move or hide."
            }
        primary.stateDescription =
            when (mode) {
                Mode.RECORDING -> "Recording"
                Mode.WORKING -> "Processing"
                Mode.PENDING -> "Text ready; insertion needs your action"
                Mode.NO_MODEL -> "No model installed"
                Mode.IDLE -> "Ready"
            }

        val code = controller.language.value
        language.text = if (code == "auto") "Auto" else code.uppercase(java.util.Locale.ROOT)
        slot.isEnabled = mode == Mode.IDLE || mode == Mode.NO_MODEL
        language.alpha = if (slot.isEnabled) 1f else 0.5f
        slot.contentDescription = "Dictation language ${language.text}"
        slot.stateDescription =
            if (slot.isEnabled) "Tap to switch English and Spanish"
            else "Language fixed for this recording"

        val recordingWarning = recording && session.elapsedSeconds >= Wav.MAX_SECONDS - 30
        val phaseText =
            when {
                session.phase == Phase.STARTING -> "Starting"
                recordingWarning ->
                    "${clock((Wav.MAX_SECONDS - session.elapsedSeconds).coerceAtLeast(0))} left"
                recording -> "● ${clock(session.elapsedSeconds)}"
                nativeBusy && !session.busy -> "Cancelling"
                inference -> "${controller.progress.value}%"
                session.phase == Phase.TRANSCRIBING -> "Preparing"
                busy -> "Finishing"
                modelBusy -> "Preparing"
                mode == Mode.PENDING -> "Insert"
                else -> ""
            }
        language.textSize = if (phaseText.isEmpty()) 14f else 12f
        // Announce phase changes once; elapsed time and progress ticks stay silent.
        if (phase.text.toString() != phaseText) {
            phase.accessibilityLiveRegion =
                if (lastPhase != session.phase || recordingWarning != lastRecordingWarning)
                    View.ACCESSIBILITY_LIVE_REGION_POLITE
                else View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        phase.text = phaseText
        phase.setTextColor(if (recording && !recordingWarning) forest else ink)
        phase.visibility = if (phaseText.isEmpty()) View.GONE else View.VISIBLE
        lastRecordingWarning = recordingWarning

        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        cancel.contentDescription = if (recording) "Cancel and discard recording" else "Cancel"
        copy.visibility = if (mode == Mode.PENDING && eligible) View.VISIBLE else View.GONE
        discard.visibility = copy.visibility

        val persistent =
            when {
                session.phase == Phase.DISPATCHED_UNCONFIRMED && session.dispatchFailed ->
                    "Insertion uncertain · check the field"
                session.phase == Phase.FAILED -> session.message ?: "Recognition failed"
                mode == Mode.PENDING -> session.message
                mode == Mode.NO_MODEL && !modelBusy -> "Install a model in Altiro"
                else -> null
            }
        val shown = current ?: persistent?.let { Notice(it, null, null, Long.MAX_VALUE) }
        note.text = shown?.let { n -> n.action?.let { "${n.text} · $it" } ?: n.text }.orEmpty()
        note.contentDescription = note.text
        note.visibility = if (shown == null) View.GONE else View.VISIBLE
        note.minHeight = dp(if (shown?.onTap != null) 48 else 32)
        val onTap = shown?.onTap
        if (onTap != null) note.setOnClickListener { onTap() }
        else {
            note.setOnClickListener(null)
            note.isClickable = false
        }

        material.busy = mode != Mode.IDLE || shown != null
        lastPhase = session.phase
        lastBusy = busy
        primary.syncMotion()
        capsule.invalidate()
        primary.invalidate()
        settle(quietNow = mode == Mode.IDLE && shown == null)

        val flags = params.flags
        params.flags =
            if (busy) flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (!attached) {
            runCatching { windows.addView(layout, params) }
                .onSuccess {
                    attached = true
                    knownWindowId = layout.createAccessibilityNodeInfo().windowId.takeIf { it >= 0 }
                    primary.syncMotion()
                }
            layout.post {
                knownWindowId = layout.createAccessibilityNodeInfo().windowId.takeIf { it >= 0 }
                clampAndUpdate()
            }
        } else if (flags != params.flags) windows.updateViewLayout(layout, params)
        // Layout changes and IME appearance can move the safe bounds, but never animate window
        // size.
        layout.post { clampAndUpdate() }
    }

    fun showMessage(text: String, action: String? = null, run: (() -> Unit)? = null) {
        if (disposed) return
        val duration = if (run != null) 8000L else 5000L
        notice = Notice(text, action, run, SystemClock.uptimeMillis() + duration)
        undoPackage = null
        layout.removeCallbacks(clearNotice)
        render(controller.editor.current, controller.session.value)
        layout.postDelayed(clearNotice, duration)
    }

    fun dispose() {
        disposed = true
        layout.removeCallbacks(clearNotice)
        layout.removeCallbacks(rest)
        close()
    }

    fun close() {
        hideTarget()
        primary.syncMotion(force = false)
        layout.removeCallbacks(rest)
        resetRestMaterial()
        quiet = false
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

    /** Explicit hide for the current app with a short Undo; never revives a destination token. */
    private fun hideHere() {
        val packageName = controller.editor.current.identity?.packageName ?: return
        if (!hideAllowed) return
        // Set the Undo note first: disabling refreshes the editor and renders synchronously.
        notice =
            Notice("Hidden in this app", "Undo", { undoHide() }, SystemClock.uptimeMillis() + 5000)
        undoPackage = packageName
        layout.removeCallbacks(clearNotice)
        controller.disableCurrentApp()
        render(controller.editor.current, controller.session.value)
        layout.postDelayed(clearNotice, 5000)
    }

    private fun undoHide() {
        val packageName = undoPackage ?: return
        undoPackage = null
        notice = null
        layout.removeCallbacks(clearNotice)
        controller.restoreApp(packageName)
    }

    // Only the material rests: text/glyphs remain opaque over arbitrary editor backdrops.
    // Editor events do not wake it; touches and state changes do.
    private fun settle(quietNow: Boolean) {
        if (quietNow == quiet) return
        quiet = quietNow
        layout.removeCallbacks(rest)
        if (quietNow) layout.postDelayed(rest, REST_DELAY)
        else {
            resetRestMaterial()
        }
    }

    private fun resetRestMaterial() {
        restAnimation?.cancel()
        restAnimation = null
        material.restFraction = 0f
    }

    private fun wake() {
        layout.removeCallbacks(rest)
        resetRestMaterial()
        if (quiet) layout.postDelayed(rest, REST_DELAY)
    }

    private fun restorePosition() {
        val b = windows.currentWindowMetrics.bounds
        dockRight =
            preferences.getBoolean(
                "dock-right-$orientation",
                preferences.getFloat("x-$orientation", 0.85f) >= 0.5f,
            )
        applyDock()
        params.x = if (dockRight) b.width() else 0
        params.y = (preferences.getFloat("y-$orientation", 0.25f) * b.height()).toInt()
    }

    private fun applyDock() {
        row.layoutDirection =
            if (dockRight) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        layout.gravity = if (dockRight) Gravity.RIGHT else Gravity.LEFT
    }

    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var originX = 0
    private var originY = 0
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var dragPointerId = 0

    // Own gestures at the window root so disabled microphone/language controls remain draggable.
    // Child views retain their real disabled semantics and receive normal taps below the slop.
    private fun dispatchDrag(event: MotionEvent, dispatch: (MotionEvent) -> Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake()
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
                    startX = params.x
                    startY = params.y
                    val cancelled = MotionEvent.obtain(event)
                    cancelled.action = MotionEvent.ACTION_CANCEL
                    dispatch(cancelled)
                    cancelled.recycle()
                    if (hideAllowed) showTarget()
                }
                if (dragging) {
                    params.x = originX + deltaX.toInt()
                    params.y = originY + deltaY.toInt()
                    clampAndUpdate(force = true)
                    if (targetAttached) target.armed = overTarget()
                }
                return if (dragging) true else dispatch(event)
            }
            MotionEvent.ACTION_UP -> {
                val moved = dragging
                if (moved) {
                    draggingWindow = false
                    if (targetAttached && overTarget() && hideAllowed) {
                        hideTarget()
                        params.x = startX
                        params.y = startY
                        clampAndUpdate(force = true)
                        hideHere()
                    } else {
                        hideTarget()
                        val b = windows.currentWindowMetrics.bounds
                        dockRight = params.x + layout.width / 2 >= b.width() / 2
                        applyDock()
                        clampAndUpdate(force = true)
                        preferences
                            .edit()
                            .putBoolean("dock-right-$orientation", dockRight)
                            .putFloat("y-$orientation", params.y.toFloat() / b.height())
                            .apply()
                    }
                }
                dragging = false
                return if (moved) true else dispatch(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                draggingWindow = false
                hideTarget()
                clampAndUpdate(force = true)
                return dispatch(event)
            }
            else -> return if (dragging) true else dispatch(event)
        }
    }

    private fun targetCenter(): Pair<Int, Int> {
        val metrics = windows.currentWindowMetrics
        val insets =
            metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
            )
        return metrics.bounds.width() / 2 to metrics.bounds.height() - insets.bottom - dp(64)
    }

    private fun showTarget() {
        if (targetAttached) return
        val (x, y) = targetCenter()
        targetParams.x = x - dp(36)
        targetParams.y = y - dp(36)
        target.armed = false
        runCatching { windows.addView(target, targetParams) }.onSuccess { targetAttached = true }
    }

    private fun hideTarget() {
        if (!targetAttached) return
        runCatching { windows.removeView(target) }
        targetAttached = false
    }

    private fun overTarget(): Boolean {
        val (x, y) = targetCenter()
        val cx = params.x + row.left + capsule.left + capsule.width / 2
        val cy = params.y + row.top + capsule.top + capsule.height / 2
        return kotlin.math.hypot((cx - x).toFloat(), (cy - y).toFloat()) < dp(72)
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
                        maxOf(layout.height, dp(64) + 2 * note.lineHeight + dp(16)) -
                        insets.bottom,
                ),
            )
        if (force || oldX != params.x || oldY != params.y) windows.updateViewLayout(layout, params)
    }

    private fun applyPalette() {
        language.setTextColor(ink)
        phase.setTextColor(ink)
        note.setTextColor(ink)
        separator.setBackgroundColor(withAlpha(ink, 40))
        slot.background = touchBackground()
        note.background =
            GradientDrawable().apply {
                setColor(withAlpha(chalk, 240))
                setStroke(hairline(), withAlpha(ink, 26))
                cornerRadius = dp(16).toFloat()
            }
        for (view in listOf(cancel, copy, discard)) view.background =
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(chalk)
                        setStroke(dp(1), withAlpha(ink, 60))
                    },
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(withAlpha(chalk, 240))
                        setStroke(hairline(), withAlpha(ink, 26))
                    },
                )
            }
        cancel.setImageDrawable(GlyphDrawable(Glyph.CLOSE, ink))
        copy.setImageDrawable(GlyphDrawable(Glyph.COPY, ink))
        discard.setImageDrawable(GlyphDrawable(Glyph.DELETE, ink))
        capsule.invalidate()
        primary.invalidate()
        target.invalidate()
    }

    private fun iconButton(glyph: Glyph, label: String) =
        ImageButton(context).apply {
            setImageDrawable(GlyphDrawable(glyph, ink))
            contentDescription = label
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isFocusable = false
        }

    private fun touchBackground() =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    setColor(withAlpha(ink, 22))
                    cornerRadius = dp(26).toFloat()
                },
            )
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }

    private fun clock(seconds: Int) =
        "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)

    private fun dp(value: Int) = (value * density).toInt()

    private fun hairline() = (0.8f * density).toInt().coerceAtLeast(1)

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private enum class Mode {
        IDLE,
        RECORDING,
        WORKING,
        PENDING,
        NO_MODEL,
    }

    private enum class Face {
        MIC,
        STOP,
        PROGRESS,
        ARROW,
        DOWNLOAD,
    }

    /** Frosted capsule: translucent chalk, hairline edge and a soft shadow drawn only outside. */
    private inner class CapsuleMaterial : Drawable() {
        var busy = false
        var restFraction = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidateSelf()
            }

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Path()
        private val box = RectF()

        override fun draw(canvas: Canvas) {
            val d = density
            box.set(bounds)
            val r = box.height() / 2
            outline.reset()
            outline.addRoundRect(box, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipOutPath(outline)
            p.style = Paint.Style.FILL
            p.color = chalk
            p.setShadowLayer(
                8 * d,
                0f,
                2 * d,
                withAlpha(
                    Color.BLACK,
                    ((if (dark) 110 else 40) * (1f - restFraction * 0.3f)).toInt(),
                ),
            )
            canvas.drawRoundRect(box, r, r, p)
            p.clearShadowLayer()
            canvas.restore()
            val awakeFill = if (dark) 216 else 172
            val restFill = if (dark) 192 else 150
            val fill =
                if (busy) 242 else (awakeFill + (restFill - awakeFill) * restFraction).toInt()
            p.color = withAlpha(chalk, fill)
            canvas.drawRoundRect(box, r, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.8f * d
            p.color = withAlpha(ink, if (dark) 40 else 26)
            box.inset(0.4f * d, 0.4f * d)
            canvas.drawRoundRect(box, r, r, p)
            p.color = withAlpha(Color.WHITE, if (dark) 18 else 120)
            box.inset(0.8f * d, 0.8f * d)
            canvas.drawRoundRect(box, r, r, p)
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(filter: android.graphics.ColorFilter?) {}

        @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** The one backed control: mic, Stop, progress ring, insert arrow or model download. */
    private inner class PrimaryFace(context: Context) : View(context) {
        var face = Face.MIC
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        var progress: Float? = null
        private var angle = 0f
        private var animation: ValueAnimator? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = RectF()

        init {
            isClickable = true
            isFocusable = false
            accessibilityDelegate =
                object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                        if (hideAllowed)
                            info.addAction(
                                AccessibilityNodeInfo.AccessibilityAction(
                                    HIDE_ACTION,
                                    "Hide in this app",
                                )
                            )
                    }

                    override fun performAccessibilityAction(
                        host: View,
                        action: Int,
                        args: Bundle?,
                    ): Boolean {
                        if (action == HIDE_ACTION && hideAllowed) {
                            hideHere()
                            return true
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
        }

        /** Rotation runs only for indeterminate work while attached and animations are on. */
        fun syncMotion(force: Boolean = true) {
            val spin = force && attached && face == Face.PROGRESS && progress == null
            if (!spin || !ValueAnimator.areAnimatorsEnabled()) {
                animation?.cancel()
                animation = null
                angle = 0f
                return
            }
            if (animation != null) return
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

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDetachedFromWindow() {
            animation?.cancel()
            animation = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val d = density
            val cx = width / 2f
            val cy = height / 2f
            val r = 20 * d
            p.style = Paint.Style.FILL
            when (face) {
                Face.MIC,
                Face.DOWNLOAD -> {
                    p.color = withAlpha(ink, if (isPressed) 46 else 20)
                    canvas.drawCircle(cx, cy, r, p)
                    glyph(canvas, if (face == Face.MIC) Glyph.MIC else Glyph.DOWNLOAD, ink)
                }
                Face.ARROW -> {
                    p.color = withAlpha(ink, if (isPressed) 210 else 255)
                    canvas.drawCircle(cx, cy, r, p)
                    glyph(canvas, Glyph.ARROW, chalk)
                }
                Face.STOP -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1.4f * d
                    p.color = withAlpha(forest, 110)
                    canvas.drawCircle(cx, cy, r + 3 * d, p)
                    p.style = Paint.Style.FILL
                    p.color = if (isPressed) withAlpha(forest, 210) else forest
                    canvas.drawCircle(cx, cy, r, p)
                    glyph(canvas, Glyph.STOP, chalk, 0.8f)
                }
                Face.PROGRESS -> {
                    val ring = r - 3 * d
                    arc.set(cx - ring, cy - ring, cx + ring, cy + ring)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2.5f * d
                    p.strokeCap = Paint.Cap.ROUND
                    p.color = withAlpha(ink, 36)
                    canvas.drawCircle(cx, cy, ring, p)
                    p.color = forest
                    val value = progress
                    if (value != null)
                        canvas.drawArc(
                            arc,
                            -90f,
                            (value.coerceIn(0f, 1f) * 360f).coerceAtLeast(8f),
                            false,
                            p,
                        )
                    else canvas.drawArc(arc, angle - 90f, 90f, false, p)
                }
            }
        }

        private fun glyph(canvas: Canvas, glyph: Glyph, color: Int, scale: Float = 1f) {
            val half = (12 * density * scale).toInt()
            GlyphDrawable(glyph, color).apply {
                setBounds(width / 2 - half, height / 2 - half, width / 2 + half, height / 2 + half)
                draw(canvas)
            }
        }
    }

    /** Drop target shown only while dragging an idle bubble. Never touchable. */
    private inner class DismissTarget(context: Context) : View(context) {
        var armed = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val d = density
            val r = if (armed) 30 * d else 24 * d
            p.style = Paint.Style.FILL
            p.color = if (armed) ink else withAlpha(chalk, 235)
            canvas.drawCircle(width / 2f, height / 2f, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = d
            p.color = withAlpha(ink, 60)
            canvas.drawCircle(width / 2f, height / 2f, r, p)
            val half = (11 * d).toInt()
            GlyphDrawable(Glyph.CLOSE, if (armed) chalk else ink).apply {
                setBounds(width / 2 - half, height / 2 - half, width / 2 + half, height / 2 + half)
                draw(canvas)
            }
        }
    }

    private companion object {
        const val REST_DELAY = 4000L
        // Custom accessibility action id outside framework action ranges. A resource id is
        // preferable if a shared ids.xml is added later.
        const val HIDE_ACTION = 0x7A170001
    }
}
