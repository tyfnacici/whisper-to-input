// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import com.example.whispertoinput.MicGesture.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class MicGestureTest {
    @Test fun tapWhileIdleStartsAndKeepsRecording() {
        val g = MicGesture(400)
        assertEquals(Action.Start, g.onDown(recording = false, nowMs = 0))
        assertEquals(Action.None, g.onUp(nowMs = 120))
    }

    @Test fun tapWhileRecordingStopsOnRelease() {
        val g = MicGesture(400)
        assertEquals(Action.None, g.onDown(recording = true, nowMs = 0))
        assertEquals(Action.Stop, g.onUp(nowMs = 90))
    }

    @Test fun holdWhileIdleStopsOnRelease() {
        val g = MicGesture(400)
        assertEquals(Action.Start, g.onDown(recording = false, nowMs = 1000))
        assertEquals(Action.Stop, g.onUp(nowMs = 4000))
    }

    @Test fun releaseWithoutPressDoesNothing() {
        assertEquals(Action.None, MicGesture().onUp(nowMs = 10))
    }

    @Test fun stateResetsBetweenPresses() {
        val g = MicGesture(400)
        g.onDown(false, 0); g.onUp(5000)
        assertEquals(Action.Start, g.onDown(false, 6000))
        assertEquals(Action.None, g.onUp(6050))
    }
}
