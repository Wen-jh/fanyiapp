package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineEnglishChineseTranslatorTest {
    @Test
    fun translate_returnsBuiltinPhrase_forCommonUiSentence() {
        val result = OfflineEnglishChineseTranslator.translate("Sign in with Google")

        assertEquals("使用 Google 登录", result.text)
        assertTrue(result.usedBuiltinPhrase)
    }

    @Test
    fun translate_fallsBackToWordLevel_forSimpleStatusText() {
        val result = OfflineEnglishChineseTranslator.translate("Network error")

        assertEquals("网络 错误", result.text)
        assertTrue(result.usedWordFallback)
    }

    @Test
    fun translate_preservesNumbersAndKnownWords_forMixedSentence() {
        val result = OfflineEnglishChineseTranslator.translate("Episode 12 settings")

        assertEquals("第 12 集 设置", result.text)
    }

    @Test
    fun translate_returnsEmpty_forBlankInput() {
        val result = OfflineEnglishChineseTranslator.translate("   ")

        assertEquals("", result.text)
    }
}
