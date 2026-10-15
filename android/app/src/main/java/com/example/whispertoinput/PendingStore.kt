// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/** One recording waiting to be transcribed, or transcribed and waiting to be typed. */
data class PendingItem(
    val id: String,
    val audio: File,
    val createdAt: Long,
    val durationMs: Long,
    /** Text field the recording was made for; used to decide where the text may go. */
    val editorKey: String,
    /** Transcription result; null while the audio has not been transcribed yet. */
    val text: String?
)

/**
 * Persistent queue of recordings. Audio lives in app-private storage (not the
 * cache, which the system may wipe) and is only deleted once its text has been
 * typed, so a dropped VPN or a killed process never loses a dictation.
 */
class PendingStore(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val counter = AtomicInteger()

    init {
        dir.mkdirs()
    }

    /** Moves [source] into the queue and returns the new item. */
    @Synchronized
    fun add(source: File, durationMs: Long, editorKey: String): PendingItem {
        val createdAt = now()
        val id = "%013d-%04d".format(createdAt, counter.getAndIncrement() % 10000)
        val audio = audioFile(id)
        if (!source.renameTo(audio)) {
            source.copyTo(audio, overwrite = true)
            source.delete()
        }
        writeMeta(id, durationMs, editorKey, null)
        return PendingItem(id, audio, createdAt, durationMs, editorKey, null)
    }

    /** All items, oldest first. */
    @Synchronized
    fun list(): List<PendingItem> =
        (dir.listFiles { f -> f.name.endsWith(META_EXT) } ?: emptyArray())
            .mapNotNull { readItem(it) }
            .sortedBy { it.id }

    @Synchronized
    fun saveText(id: String, text: String) {
        val item = list().firstOrNull { it.id == id } ?: return
        writeMeta(id, item.durationMs, item.editorKey, text)
    }

    @Synchronized
    fun remove(id: String) {
        audioFile(id).delete()
        metaFile(id).delete()
    }

    /** Removes everything that has not been transcribed yet. */
    @Synchronized
    fun clearUntranscribed() {
        list().filter { it.text == null }.forEach { remove(it.id) }
    }

    /** Drops items older than [maxAgeMs] and, beyond that, the oldest above [maxCount]. */
    @Synchronized
    fun prune(maxAgeMs: Long = DEFAULT_MAX_AGE_MS, maxCount: Int = DEFAULT_MAX_COUNT) {
        val cutoff = now() - maxAgeMs
        val items = list()
        items.filter { it.createdAt < cutoff }.forEach { remove(it.id) }
        val kept = items.filter { it.createdAt >= cutoff }
        kept.take((kept.size - maxCount).coerceAtLeast(0)).forEach { remove(it.id) }
        // Audio files whose metadata is gone are unusable.
        dir.listFiles { f -> f.name.endsWith(AUDIO_EXT) }?.forEach {
            if (!metaFile(it.name.removeSuffix(AUDIO_EXT)).exists()) it.delete()
        }
    }

    private fun audioFile(id: String) = File(dir, id + AUDIO_EXT)
    private fun metaFile(id: String) = File(dir, id + META_EXT)

    private fun writeMeta(id: String, durationMs: Long, editorKey: String, text: String?) {
        val props = Properties().apply {
            setProperty("durationMs", durationMs.toString())
            setProperty("editorKey", editorKey)
            if (text != null) setProperty("text", text)
        }
        val tmp = File(dir, "$id.tmp")
        tmp.writer(Charsets.UTF_8).use { props.store(it, null) }
        if (!tmp.renameTo(metaFile(id))) {
            tmp.copyTo(metaFile(id), overwrite = true)
            tmp.delete()
        }
    }

    private fun readItem(meta: File): PendingItem? = try {
        val id = meta.name.removeSuffix(META_EXT)
        val audio = audioFile(id)
        if (!audio.exists()) {
            meta.delete()
            null
        } else {
            val props = Properties().apply { meta.reader(Charsets.UTF_8).use { load(it) } }
            PendingItem(
                id = id,
                audio = audio,
                createdAt = id.substringBefore('-').toLong(),
                durationMs = props.getProperty("durationMs")?.toLongOrNull() ?: 0L,
                editorKey = props.getProperty("editorKey") ?: "",
                text = props.getProperty("text")
            )
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_MAX_COUNT = 20
        private const val AUDIO_EXT = ".m4a"
        private const val META_EXT = ".meta"
    }
}
