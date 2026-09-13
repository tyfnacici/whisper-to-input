/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import com.github.liuyueyi.quick.transfer.ChineseUtils

/** Selects a fixed or adaptive transcription request read timeout. */
object TimeoutPolicy {
    /**
     * Fixed values override the timeout. Auto uses duration * 4 + 30 seconds,
     * falling back to 600 seconds if the duration cannot be determined.
     */
    fun readTimeoutSeconds(
        requestTimeout: String,
        auto: String,
        t60: String,
        t300: String,
        t600: String,
        recordingDurationSeconds: Long?
    ): Long {
        if (requestTimeout == t60) return 60L
        if (requestTimeout == t300) return 300L
        if (requestTimeout == t600) return 600L
        // "Auto" (default) and any unknown value: adaptive timeout
        return if (recordingDurationSeconds == null) 600L
        else recordingDurationSeconds * 4 + 30L
    }
}

/** Applies Chinese conversion and the optional trailing space to transcription text. */
object TextPostProcessing {
    /** Converts text according to the selected mode, then optionally appends one space. */
    fun apply(
        postprocessing: String,
        simplified: String,
        traditional: String,
        addTrailingSpace: Boolean,
        text: String
    ): String {
        val processedText = when (postprocessing) {
            simplified -> ChineseUtils.tw2s(text)
            traditional -> ChineseUtils.s2tw(text)
            else -> text // No conversion
        }
        return processedText + if (addTrailingSpace) " " else ""
    }
}
