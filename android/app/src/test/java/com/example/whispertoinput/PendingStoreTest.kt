// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PendingStoreTest {
    private lateinit var root: File
    private lateinit var dir: File
    private var clock = 1_000_000_000L
    private lateinit var store: PendingStore

    @Before fun setUp() {
        root = Files.createTempDirectory("pending-test").toFile()
        dir = File(root, "pending")
        store = PendingStore(dir) { clock }
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun audio(name: String = "rec.m4a"): File =
        File(root, name).apply { writeText("audio") }

    @Test fun addMovesAudioIntoQueue() {
        val src = audio()
        val item = store.add(src, 2_500, "app:1")
        assertFalse(src.exists())
        assertTrue(item.audio.exists())
        assertEquals(listOf(item.id), store.list().map { it.id })
        assertEquals(2_500L, store.list().single().durationMs)
        assertEquals("app:1", store.list().single().editorKey)
        assertNull(store.list().single().text)
    }

    @Test fun itemsAreOrderedOldestFirst() {
        val a = store.add(audio("a.m4a"), 1000, "k")
        clock += 10
        val b = store.add(audio("b.m4a"), 1000, "k")
        clock += 10
        val c = store.add(audio("c.m4a"), 1000, "k")
        assertEquals(listOf(a.id, b.id, c.id), store.list().map { it.id })
    }

    @Test fun savedTextSurvivesReload() {
        val item = store.add(audio(), 1000, "k")
        store.saveText(item.id, "merhaba dünya\nikinci satır ğüşıöç")
        val reloaded = PendingStore(dir) { clock }.list().single()
        assertEquals("merhaba dünya\nikinci satır ğüşıöç", reloaded.text)
        assertTrue(reloaded.audio.exists())
    }

    @Test fun removeDeletesAudioAndMetadata() {
        val item = store.add(audio(), 1000, "k")
        store.remove(item.id)
        assertTrue(store.list().isEmpty())
        assertFalse(item.audio.exists())
    }

    @Test fun clearUntranscribedKeepsReadyTexts() {
        val a = store.add(audio("a.m4a"), 1000, "k")
        clock += 1
        val b = store.add(audio("b.m4a"), 1000, "k")
        store.saveText(b.id, "hazır")
        store.clearUntranscribed()
        assertEquals(listOf(b.id), store.list().map { it.id })
        assertFalse(a.audio.exists())
    }

    @Test fun pruneDropsItemsOlderThanMaxAge() {
        val old = store.add(audio("old.m4a"), 1000, "k")
        clock += 25L * 60 * 60 * 1000
        val fresh = store.add(audio("new.m4a"), 1000, "k")
        store.prune()
        assertEquals(listOf(fresh.id), store.list().map { it.id })
        assertFalse(old.audio.exists())
    }

    @Test fun pruneKeepsOnlyNewestMaxCount() {
        val ids = (1..25).map {
            clock += 1000
            store.add(audio("r$it.m4a"), 1000, "k").id
        }
        store.prune()
        assertEquals(ids.takeLast(20), store.list().map { it.id })
    }

    @Test fun orphanAudioWithoutMetadataIsRemoved() {
        val item = store.add(audio(), 1000, "k")
        File(dir, item.id + ".meta").delete()
        store.prune()
        assertFalse(item.audio.exists())
    }
}
