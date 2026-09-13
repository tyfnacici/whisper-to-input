package com.example.whispertoinput

import com.github.liuyueyi.quick.transfer.ChineseUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class TextPostProcessingTest {
    private val simplified = "Convert to Simplified Chinese"
    private val traditional = "Convert to Traditional Chinese"

    @Test fun unknownModeWithoutTrailingSpaceLeavesTextUnchanged() {
        assertEquals("你好", TextPostProcessing.apply("No Conversion", simplified, traditional, false, "你好"))
    }

    @Test fun unknownModeWithTrailingSpaceAppendsExactlyOneSpace() {
        assertEquals("你好 ", TextPostProcessing.apply("No Conversion", simplified, traditional, true, "你好"))
    }

    @Test fun simplifiedModeMatchesChineseUtils() {
        val text = "繁體中文"
        assertEquals(ChineseUtils.tw2s(text), TextPostProcessing.apply(simplified, simplified, traditional, false, text))
    }

    @Test fun simplifiedModeAppendsTrailingSpace() {
        val text = "繁體中文"
        assertEquals(ChineseUtils.tw2s(text) + " ", TextPostProcessing.apply(simplified, simplified, traditional, true, text))
    }

    @Test fun traditionalModeMatchesChineseUtils() {
        val text = "简体中文"
        assertEquals(ChineseUtils.s2tw(text), TextPostProcessing.apply(traditional, simplified, traditional, false, text))
    }

    @Test fun traditionalModeAppendsTrailingSpace() {
        val text = "简体中文"
        assertEquals(ChineseUtils.s2tw(text) + " ", TextPostProcessing.apply(traditional, simplified, traditional, true, text))
    }

    @Test fun emptyTextHasOptionalTrailingSpaceInNoConversionMode() {
        assertEquals("", TextPostProcessing.apply("No Conversion", simplified, traditional, false, ""))
        assertEquals(" ", TextPostProcessing.apply("No Conversion", simplified, traditional, true, ""))
    }

    @Test fun emptyTextHasOptionalTrailingSpaceInConversionModes() {
        assertEquals("", TextPostProcessing.apply(simplified, simplified, traditional, false, ""))
        assertEquals(" ", TextPostProcessing.apply(traditional, simplified, traditional, true, ""))
    }

    @Test fun multilineTextGetsOneSpaceAfterLastLine() {
        val text = "第一行\n第二行"
        val result = TextPostProcessing.apply("No Conversion", simplified, traditional, true, text)
        assertEquals("第一行\n第二行 ", result)
        assertEquals(1, result.count { it == ' ' })
    }
}
