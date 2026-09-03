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

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import com.google.android.material.color.DynamicColors
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = AUDIO_MEDIA_TYPE_M4A
    private var useOggFormat: Boolean = false
    private var isFirstTime: Boolean = true

    // Live transcription state (only active when the LIVE_TRANSCRIPTION pref is on
    // and a recording session is running).
    private var liveModeEnabled: Boolean = false
    private var liveTranscriber: LiveTranscriber? = null
    private var liveChunkIndex: Int = 0

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            currentInputConnection?.commitText(text, 1)
            // Check if auto-switch-back is enabled and switch if so
            CoroutineScope(Dispatchers.Main).launch {
                val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_SWITCH_BACK] ?: false
                }.first()
                if (autoSwitchBack) {
                    onSwitchIme()
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    private suspend fun updateAudioFormat() {
        val backend = dataStore.data.map { preferences: Preferences ->
            preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
        }.first()

        useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        if (useOggFormat) {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}"
            audioMediaType = AUDIO_MEDIA_TYPE_OGG
        } else {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}"
            audioMediaType = AUDIO_MEDIA_TYPE_M4A
        }
    }

    private suspend fun updateLiveMode() {
        liveModeEnabled = dataStore.data.map { preferences: Preferences ->
            preferences[LIVE_TRANSCRIPTION] ?: true
        }.first()
    }

    // Called when the LiveTranscriber FSM completes a chunk: finalize the current
    // segment file, pass it to the ordered queue, and keep recording seamlessly.
    private fun onLiveChunkBoundary() {
        val manager = recorderManager ?: return
        val transcriber = liveTranscriber ?: return

        val chunkFilename = "${externalCacheDir?.absolutePath}/chunk_${liveChunkIndex++}" +
            if (useOggFormat) ".ogg" else ".m4a"
        val chunkFile = File(recordedAudioFilename)
        val moved = if (chunkFile.exists()) {
            try {
                chunkFile.renameTo(File(chunkFilename))
            } catch (e: Exception) {
                Log.w("whisper-input", "chunk rename failed: ${e.message}")
                false
            }
        } else {
            false
        }

        // Restart recording into the standard file immediately so no words are lost.
        if (!manager.restart(this, recordedAudioFilename, useOggFormat)) {
            Log.w("whisper-input", "recorder restart failed; falling back to single-shot")
            stopLiveMode()
            return
        }

        if (moved) {
            transcriber.onSegmentReady(File(chunkFilename))
        }
    }

    private fun stopLiveMode() {
        liveTranscriber?.reset()
        liveTranscriber = null
        liveModeEnabled = false
        liveChunkIndex = 0
    }

    private fun cleanupChunkFiles() {
        val cacheDir = externalCacheDir ?: return
        cacheDir.listFiles { file -> file.name.startsWith("chunk_") }?.forEach { it.delete() }
    }

    override fun onCreateInputView(): View {
        // Initialize members with regard to this context
        recorderManager = RecorderManager(this)

        // Preload conversion table
        ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
        ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)

        // Initialize audio format based on backend setting
        CoroutineScope(Dispatchers.Main).launch {
            updateAudioFormat()
        }

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager
        recorderManager!!.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }

        // The IME inflates under the system Theme.DeviceDefault.InputMethod, which
        // has no Material 3 attributes (colorSurface etc.) — inflating with it
        // crashes on any ?attr/ M3 reference. Wrap the context in an inflated
        // version of OUR Material 3 theme (with dynamic colors applied on top,
        // so the wallpaper palette is used when available).
        val themedContext = ContextThemeWrapper(
            this,
            R.style.Theme_WhisperToInput
        )
        DynamicColors.wrapContextIfAvailable(themedContext)
        val themedLayoutInflater = themedContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater

        // Returns the keyboard after setting it up and inflating its layout
        return whisperKeyboard.setup(themedLayoutInflater,
            shouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { shouldShowRetry() },
        )
    }

    private fun onStartRecording() {
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        // Start a fresh live-transcription session if the setting is enabled.
        CoroutineScope(Dispatchers.Main).launch {
            updateLiveMode()
            if (liveModeEnabled) {
                cleanupChunkFiles()
                liveChunkIndex = 0
                liveTranscriber = LiveTranscriber(
                    this@WhisperInputService,
                    { text ->
                        // Ordered chunk result: commit as it arrives. The keyboard
                        // stays in Recording state (no reset) — live mode keeps
                        // listening while text streams in.
                        if (text.isNotEmpty()) {
                            currentInputConnection?.commitText(text, 1)
                        }
                    },
                    { message ->
                        // Chunk failure must not kill the session; show and keep going.
                        Toast.makeText(this@WhisperInputService, message, Toast.LENGTH_SHORT).show()
                    })
            }
        }

        recorderManager!!.start(this, recordedAudioFilename, useOggFormat)
    }

    // when mic amplitude is updated, notify the keyboard
    // this callback is registered to the recorder manager
    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        whisperKeyboard.updateMicrophoneAmplitude(amplitude)

        // Feed the live FSM and detect chunk boundaries.
        val transcriber = liveTranscriber ?: return
        val before = transcriber.completedChunkCount
        transcriber.onAmplitude(amplitude)
        if (transcriber.completedChunkCount > before) {
            onLiveChunkBoundary()
        }
    }

    private fun onCancelRecording() {
        stopLiveMode()
        recorderManager!!.stop()
    }

    private fun onStartTranscription(attachToEnd: String) {
        val live = liveTranscriber
        if (live != null) {
            // Live mode final press: stop recording, enqueue the final remaining
            // segment through the ordered queue so its text lands after previous
            // chunks, then tear the live session down.
            recorderManager!!.stop()
            val finalFile = File(recordedAudioFilename)
            if (finalFile.exists()) {
                val tailFilename = "${externalCacheDir?.absolutePath}/chunk_${liveChunkIndex++}" +
                    if (useOggFormat) ".ogg" else ".m4a"
                if (finalFile.renameTo(File(tailFilename))) {
                    live.onSegmentReady(File(tailFilename))
                }
            }
            // The queue drains in the background; nothing else to wait on here.
            // Keyboard resets so the user can keep typing while the tail commits.
            whisperKeyboard.reset()
        } else {
            recorderManager!!.stop()
            whisperTranscriber.startAsync(this,
                recordedAudioFilename,
                audioMediaType,
                attachToEnd,
                { transcriptionCallback(it) },
                { transcriptionExceptionCallback(it) })
        }
    }

    private fun onCancelTranscription() {
        stopLiveMode()
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        stopLiveMode()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to.
        // Automatically starts recording after switching to Whisper Input. (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            // Update audio format based on current backend setting
            updateAudioFormat()
            if (!isFirstTime) return@launch
            isFirstTime = false
            val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_RECORDING_START] ?: true
            }.first()
            if (isAutoStartRecording) {
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopLiveMode()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLiveMode()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }
}
