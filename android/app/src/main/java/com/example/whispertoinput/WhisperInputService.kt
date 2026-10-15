// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.example.whispertoinput.keyboard.KeyboardListener
import com.example.whispertoinput.keyboard.KeyboardState
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val MIN_RECORDING_MS = 700L
private const val PING_INTERVAL_MS = 10_000L
private const val WATCHDOG_INTERVAL_MS = 5_000L
private const val WATCHDOG_MAX_FAILURES = 4

class WhisperInputService : InputMethodService(), KeyboardListener {
    // A stray exception must never take the keyboard process down.
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        Log.e("whisper-input", "unhandled error: ${e.message}", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)
    private val keyboard = WhisperKeyboard()
    private lateinit var recorder: RecorderManager
    private lateinit var store: PendingStore

    private var windowVisible = false
    private var editorKey = ""

    private var recording = false
    private var recordStartedAt = 0L
    private var recordEditorKey = ""
    private var recordWasAutoStarted = false

    private var sendJob: Job? = null
    private var sending = false
    private var sendStartedAt = 0L

    private var online: Boolean? = null
    private var lastError: String? = null

    private var tickerJob: Job? = null
    private var monitorJob: Job? = null
    private val pingNow = Channel<Unit>(Channel.CONFLATED)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        recorder = RecorderManager(this)
        store = PendingStore(File(filesDir, "pending"))
        store.prune()
        registerNetworkCallback()
    }

    override fun onCreateInputView(): View {
        val themed = ContextThemeWrapper(this, R.style.Theme_WhisperToInput)
        val inflater = themed.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = keyboard.setup(inflater, shouldOfferImeSwitch(), this)
        render()
        return view
    }

    private fun shouldOfferImeSwitch(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            shouldOfferSwitchingToNextInputMethod()
        } else {
            val token: IBinder? = window?.window?.attributes?.token
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .shouldOfferSwitchingToNextInputMethod(token)
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorKey = "${attribute?.packageName}:${attribute?.fieldId}"
    }

    override fun onWindowShown() {
        super.onWindowShown()
        windowVisible = true
        startMonitor()
        scope.launch {
            insertReady(onlyThisField = true)
            val autoStart = readBoolean(AUTO_RECORDING_START, true)
            if (autoStart && !recording && windowVisible) startRecording(auto = true)
            render()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        windowVisible = false
        monitorJob?.cancel()
        monitorJob = null
        // A transcription that is already running keeps going; its text waits for the next field.
        if (recording) {
            // An auto-started recording nobody asked for is dropped; a manual one is kept.
            if (recordWasAutoStarted) cancelRecording() else finishRecording(send = false)
        }
        render()
    }

    override fun onDestroy() {
        if (recording) finishRecording(send = false)
        networkCallback?.let {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- recording

    override fun onStartRecording() {
        if (!recording) startRecording(auto = false)
    }

    override fun onStopRecording() {
        if (recording) finishRecording(send = true)
    }

    private fun recordingFile() = File(cacheDir, "recording.m4a")

    private fun startRecording(auto: Boolean) {
        if (!recorder.hasMicrophonePermission()) {
            if (!auto) launchMainActivity()
            return
        }
        if (!recorder.start(recordingFile())) {
            Toast.makeText(this, R.string.toast_mic_failed, Toast.LENGTH_SHORT).show()
            return
        }
        recording = true
        recordWasAutoStarted = auto
        recordStartedAt = SystemClock.elapsedRealtime()
        recordEditorKey = editorKey
        startTicker()
        render()
    }

    private fun cancelRecording() {
        if (!recording) return
        recording = false
        recorder.stop()
        recordingFile().delete()
        render()
    }

    /** Stops the recording and queues it; sends it right away when [send] is true. */
    private fun finishRecording(send: Boolean) {
        recording = false
        val durationMs = recorder.stop()
        if (durationMs == null || durationMs < MIN_RECORDING_MS) {
            recordingFile().delete()
            if (durationMs != null && send) {
                Toast.makeText(this, R.string.toast_too_short, Toast.LENGTH_SHORT).show()
            }
        } else {
            store.add(recordingFile(), durationMs, recordEditorKey)
            if (send) processQueue()
        }
        render()
    }

    // ------------------------------------------------------------- transcription

    /** Sends queued recordings one by one, oldest first, and types their text in order. */
    private fun processQueue() {
        if (sending) return
        lastError = null
        sending = true
        sendStartedAt = SystemClock.elapsedRealtime()
        sendJob = scope.launch {
            val self = coroutineContext.job
            var watchdogTripped = false
            val watchdog = launch {
                var failures = 0
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    failures = if (ServerHealth.ping(readEndpoint()) == null) failures + 1 else 0
                    if (failures >= WATCHDOG_MAX_FAILURES) {
                        watchdogTripped = true
                        self.cancel()
                        return@launch
                    }
                }
            }
            try {
                while (true) {
                    val next = store.list().firstOrNull { it.text == null } ?: break
                    val text = Transcriber.transcribe(readEndpoint(), next.audio, next.durationMs)
                    if (text.isEmpty()) {
                        store.remove(next.id)
                        Toast.makeText(this@WhisperInputService, R.string.toast_no_speech, Toast.LENGTH_SHORT).show()
                    } else {
                        store.saveText(next.id, text)
                        insertReady(onlyThisField = true)
                    }
                    online = true
                    render()
                }
            } catch (e: TranscriptionException) {
                lastError = if (e.retryable) getString(R.string.error_unreachable) else e.message
                if (e.retryable) online = false
            } catch (e: CancellationException) {
                if (watchdogTripped) {
                    lastError = getString(R.string.error_unreachable)
                    online = false
                }
            } catch (e: Exception) {
                Log.e("whisper-input", "send failed: ${e.message}", e)
                lastError = e.message ?: getString(R.string.error_unreachable)
            } finally {
                watchdog.cancel()
                sending = false
                render()
            }
        }
        sendJob?.invokeOnCompletion { sending = false }
        startTicker()
        render()
    }

    override fun onCancel() {
        when {
            recording -> cancelRecording()
            sending -> {
                // The audio stays queued so it can be sent again.
                sendJob?.cancel()
                lastError = null
                render()
            }
        }
    }

    override fun onRetry() = processQueue()

    override fun onDiscardPending() {
        store.clearUntranscribed()
        lastError = null
        Toast.makeText(this, R.string.toast_discarded, Toast.LENGTH_SHORT).show()
        render()
    }

    // ------------------------------------------------------------- typing results

    /**
     * Types waiting texts into the current field, oldest first. With [onlyThisField]
     * only texts recorded for this very field are typed; otherwise all of them.
     */
    private suspend fun insertReady(onlyThisField: Boolean) {
        val ic = currentInputConnection ?: return
        if (!windowVisible) return
        val ready = store.list().filter {
            it.text != null && (!onlyThisField || it.editorKey == editorKey)
        }
        if (ready.isEmpty()) return
        ic.beginBatchEdit()
        for (item in ready) {
            val before = ic.getTextBeforeCursor(1, 0)
            val needsSpace = !before.isNullOrEmpty() && !before[0].isWhitespace()
            ic.commitText((if (needsSpace) " " else "") + item.text.orEmpty(), 1)
            store.remove(item.id)
        }
        ic.endBatchEdit()
        if (readBoolean(AUTO_SWITCH_BACK, false)) onSwitchIme()
        render()
    }

    override fun onInsertReady() {
        scope.launch { insertReady(onlyThisField = false) }
    }

    // ---------------------------------------------------------- connection status

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                pingNow.trySend(Unit)
            }

            override fun onLost(network: Network) {
                pingNow.trySend(Unit)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    /** Checks the server while the keyboard is visible, and again whenever the network changes. */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val reachable = ServerHealth.ping(readEndpoint()) != null
                if (reachable != online) {
                    online = reachable
                    if (reachable && !sending) lastError = null
                    render()
                }
                withTimeoutOrNull(PING_INTERVAL_MS) { pingNow.receive() }
            }
        }
    }

    // -------------------------------------------------------------------- UI state

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && (recording || sending)) {
                render()
                delay(500)
            }
            render()
        }
    }

    private fun render() {
        val items = store.list()
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = if (recording) now - recordStartedAt else if (sending) now - sendStartedAt else 0L
        keyboard.render(
            KeyboardState(
                recording = recording,
                sending = sending,
                elapsedSeconds = elapsedMs / 1000,
                pendingCount = items.count { it.text == null },
                readyCount = items.count { it.text != null },
                online = online,
                error = lastError
            )
        )
    }

    // ------------------------------------------------------------------ other keys

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (TextUtils.isEmpty(ic.getSelectedText(0))) ic.deleteSurroundingText(1, 0)
        else ic.commitText("", 1)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    override fun onSpace() {
        currentInputConnection?.commitText(" ", 1)
    }

    override fun onSwitchIme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            val token: IBinder? = window?.window?.attributes?.token
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).switchToLastInputMethod(token)
        }
    }

    override fun onOpenSettings() = launchMainActivity()

    private fun launchMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
