// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput.keyboard

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.whispertoinput.MicGesture
import com.example.whispertoinput.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/** What the keyboard shows. The service owns the logic; this class only draws it. */
data class KeyboardState(
    val recording: Boolean = false,
    val sending: Boolean = false,
    val elapsedSeconds: Long = 0,
    /** Recordings that have not been transcribed yet. */
    val pendingCount: Int = 0,
    /** Transcribed texts waiting to be typed into a field. */
    val readyCount: Int = 0,
    /** Last reachability check; null while unknown. */
    val online: Boolean? = null,
    val error: String? = null
)

interface KeyboardListener {
    fun onStartRecording()
    fun onStopRecording()
    /** Left key while recording or sending. */
    fun onCancel()
    fun onRetry()
    fun onDiscardPending()
    fun onInsertReady()
    fun onBackspace()
    fun onEnter()
    fun onSpace()
    fun onSwitchIme()
    fun onOpenSettings()
}

class WhisperKeyboard {
    private lateinit var root: View
    private lateinit var listener: KeyboardListener
    private lateinit var statusGroup: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var chipReady: TextView
    private lateinit var btnLeft: ImageButton
    private lateinit var badge: TextView
    private lateinit var btnMic: View
    private lateinit var imgMic: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var rings: List<View>
    private var ringAnimators: List<ValueAnimator> = emptyList()

    private val gesture = MicGesture()
    private var state = KeyboardState()
    private var leftMode = LeftMode.Hidden

    private enum class LeftMode { Hidden, Cancel, Retry }

    fun setup(
        inflater: LayoutInflater,
        offerImeSwitch: Boolean,
        listener: KeyboardListener
    ): View {
        this.listener = listener
        root = inflater.inflate(R.layout.keyboard_view, null)
        statusGroup = root.findViewById(R.id.status_group)
        statusDot = root.findViewById(R.id.status_dot)
        statusLabel = root.findViewById(R.id.label_status)
        chipReady = root.findViewById(R.id.chip_ready)
        btnLeft = root.findViewById(R.id.btn_left)
        badge = root.findViewById(R.id.badge_pending)
        btnMic = root.findViewById(R.id.btn_mic)
        imgMic = root.findViewById(R.id.img_mic)
        progress = root.findViewById(R.id.progress_sending)
        rings = listOf(root.findViewById(R.id.ring_0), root.findViewById(R.id.ring_1))

        root.findViewById<View>(R.id.btn_settings).setOnClickListener { listener.onOpenSettings() }
        root.findViewById<View>(R.id.btn_enter).setOnClickListener { listener.onEnter() }
        root.findViewById<View>(R.id.btn_space).setOnClickListener { listener.onSpace() }
        root.findViewById<BackspaceButton>(R.id.btn_backspace)
            .setBackspaceCallback { listener.onBackspace() }
        chipReady.setOnClickListener { listener.onInsertReady() }
        root.findViewById<View>(R.id.btn_previous_ime).apply {
            if (offerImeSwitch) setOnClickListener { listener.onSwitchIme() }
            else visibility = View.GONE
        }
        btnLeft.setOnClickListener {
            when (leftMode) {
                LeftMode.Cancel -> listener.onCancel()
                LeftMode.Retry -> listener.onRetry()
                LeftMode.Hidden -> Unit
            }
        }
        btnLeft.setOnLongClickListener {
            if (leftMode == LeftMode.Retry) {
                listener.onDiscardPending()
                true
            } else false
        }
        btnMic.setOnTouchListener { v, event -> onMicTouch(v, event) }

        render(KeyboardState())
        return root
    }

    private fun onMicTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (gesture.onDown(state.recording, SystemClock.elapsedRealtime())) {
                    MicGesture.Action.Start -> listener.onStartRecording()
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (gesture.onUp(SystemClock.elapsedRealtime()) == MicGesture.Action.Stop) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onStopRecording()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            }
        }
        return true
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(root, attr)

    fun render(newState: KeyboardState) {
        if (!::root.isInitialized) return
        state = newState
        val s = newState
        val busy = s.recording || s.sending
        root.keepScreenOn = busy

        // Status line / ready chip
        val showChip = s.readyCount > 0 && !s.recording
        chipReady.visibility = if (showChip) View.VISIBLE else View.GONE
        statusGroup.visibility = if (showChip) View.GONE else View.VISIBLE
        val errorColor = color(MaterialR.attr.colorError)
        val normalColor = color(MaterialR.attr.colorOnSurfaceVariant)
        statusLabel.setTextColor(normalColor)
        statusDot.visibility = View.GONE
        statusLabel.text = when {
            s.recording -> root.context.getString(R.string.status_recording, mmss(s.elapsedSeconds))
            s.sending -> root.context.getString(R.string.status_sending, mmss(s.elapsedSeconds))
            s.error != null -> {
                statusLabel.setTextColor(errorColor)
                if (s.pendingCount > 0) "${s.error} · ${s.pendingCount}" else s.error
            }
            s.pendingCount > 0 -> root.context.getString(R.string.hint_pending, s.pendingCount)
            else -> root.context.getString(R.string.hint_idle)
        }
        if (!s.recording && s.online != null) {
            statusDot.visibility = View.VISIBLE
            statusDot.background.mutate()
            statusDot.backgroundTintList = ColorStateList.valueOf(
                if (s.online) root.context.getColor(R.color.brand_online) else errorColor
            )
            statusDot.contentDescription = root.context.getString(
                if (s.online) R.string.desc_online else R.string.desc_offline
            )
        }

        // Left key: cancel while busy, retry when recordings are waiting
        leftMode = when {
            busy -> LeftMode.Cancel
            s.pendingCount > 0 -> LeftMode.Retry
            else -> LeftMode.Hidden
        }
        btnLeft.visibility = if (leftMode == LeftMode.Hidden) View.INVISIBLE else View.VISIBLE
        when (leftMode) {
            LeftMode.Cancel -> {
                btnLeft.setImageResource(R.drawable.ic_close)
                btnLeft.contentDescription = root.context.getString(R.string.desc_cancel)
            }
            LeftMode.Retry -> {
                btnLeft.setImageResource(R.drawable.ic_refresh)
                btnLeft.contentDescription = root.context.getString(R.string.desc_retry)
            }
            LeftMode.Hidden -> Unit
        }
        badge.visibility = if (leftMode == LeftMode.Retry) View.VISIBLE else View.GONE
        badge.text = s.pendingCount.coerceAtMost(99).toString()

        // Mic button
        if (s.recording) {
            btnMic.backgroundTintList = ColorStateList.valueOf(errorColor)
            imgMic.setImageResource(R.drawable.ic_stop)
            imgMic.imageTintList = ColorStateList.valueOf(color(MaterialR.attr.colorOnError))
        } else {
            btnMic.backgroundTintList = null
            imgMic.setImageResource(R.drawable.ic_mic)
            imgMic.imageTintList = ColorStateList.valueOf(color(MaterialR.attr.colorOnPrimary))
        }
        progress.visibility = if (s.sending && !s.recording) View.VISIBLE else View.GONE
        setRingsRunning(s.recording)
    }

    private fun setRingsRunning(running: Boolean) {
        if (running && ringAnimators.isEmpty()) {
            ringAnimators = rings.mapIndexed { i, ring ->
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1600
                    startDelay = i * 800L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val f = it.animatedFraction
                        val scale = 0.9f + 0.4f * f
                        ring.scaleX = scale
                        ring.scaleY = scale
                        ring.alpha = 0.5f * (1f - f)
                    }
                    start()
                }
            }
        } else if (!running && ringAnimators.isNotEmpty()) {
            ringAnimators.forEach { it.cancel() }
            ringAnimators = emptyList()
            rings.forEach { it.alpha = 0f }
        }
    }

    private fun mmss(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
