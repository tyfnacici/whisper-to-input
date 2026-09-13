package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeoutPolicyTest {
    private val auto = "Auto"
    private val t60 = "60s"
    private val t300 = "300s"
    private val t600 = "600s"

    private fun timeout(setting: String, duration: Long?) = TimeoutPolicy.readTimeoutSeconds(
        requestTimeout = setting,
        auto = auto,
        t60 = t60,
        t300 = t300,
        t600 = t600,
        recordingDurationSeconds = duration
    )

    @Test fun fixed60OverridesShortAndLongDurations() {
        assertEquals(60L, timeout(t60, 0L))
        assertEquals(60L, timeout(t60, 3600L))
    }

    @Test fun fixed300OverridesDurationIncludingNull() {
        assertEquals(300L, timeout(t300, null))
        assertEquals(300L, timeout(t300, 100L))
    }

    @Test fun fixed600OverridesDurationIncludingNull() {
        assertEquals(600L, timeout(t600, null))
        assertEquals(600L, timeout(t600, 5L))
    }

    @Test fun autoWithZeroDurationUsesThirtySeconds() {
        assertEquals(30L, timeout(auto, 0L))
    }

    @Test fun autoWithShortDictationUsesAdaptiveFormula() {
        assertEquals(50L, timeout(auto, 5L))
    }

    @Test fun autoWithHundredSecondRecordingUsesAdaptiveFormula() {
        assertEquals(430L, timeout(auto, 100L))
    }

    @Test fun autoWithUnknownDurationFallsBackToTenMinutes() {
        assertEquals(600L, timeout(auto, null))
    }

    @Test fun unknownSettingUsesAdaptiveFormulaAndFallback() {
        assertEquals(430L, timeout("forever", 100L))
        assertEquals(600L, timeout("forever", null))
    }

    @Test fun largeDurationGrowsLinearly() {
        assertEquals(14430L, timeout(auto, 3600L))
    }
}
