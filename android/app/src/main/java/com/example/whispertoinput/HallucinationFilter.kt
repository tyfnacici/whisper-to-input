// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import java.util.Locale

/**
 * Removes text that Whisper invents over silence or noise ("Altyazı M.K.",
 * "Abone olmayı unutmayın") and non-speech tags such as `[BLANK_AUDIO]` or `[Müzik]`.
 *
 * Filtering is per sentence and by exact match of the normalized sentence against
 * [BLOCKLIST], so real speech that merely contains a word like "abone" is kept.
 */
object HallucinationFilter {
    private val TR = Locale("tr")

    /** Bracketed/parenthesized/starred tags and music notes are never dictated text. */
    private val NON_SPEECH_TAG = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*|[♪♫]""")
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?…])\s+|\n+""")
    private val PUNCTUATION = Regex("""[\p{P}\p{S}]""")
    private val SPACES = Regex("""\s+""")

    /** Normalized (see [normalize]) sentences that are known Whisper hallucinations. */
    internal val BLOCKLIST: Set<String> = setOf(
        "altyazı mk",
        "altyazı m k",
        "altyazı mkalkan",
        "abone olmayı unutmayın",
        "kanalıma abone olmayı unutmayın",
        "beğenmeyi ve abone olmayı unutmayın",
        "videoyu beğenmeyi unutmayın",
        "izlediğiniz için teşekkürler",
        "izlediğiniz için teşekkür ederim",
        "izlediğiniz için teşekkür ederiz",
        "bir sonraki videoda görüşmek üzere",
        "bir sonraki videoda görüşmek üzere hoşça kalın",
        "altyazılar amaraorg topluluğu tarafından sağlanmıştır",
        "thank you for watching",
        "subtitles by the amaraorg community",
    )

    /** Returns [raw] without hallucinated sentences and tags; empty if nothing remains. */
    fun clean(raw: String): String =
        NON_SPEECH_TAG.replace(raw, " ")
            .trim()
            .split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() && normalize(it) !in BLOCKLIST }
            .joinToString(" ")

    /** Turkish-locale lowercase, punctuation removed, spaces collapsed. */
    internal fun normalize(sentence: String): String =
        SPACES.replace(PUNCTUATION.replace(sentence.lowercase(TR), ""), " ").trim()
}
