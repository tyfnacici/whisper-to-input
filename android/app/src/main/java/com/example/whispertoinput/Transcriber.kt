// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [retryable] is true when sending the same audio again later can succeed. */
class TranscriptionException(message: String, val retryable: Boolean) : Exception(message)

/**
 * Sends a recording to a whisper.cpp server (`/inference`). Model, language and
 * prompt come from the server's own configuration, so only the audio is sent.
 */
object Transcriber {
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val BLANK_AUDIO = Regex("""\[BLANK_AUDIO]""")

    /** Returns the transcribed text, empty if no speech was found. */
    suspend fun transcribe(endpoint: String, audio: File, durationMs: Long?): String {
        if (!Endpoint.isValid(endpoint)) {
            throw TranscriptionException("Sunucu adresi ayarlanmamış.", retryable = false)
        }
        val client = baseClient.newBuilder()
            .writeTimeout(TimeoutPolicy.writeTimeoutSeconds(durationMs), TimeUnit.SECONDS)
            .readTimeout(TimeoutPolicy.readTimeoutSeconds(durationMs), TimeUnit.SECONDS)
            .build()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.m4a", audio.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("response_format", "text")
            .build()
        val request = Request.Builder().url(endpoint.trim()).post(body).build()

        val response = execute(client.newCall(request))
        when {
            response.successful -> return BLANK_AUDIO.replace(response.body, "").trim()
            response.code >= 500 -> throw TranscriptionException(
                "Sunucu hata verdi (${response.code}).", retryable = true
            )
            else -> throw TranscriptionException(
                "Sunucu isteği reddetti (${response.code}): ${response.body.replace('\n', ' ').take(120)}",
                retryable = false
            )
        }
    }

    private class RawResponse(val code: Int, val successful: Boolean, val body: String)

    // Async enqueue so that cancelling the coroutine cancels the HTTP call at once.
    // The body is read in the callback, on OkHttp's own thread, never on the caller's.
    private suspend fun execute(call: Call): RawResponse =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use { it.body?.string().orEmpty() }
                        cont.resume(RawResponse(response.code, response.isSuccessful, body))
                    } catch (e: IOException) {
                        fail(e)
                    }
                }

                override fun onFailure(call: Call, e: IOException) = fail(e)

                private fun fail(e: IOException) {
                    if (cont.isCancelled) return
                    val message = if (e is SocketTimeoutException) {
                        "Sunucu zamanında yanıt vermedi."
                    } else {
                        "Sunucuya ulaşılamadı."
                    }
                    cont.resumeWithException(TranscriptionException(message, retryable = true))
                }
            })
        }
}
