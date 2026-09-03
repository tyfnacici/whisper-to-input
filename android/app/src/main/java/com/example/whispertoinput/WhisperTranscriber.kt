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
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import com.github.liuyueyi.quick.transfer.ChineseUtils

class WhisperTranscriber {
    private data class Config(
        val endpoint: String,
        val languageCode: String,
        val speechToTextBackend: String,
        val postprocessing: String,
        val addTrailingSpace: Boolean,
        val requestTimeout: String
    )

    private val TAG = "WhisperTranscriber"
    private var currentTranscriptionJob: Job? = null

    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit
    ) {
        suspend fun makeWhisperRequest(): String {
            // Retrieve configs
            val (endpoint, languageCode, speechToTextBackend, postprocessing, addTrailingSpace, requestTimeout) = context.dataStore.data.map { preferences: Preferences ->
                Config(
                    preferences[ENDPOINT] ?: "",
                    preferences[LANGUAGE_CODE] ?: "",
                    preferences[SPEECH_TO_TEXT_BACKEND] ?: context.getString(R.string.settings_option_openai_api),
                    preferences[POSTPROCESSING] ?: context.getString(R.string.settings_option_no_conversion),
                    preferences[ADD_TRAILING_SPACE] ?: false,
                    preferences[REQUEST_TIMEOUT] ?: context.getString(R.string.settings_option_timeout_auto)
                )
            }.first()

            // Foolproof message
            if (endpoint == "") {
                throw Exception(context.getString(R.string.error_endpoint_unset))
            }

            // Make request
            // The read timeout adapts to the recording length so that long dictations
            // are not cut off by the client (the self-hosted server may need a while
            // to transcribe). Fixed overrides are also available in the settings.
            val readTimeoutSeconds: Long = resolveReadTimeoutSeconds(context, requestTimeout, filename)
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build()
            val request = buildWhisperRequest(
                context,
                filename,
                mediaType,
                speechToTextBackend,
                endpoint,
                languageCode
            )
            val response = client.newCall(request).execute()

            // If request is not successful, or response code is weird
            if (!response.isSuccessful || response.code / 100 != 2) {
                throw Exception(response.body!!.string().replace('\n', ' '))
            }

            var rawText = response.body!!.string().trim()
            
            // For NVIDIA NIM, remove quotes if they wrap the text
            // Not sure if this is a bug or a feature...
            if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim) && 
                rawText.startsWith("\"") && rawText.endsWith("\"")) {
                rawText = rawText.substring(1, rawText.length - 1).trim()
            }
            
            val processedText = when (postprocessing) {
                context.getString(R.string.settings_option_to_simplified) -> ChineseUtils.tw2s(rawText)
                context.getString(R.string.settings_option_to_traditional) -> ChineseUtils.s2tw(rawText)
                else -> rawText // No conversion
            }

            if (attachToEnd == "") {
                return processedText + if (addTrailingSpace) " " else ""
            } else {
                // Only used for space key and enter key.
                return processedText + attachToEnd
            }
        }

        // Create a cancellable job in the main thread (for UI updating)
        val job = CoroutineScope(Dispatchers.Main).launch {

            // Within the job, make a suspend call at the I/O thread
            // It suspends before result is obtained.
            // Returns (transcribed string, exception message)
            val (transcribedText, exceptionMessage) = withContext(Dispatchers.IO) {
                try {
                    // Perform transcription here
                    val response = makeWhisperRequest()
                    // Clean up unused audio file after transcription
                    // Ref: https://developer.android.com/reference/android/media/MediaRecorder#setOutputFile(java.io.File)
                    File(filename).delete()
                    return@withContext Pair(response, null)
                } catch (e: CancellationException) {
                    // Task was canceled
                    return@withContext Pair(null, null)
                } catch (e: Exception) {
                    return@withContext Pair(null, e.message)
                }
            }

            // This callback is within the main thread.
            callback.invoke(transcribedText)

            // If exception message is not null
            if (!exceptionMessage.isNullOrEmpty()) {
                Log.e(TAG, exceptionMessage)
                exceptionCallback(exceptionMessage)
            }
        }

        registerTranscriptionJob(job)
    }

    fun stop() {
        registerTranscriptionJob(null)
    }

    // Resolves the read timeout (in seconds) for the transcription request.
    // - "Auto": adapts to the audio duration (duration * 4 + 30 seconds),
    //   falling back to 10 minutes if the duration cannot be determined.
    // - Fixed values (60s, 300s, 600s) simply override the timeout.
    private fun resolveReadTimeoutSeconds(context: Context, requestTimeout: String, filename: String): Long {
        if (requestTimeout == context.getString(R.string.settings_option_timeout_60s)) return 60L
        if (requestTimeout == context.getString(R.string.settings_option_timeout_300s)) return 300L
        if (requestTimeout == context.getString(R.string.settings_option_timeout_600s)) return 600L
        // "Auto" (default) and any unknown value: adaptive timeout
        val audioDurationSeconds: Long? = getAudioDurationSeconds(filename)
        return if (audioDurationSeconds == null) {
            // Fall back to 10 minutes if duration cannot be determined
            600L
        } else {
            audioDurationSeconds * 4 + 30L
        }
    }

    // Returns the duration of the recorded audio file in seconds, or null if it
    // cannot be determined.
    private fun getAudioDurationSeconds(filename: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filename)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { milliseconds ->
                ceil(milliseconds / 1000.0).toLong()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to determine audio duration: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun registerTranscriptionJob(job: Job?) {
        currentTranscriptionJob?.cancel()
        currentTranscriptionJob = job
    }

    private fun buildWhisperRequest(
        context: Context,
        filename: String,
        mediaType: String,
        speechToTextBackend: String,
        endpoint: String,
        languageCode: String
    ): Request {
        // Please refer to the following for the endpoint/payload definitions:
        // OpenAI API:
        // - https://platform.openai.com/docs/api-reference/audio/createTranscription
        // - https://platform.openai.com/docs/api-reference/making-requests
        // Whisper ASR WebService:
        // - https://ahmetoner.com/whisper-asr-webservice/run/#usage
        // NVIDIA NIM:
        // - No public documentation for HTTP-style requests.
        // - Source code at `/opt/nim/inference.py` in docker container `nvcr.io/nim/nvidia/riva-asr:1.3.0`.
        /*
            ...
            @HttpNIMApiInterface.route('/v1/audio/transcriptions', methods=["post"])
            async def transcriptions(
                self,
                file: UploadFile = File(...),
                model: Optional[str] = Form(None),
                language: Optional[str] = Form(None),
                prompt: Optional[str] = Form(None),
                response_format: Optional[str] = Form(None),
                temperature: Optional[float] = Form(None),
            ):
            ...
         */
        val file: File = File(filename)
        val fileBody: RequestBody = file.asRequestBody(mediaType.toMediaTypeOrNull())
        val requestBody: RequestBody = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            // Determine filename based on media type
            val formDataFilename = if (mediaType == "audio/ogg") "@audio.ogg" else "@audio.m4a"
            
            // Add file to payload
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api) || 
                speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim)) {
                addFormDataPart("file", formDataFilename, fileBody)
            } else if (speechToTextBackend == context.getString(R.string.settings_option_whisper_asr_webservice)) {
                addFormDataPart("audio_file", formDataFilename, fileBody)
            }
            // Add backend-specific parameters to payload
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api)) {
                addFormDataPart("response_format", "text")
            }
            if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim)) {
                addFormDataPart("language", languageCode)
                addFormDataPart("response_format", "text")
            }
        }.build()

        val requestHeaders: Headers = Headers.Builder().apply {
            add("Content-Type", "multipart/form-data")
        }.build()

        // Build URL with endpoint-specific parameters
        val url = when (speechToTextBackend) {
            context.getString(R.string.settings_option_openai_api),
            context.getString(R.string.settings_option_whisper_asr_webservice) -> {
                "$endpoint?encode=true&task=transcribe&language=$languageCode&word_timestamps=false&output=txt"
            }
            else -> endpoint
        }

        return Request.Builder()
            .headers(requestHeaders)
            .url(url)
            .post(requestBody)
            .build()
    }
}
