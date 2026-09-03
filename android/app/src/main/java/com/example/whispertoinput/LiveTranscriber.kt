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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

private const val LIVE_TRANSCRIBER_AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val LIVE_TRANSCRIBER_AUDIO_MEDIA_TYPE_OGG = "audio/ogg"

/**
 * Drives live (streaming) transcription for an ongoing recording.
 *
 * The class is split in two halves:
 *
 * 1. A speech/silence finite state machine fed by microphone amplitude samples
 *    ([onAmplitude], delivered by RecorderManager's amplitude callback). It
 *    decides chunk boundaries: a chunk starts when speech is detected (leading
 *    silence is ignored) and ends after [END_SILENCE_MS] of silence following
 *    at least [MIN_SPEECH_MS] of accumulated speech, or is force-ended once it
 *    exceeds [MAX_CHUNK_MS]. Chunk thresholds reuse the recorder FSM integer
 *    resources from constants.xml so both operate on the same amplitude scale.
 *
 * 2. A strictly ordered transcription queue. Finished chunk audio files are
 *    passed to [onSegmentReady] by the recording side; requests are transcribed
 *    one at a time on a single-threaded queue and results are committed in the
 *    order the files were enqueued, via [onChunkText]. Failures are reported
 *    through [onError]; the queue keeps processing subsequent chunks.
 *
 * Threading: [onAmplitude] and [reset] must be called from the main thread
 * (same thread as RecorderManager's amplitude callback). [onSegmentReady] may
 * be called from any thread; callbacks are delivered on the main thread.
 */
class LiveTranscriber(
    context: Context,
    private val onChunkText: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "LiveTranscriber"

        // Accumulated speech time required before trailing silence ends a chunk.
        private const val MIN_SPEECH_MS = 2000L
        // Silence duration (after enough speech) that ends a chunk.
        private const val END_SILENCE_MS = 800L
        // A chunk never lasts longer than this, even without trailing silence.
        private const val MAX_CHUNK_MS = 15000L
        // Cap for a single amplitude-report interval so stalled feeds cannot
        // inject huge time deltas into the FSM.
        private const val MAX_AMPLITUDE_STEP_MS = 1000L
        // A gap larger than this between amplitude samples means the feed was
        // interrupted (e.g. recording restarted); FSM timing is reset.
        private const val STALE_FEED_MS = 5000L
    }

    private val appContext: Context = context.applicationContext

    // Amplitude threshold above which the FSM considers the user to be speaking.
    // Reuses the recorder FSM's idle->speaking threshold so both operate on the
    // same amplitude scale (see constants.xml).
    private val speechThreshold: Int =
        appContext.resources.getInteger(R.integer.recorder_fsm_idle_speaking_threshold)

    // Own transcriber instance for chunk files; independent from the service's
    // full-recording transcriber.
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()

    // ------------------------------------------------------------------
    // Speech/silence FSM state (main thread only)
    // ------------------------------------------------------------------
    private enum class State { IDLE, SPEAKING }
    private var state: State = State.IDLE
    private var lastAmplitudeTimeMs = 0L
    private var chunkSpeechMs = 0L
    private var chunkSilenceMs = 0L
    private var chunkElapsedMs = 0L

    // True while the FSM considers the user to be speaking (inside a chunk).
    val isSpeechActive: Boolean
        get() = state == State.SPEAKING

    // Incremented every time the FSM completes a chunk. The recording side can
    // detect chunk boundaries by comparing consecutive reads, finalize the
    // chunk's audio file accordingly, and pass it to onSegmentReady().
    var completedChunkCount: Long = 0L
        private set

    // ------------------------------------------------------------------
    // Ordered transcription queue
    // ------------------------------------------------------------------
    private val queueExecutor = Executors.newSingleThreadExecutor()
    private val queueDispatcher = queueExecutor.asCoroutineDispatcher()
    private var queueJob: Job = SupervisorJob()
    private var queueScope: CoroutineScope = CoroutineScope(queueJob + queueDispatcher)
    private var lastEnqueuedJob: Job? = null
    private val pendingFiles: MutableSet<File> = Collections.synchronizedSet(LinkedHashSet())

    /**
     * Feeds one microphone amplitude sample (from RecorderManager's existing
     * amplitude callback) into the speech/silence FSM. Main thread only.
     */
    fun onAmplitude(amplitude: Int) {
        val now = SystemClock.elapsedRealtime()
        val delta = if (lastAmplitudeTimeMs == 0L) 0L else now - lastAmplitudeTimeMs
        lastAmplitudeTimeMs = now

        // If the amplitude feed was interrupted, accumulated timing is stale.
        if (delta > STALE_FEED_MS) {
            state = State.IDLE
            chunkSpeechMs = 0L
            chunkSilenceMs = 0L
            chunkElapsedMs = 0L
        }
        val step = delta.coerceAtMost(MAX_AMPLITUDE_STEP_MS)

        when (state) {
            State.IDLE -> {
                // Ignore leading silence: no chunk starts until speech is detected.
                if (amplitude > speechThreshold) {
                    state = State.SPEAKING
                    chunkSpeechMs = step
                    chunkSilenceMs = 0L
                    chunkElapsedMs = step
                }
            }
            State.SPEAKING -> {
                chunkElapsedMs += step
                if (amplitude > speechThreshold) {
                    chunkSpeechMs += step
                    chunkSilenceMs = 0L
                } else {
                    chunkSilenceMs += step
                }

                if (chunkSilenceMs >= END_SILENCE_MS) {
                    if (chunkSpeechMs >= MIN_SPEECH_MS) {
                        // Enough speech was captured: this becomes a chunk.
                        endChunk()
                    } else {
                        // Too little speech (noise/blip): discard and keep waiting.
                        discardChunk()
                    }
                } else if (chunkElapsedMs >= MAX_CHUNK_MS) {
                    // Force-end long chunks without waiting for silence. Since a
                    // chunk only exists once speech started, it already contains
                    // (nearly) the required amount of speech.
                    endChunk()
                }
            }
        }
    }

    private fun endChunk() {
        resetFsm()
        completedChunkCount++
        Log.d(TAG, "Chunk #$completedChunkCount completed")
    }

    private fun discardChunk() {
        resetFsm()
        Log.d(TAG, "Chunk discarded (not enough speech)")
    }

    private fun resetFsm() {
        state = State.IDLE
        chunkSpeechMs = 0L
        chunkSilenceMs = 0L
        chunkElapsedMs = 0L
    }

    /**
     * Enqueues a finished chunk audio file for transcription. Files are
     * transcribed strictly in enqueue order on a single-threaded queue, and
     * each result is delivered through [onChunkText] (main thread) before the
     * next chunk starts, guaranteeing in-order commits. Each file is deleted
     * after its transcription attempt.
     */
    fun onSegmentReady(file: File) {
        val previous = lastEnqueuedJob
        pendingFiles.add(file)
        lastEnqueuedJob = queueScope.launch {
            // Strict ordering: wait until the previous chunk has fully finished
            // (including its callback) before starting this one.
            previous?.join()

            if (!isActive || !file.exists()) {
                file.delete()
                pendingFiles.remove(file)
                return@launch
            }

            try {
                val mediaType = if (file.extension.equals("ogg", ignoreCase = true)) {
                    LIVE_TRANSCRIBER_AUDIO_MEDIA_TYPE_OGG
                } else {
                    LIVE_TRANSCRIBER_AUDIO_MEDIA_TYPE_M4A
                }
                val text = whisperTranscriber.transcribe(appContext, file.absolutePath, mediaType)
                if (isActive && text.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onChunkText(text)
                    }
                }
            } catch (e: CancellationException) {
                // Queue was reset while this chunk was in flight; the file has
                // already been cleaned up by reset(). Keep cancellation intact.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chunk transcription failed: ${e.message}")
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        onError(e.message ?: "Chunk transcription failed")
                    }
                }
            } finally {
                // Clean up the chunk audio regardless of outcome.
                file.delete()
                pendingFiles.remove(file)
            }
        }
    }

    /**
     * Resets everything: FSM back to idle, queued and in-flight chunk
     * transcriptions cancelled, remaining temp chunk files deleted. The
     * transcriber stays usable afterwards.
     */
    fun reset() {
        resetFsm()
        lastAmplitudeTimeMs = 0L

        // Cancel all pending and running chunk transcriptions.
        queueJob.cancel()
        lastEnqueuedJob = null

        // Delete temp chunk files that were still queued or in flight. (In-flight
        // jobs also delete their own files in their finally blocks; double delete
        // is harmless.)
        synchronized(pendingFiles) {
            for (file in pendingFiles) {
                file.delete()
            }
            pendingFiles.clear()
        }

        // Recreate the queue so the transcriber remains usable after a reset.
        queueJob = SupervisorJob()
        queueScope = CoroutineScope(queueJob + queueDispatcher)
    }
}
