// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationFilterTest {
    private fun assertDropped(raw: String) = assertEquals("", HallucinationFilter.clean(raw))
    private fun assertKept(raw: String) = assertEquals(raw, HallucinationFilter.clean(raw))

    @Test fun subtitleCredit() = assertDropped("Altyazı M.K.")
    @Test fun subtitleCreditWithColon() = assertDropped("Altyazı: M.K.")
    @Test fun blankAudioTag() = assertDropped(" [BLANK_AUDIO] ")
    @Test fun musicTag() = assertDropped("[Müzik]")
    @Test fun parenthesizedMusic() = assertDropped("(müzik)")
    @Test fun starredMusic() = assertDropped("*müzik*")
    @Test fun musicNote() = assertDropped("♪")
    @Test fun subscribePrompt() = assertDropped("Abone olmayı unutmayın!")
    @Test fun thanksForWatching() = assertDropped("İzlediğiniz için teşekkürler.")
    @Test fun englishBoilerplate() = assertDropped("Thank you for watching.")
    @Test fun multipleHallucinations() =
        assertDropped("[BLANK_AUDIO]\nAltyazı M.K.\nAbone olmayı unutmayın.")
    @Test fun emptyInput() = assertDropped("")

    @Test fun hallucinationAfterRealSpeechIsDropped() =
        assertEquals("Yarın toplantı var.", HallucinationFilter.clean("Yarın toplantı var. Altyazı M.K."))

    @Test fun sentenceContainingAboneIsKept() = assertKept("Bu kanala abone olmayı düşünüyorum.")
    @Test fun plainThanksIsKept() = assertKept("Teşekkürler, görüşürüz.")
    @Test fun mixedTurkishEnglishIsKept() = assertKept("CI fail oldu, PR'ı merge et.")
    @Test fun multiSentenceSpeechIsKept() = assertKept("Merhaba. Bugün toplantı var! Geliyor musun?")

    @Test fun blocklistIsAlreadyNormalized() {
        for (entry in HallucinationFilter.BLOCKLIST) {
            assertEquals(entry, HallucinationFilter.normalize(entry))
        }
        assertTrue(HallucinationFilter.BLOCKLIST.isNotEmpty())
    }

    @Test fun turkishCapitalIIsLowercasedCorrectly() =
        assertEquals("izlediğiniz için teşekkürler", HallucinationFilter.normalize("İZLEDİĞİNİZ İÇİN TEŞEKKÜRLER."))
}
