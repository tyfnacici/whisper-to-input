// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.net.URI

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

val ENDPOINT = stringPreferencesKey("endpoint")
val AUTO_RECORDING_START = booleanPreferencesKey("is-auto-recording-start")
val AUTO_SWITCH_BACK = booleanPreferencesKey("auto-switch-back")

/** whisper.cpp server exposed on the tailnet (see README). */
const val DEFAULT_ENDPOINT = "http://100.124.60.71:8815/inference"

// Earlier builds shipped a default pointing at a port nothing listens on.
private const val LEGACY_DEFAULT_ENDPOINT = "http://100.124.60.71:8814/inference"

object Endpoint {
    /** Replaces the stale default of earlier builds; keeps anything the user typed. */
    fun migrate(stored: String?): String = when (stored) {
        null, "", LEGACY_DEFAULT_ENDPOINT -> DEFAULT_ENDPOINT
        else -> stored.trim()
    }

    /** True for a well-formed http(s) URL with a host. */
    fun isValid(endpoint: String): Boolean = try {
        val uri = URI(endpoint.trim())
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    /** Server root (scheme://host:port/) used for the cheap reachability check. */
    fun healthUrl(endpoint: String): String? = try {
        val uri = URI(endpoint.trim())
        if (uri.scheme == null || uri.host.isNullOrEmpty()) null
        else URI(uri.scheme, null, uri.host, uri.port, "/", null, null).toString()
    } catch (e: Exception) {
        null
    }
}

suspend fun Context.readEndpoint(): String =
    Endpoint.migrate(dataStore.data.first()[ENDPOINT])

suspend fun Context.readBoolean(key: Preferences.Key<Boolean>, default: Boolean): Boolean =
    dataStore.data.first()[key] ?: default
