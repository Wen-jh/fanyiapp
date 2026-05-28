package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtTranslationEngineTest {
    @Test
    fun finalizeTranslationResult_keepsHyMtChineseOutput() {
        val result = HyMtTranslationEngine.finalizeTranslationResult(
            recognizedText = "Open settings",
            cleanedOutput = "打开设置",
            rawOutput = "译文：打开设置",
            sourceLanguage = "English",
            targetLanguage = "Chinese"
        )

        assertEquals("打开设置", result.text)
        assertEquals("hy-mt-native", result.backend)
    }

    @Test
    fun finalizeTranslationResult_usesFallbackWhenOutputEchoesEnglishSource() {
        val result = HyMtTranslationEngine.finalizeTranslationResult(
            recognizedText = "Open settings",
            cleanedOutput = "Open settings",
            rawOutput = "Open settings",
            sourceLanguage = "English",
            targetLanguage = "Chinese"
        )

        assertEquals("打开设置", result.text)
        assertEquals("builtin-fallback", result.backend)
    }

    @Test
    fun finalizeTranslationResult_usesFallbackWhenOutputIsBlank() {
        val result = HyMtTranslationEngine.finalizeTranslationResult(
            recognizedText = "Network error",
            cleanedOutput = "",
            rawOutput = "",
            sourceLanguage = "English",
            targetLanguage = "Chinese"
        )

        assertEquals("网络 错误", result.text)
        assertEquals("builtin-fallback", result.backend)
    }

    @Test
    fun shouldUseEnglishChineseFallback_returnsFalseForNonEnglishChineseDirection() {
        assertFalse(
            HyMtTranslationEngine.shouldUseEnglishChineseFallback(
                recognizedText = "Open settings",
                translatedText = "Open settings",
                sourceLanguage = "Japanese",
                targetLanguage = "Chinese"
            )
        )
    }

    @Test
    fun shouldUseEnglishChineseFallback_detectsMostlyLatinOutputWithoutChinese() {
        assertTrue(
            HyMtTranslationEngine.shouldUseEnglishChineseFallback(
                recognizedText = "Network error",
                translatedText = "Network error please retry",
                sourceLanguage = "English",
                targetLanguage = "Chinese"
            )
        )
    }

    @Test
    fun splitForTranslation_keepsShortTextAsSingleSegment() {
        val segments = HyMtTranslationEngine.splitForTranslation("Open settings")

        assertEquals(listOf("Open settings"), segments)
    }

    @Test
    fun splitForTranslation_breaksLongParagraphsIntoOrderedSegments() {
        val source = buildString {
            repeat(80) { append("Open settings and continue learning. ") }
        }

        val segments = HyMtTranslationEngine.splitForTranslation(source, maxCharsPerSegment = 80)

        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.length <= 80 })
        assertTrue(segments.joinToString(" ").contains("Open settings"))
    }
}
