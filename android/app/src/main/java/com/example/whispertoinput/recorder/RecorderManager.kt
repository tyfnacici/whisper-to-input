// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

private const val TAG = "whisper-input"
private const val SAMPLE_RATE_HZ = 16_000
private const val BIT_RATE = 48_000

/** Records mono 16 kHz AAC (m4a), the rate whisper models work at. */
class RecorderManager(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAtMs = 0L

    val isRecording: Boolean get() = recorder != null

    /** Starts recording into [file]; returns false if the microphone could not be opened. */
    fun start(file: File): Boolean {
        stopQuietly()
        file.delete()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return try {
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            startedAtMs = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            Log.e(TAG, "recorder start failed: ${e.message}")
            runCatching { r.release() }
            false
        }
    }

    /** Stops recording and returns its length in milliseconds, or null if nothing usable was recorded. */
    fun stop(): Long? {
        val r = recorder ?: return null
        recorder = null
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        return try {
            r.stop()
            durationMs
        } catch (e: Exception) {
            // stop() throws when no valid audio was captured (very short recording).
            Log.w(TAG, "recorder stop failed: ${e.message}")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun stopQuietly() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
    }

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
