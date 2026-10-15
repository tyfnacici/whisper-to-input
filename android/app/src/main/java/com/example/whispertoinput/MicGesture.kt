// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

/**
 * Decides what a press on the mic button means.
 *
 * - tap while idle: start recording and keep going until the next tap
 * - hold while idle: start recording, release sends it (push-to-talk)
 * - tap while recording: send
 */
class MicGesture(private val holdThresholdMs: Long = 400L) {
    enum class Action { None, Start, Stop }

    private var downAtMs = 0L
    private var startedByThisPress = false
    private var stopOnRelease = false

    fun onDown(recording: Boolean, nowMs: Long): Action {
        downAtMs = nowMs
        return if (recording) {
            startedByThisPress = false
            stopOnRelease = true
            Action.None
        } else {
            startedByThisPress = true
            stopOnRelease = false
            Action.Start
        }
    }

    fun onUp(nowMs: Long): Action {
        val action = when {
            stopOnRelease -> Action.Stop
            startedByThisPress && nowMs - downAtMs >= holdThresholdMs -> Action.Stop
            else -> Action.None
        }
        startedByThisPress = false
        stopOnRelease = false
        return action
    }
}
