// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import kotlin.math.ceil

/**
 * Request timeouts that scale with the recording length, so a long dictation is
 * never cut off by the client while the server is still transcribing.
 */
object TimeoutPolicy {
    private const val MIN_SECONDS = 60L
    private const val UNKNOWN_DURATION_SECONDS = 600L

    private fun seconds(durationMs: Long): Long = ceil(durationMs / 1000.0).toLong()

    /** Time the server may take to answer: duration x 4 + 30s, at least 60s. */
    fun readTimeoutSeconds(durationMs: Long?): Long =
        if (durationMs == null) UNKNOWN_DURATION_SECONDS
        else maxOf(MIN_SECONDS, seconds(durationMs) * 4 + 30)

    /** Time allowed to upload the audio: duration x 2 + 30s, at least 60s. */
    fun writeTimeoutSeconds(durationMs: Long?): Long =
        if (durationMs == null) UNKNOWN_DURATION_SECONDS
        else maxOf(MIN_SECONDS, seconds(durationMs) * 2 + 30)
}
