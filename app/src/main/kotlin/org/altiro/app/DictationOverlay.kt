package org.altiro.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.altiro.core.EditorState
import org.altiro.core.Phase
import org.altiro.core.Session

/** A bounded non-focusable control; never a full-screen touch interception layer. */
class DictationOverlay(
    context: Context,
    private val controller: DictationController,
    private val record: () -> Unit,
    private val insert: () -> Unit,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val preferences = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val orientation = context.resources.configuration.orientation
    private val layout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background =
                GradientDrawable().apply {
                    setColor(Color.rgb(235, 247, 241))
                    cornerRadius = dp(16).toFloat()
                }
            elevation = dp(6).toFloat()
        }
    private val status =
        TextView(context).apply {
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = Gravity.CENTER
            maxWidth = dp(180)
        }
    private val primary =
        Button(context).apply {
            minWidth = dp(64)
            minimumHeight = dp(48)
        }
    private val cancel =
        Button(context).apply {
            text = "Cancel"
            minimumHeight = dp(48)
            setOnClickListener { controller.cancel() }
        }
    private val copy =
        Button(context).apply {
            text = "Copy"
            minimumHeight = dp(48)
            setOnClickListener { controller.copy() }
        }
    private val discard =
        Button(context).apply {
            text = "Discard"
            minimumHeight = dp(48)
            setOnClickListener { controller.discard() }
        }
    private val disable =
        Button(context).apply {
            text = "Hide in this app"
            minimumHeight = dp(48)
            setOnClickListener {
                controller.disableCurrentApp()
                close()
            }
        }
    private val params =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                val bounds = windows.currentWindowMetrics.bounds
                x = (preferences.getFloat("x-$orientation", 0.85f) * bounds.width()).toInt()
                y = (preferences.getFloat("y-$orientation", 0.25f) * bounds.height()).toInt()
            }
    private var attached = false

    init {
        val handle =
            TextView(context).apply {
                text = "↕ Move"
                contentDescription = "Drag the floating microphone"
                gravity =
                    Gravity.CENTER
                minimumHeight = dp(48)
                setTextColor(Color.BLACK)
            }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                }
                MotionEvent.ACTION_MOVE ->
                    if (kotlin.math.abs(event.rawX - downX) + kotlin.math.abs(event.rawY - downY) > slop) {
                        params.x = originX + (event.rawX - downX).toInt()
                        params.y = originY + (event.rawY - downY).toInt()
                        clamp()
                        if (attached) windows.updateViewLayout(layout, params)
                    }
                MotionEvent.ACTION_UP -> {
                    val bounds = windows.currentWindowMetrics.bounds
                    params.x = if (params.x + layout.width / 2 < bounds.width() / 2) 0 else bounds.width() - layout.width
                    clamp()
                    if (attached) windows.updateViewLayout(layout, params)
                    preferences
                        .edit()
                        .putFloat("x-$orientation", params.x.toFloat() / bounds.width())
                        .putFloat(
                            "y-$orientation",
                            params.y.toFloat() / bounds.height(),
                        ).apply()
                    handle.performClick()
                }
            }
            true
        }
        for (view in listOf(handle, status, primary, cancel, copy, discard, disable)) layout.addView(view)
    }

    fun render(
        editor: EditorState,
        session: Session,
    ) {
        val controlsNeeded = session.busy || session.text != null
        val identity = editor.identity
        val eligible = identity != null && editor.connectionAvailable && !editor.password && !editor.blocked && identity.displayId == 0
        if (editor.locked || editor.password || editor.blocked || editor.identity?.displayId?.let { it != 0 } == true ||
            (!controlsNeeded && !eligible)
        ) {
            close()
            return
        }
        if (!attached) {
            runCatching { windows.addView(layout, params) }.onSuccess { attached = true }
            layout.post {
                clamp()
                if (attached) windows.updateViewLayout(layout, params)
            }
        }
        status.text =
            when (session.phase) {
                Phase.STARTING -> "Starting…"
                Phase.RECORDING -> "Recording · ${session.elapsedSeconds}s"
                Phase.FINALIZING -> "Finishing recording…"
                Phase.TRANSCRIBING -> controller.processingLabel()
                Phase.AWAITING_USER -> session.message ?: "Text ready"
                Phase.DISPATCHED_UNCONFIRMED -> "Check insertion"
                Phase.FAILED -> session.message ?: "Recognition failed"
                else ->
                    if (controller.recognition.busy.value) {
                        "Cancelling · ${controller.processingLabel()}"
                    } else if (!controller.models.ready.value) {
                        "Import model in Altiro"
                    } else {
                        "Altiro · offline"
                    }
            }
        val recording = session.phase in setOf(Phase.STARTING, Phase.RECORDING)
        val pending = session.text != null && !session.attemptConsumed
        primary.text =
            if (recording) {
                "Stop"
            } else if (pending) {
                "Insert"
            } else {
                "Mic"
            }
        primary.contentDescription =
            if (recording) {
                "Stop recording"
            } else if (pending) {
                "Insert transcript into ${editor.identity?.packageName ?: "selected field"}"
            } else {
                "Open microphone recording screen"
            }
        primary.isEnabled =
            recording || (pending && eligible) ||
            (!session.busy && !controller.recognition.busy.value && controller.models.ready.value && session.text == null && eligible)
        primary.setOnClickListener {
            when {
                recording -> controller.stop()
                pending -> insert()
                else -> record()
            }
        }
        cancel.visibility = if (session.busy) android.view.View.VISIBLE else android.view.View.GONE
        copy.visibility = if (session.text != null) android.view.View.VISIBLE else android.view.View.GONE
        discard.visibility = copy.visibility
        disable.visibility = if (!session.busy && session.text == null) android.view.View.VISIBLE else android.view.View.GONE
    }

    fun showMessage(message: String) {
        status.text = message
    }

    fun close() {
        if (attached) {
            runCatching { windows.removeView(layout) }
            attached = false
        }
    }

    private fun clamp() {
        val metrics = windows.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowManagerInsets)
        params.x = params.x.coerceIn(insets.left, maxOf(insets.left, metrics.bounds.width() - layout.width - insets.right))
        params.y = params.y.coerceIn(insets.top, maxOf(insets.top, metrics.bounds.height() - layout.height - insets.bottom))
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private val WindowManagerInsets =
            android.view.WindowInsets.Type
                .systemBars() or
                android.view.WindowInsets.Type
                    .ime()
    }
}
