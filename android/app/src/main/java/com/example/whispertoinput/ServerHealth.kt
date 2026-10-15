// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Cheap reachability check: any HTTP answer from the server root counts as up. */
object ServerHealth {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Returns the round-trip time in milliseconds, or null if the server is unreachable. */
    suspend fun ping(endpoint: String): Long? = withContext(Dispatchers.IO) {
        val url = Endpoint.healthUrl(endpoint) ?: return@withContext null
        val started = SystemClock.elapsedRealtime()
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use {
                SystemClock.elapsedRealtime() - started
            }
        } catch (e: Exception) {
            null
        }
    }
}
