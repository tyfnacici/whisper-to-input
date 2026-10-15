// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeoutPolicyTest {
    @Test fun shortClipsGetAtLeastSixtySeconds() {
        assertEquals(60L, TimeoutPolicy.readTimeoutSeconds(0L))
        assertEquals(60L, TimeoutPolicy.readTimeoutSeconds(5_000L))
        assertEquals(60L, TimeoutPolicy.writeTimeoutSeconds(5_000L))
    }

    @Test fun readTimeoutIsFourTimesDurationPlusThirty() {
        assertEquals(430L, TimeoutPolicy.readTimeoutSeconds(100_000L))
        assertEquals(14_430L, TimeoutPolicy.readTimeoutSeconds(3_600_000L))
    }

    @Test fun partialSecondsRoundUp() {
        assertEquals(TimeoutPolicy.readTimeoutSeconds(100_000L) + 4,
            TimeoutPolicy.readTimeoutSeconds(100_001L))
    }

    @Test fun writeTimeoutIsTwiceDurationPlusThirty() {
        assertEquals(230L, TimeoutPolicy.writeTimeoutSeconds(100_000L))
    }

    @Test fun unknownDurationFallsBackToTenMinutes() {
        assertEquals(600L, TimeoutPolicy.readTimeoutSeconds(null))
        assertEquals(600L, TimeoutPolicy.writeTimeoutSeconds(null))
    }
}
