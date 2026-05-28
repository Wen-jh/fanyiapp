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

        assertEquals(1, segments.size)
        assertEquals("Open settings", segments.single().text)
        assertEquals(HyMtTranslationEngine.SegmentBreakType.PARAGRAPH, segments.single().breakType)
    }

    @Test
    fun splitForTranslation_breaksLongParagraphsAtSentenceBoundaries() {
        val source = "Open settings and continue learning. Review the translation output carefully. Keep each segment natural and readable."

        val segments = HyMtTranslationEngine.splitForTranslation(source, maxCharsPerSegment = 55)

        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.text.length <= 55 })
        assertEquals(HyMtTranslationEngine.SegmentBreakType.PARAGRAPH, segments.first().breakType)
        assertTrue(segments.drop(1).all { it.breakType == HyMtTranslationEngine.SegmentBreakType.CONTINUATION })
        assertTrue(segments.first().text.endsWith("."))
    }

    @Test
    fun splitForTranslation_preservesParagraphBoundaries() {
        val source = "First paragraph explains the setup and expected result.\n\nSecond paragraph adds another note for translation."

        val segments = HyMtTranslationEngine.splitForTranslation(source, maxCharsPerSegment = 120)

        assertEquals(2, segments.size)
        assertEquals(HyMtTranslationEngine.SegmentBreakType.PARAGRAPH, segments[0].breakType)
        assertEquals(HyMtTranslationEngine.SegmentBreakType.PARAGRAPH, segments[1].breakType)
        assertTrue(segments[0].text.startsWith("First paragraph"))
        assertTrue(segments[1].text.startsWith("Second paragraph"))
    }

    @Test
    fun splitForTranslation_fallsBackToHardSplitWhenNoBoundaryExists() {
        val source = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"

        val segments = HyMtTranslationEngine.splitForTranslation(source, maxCharsPerSegment = 20)

        assertEquals(3, segments.size)
        assertTrue(segments.all { it.text.length <= 20 })
        assertEquals(HyMtTranslationEngine.SegmentBreakType.PARAGRAPH, segments.first().breakType)
        assertTrue(segments.drop(1).all { it.breakType == HyMtTranslationEngine.SegmentBreakType.CONTINUATION })
    }

    @Test
    fun mergeTranslatedSegments_keepsParagraphBreaksAndInlineContinuations() {
        val merged = HyMtTranslationEngine.mergeTranslatedSegments(
            listOf(
                HyMtTranslationEngine.TranslatedSegment(
                    text = "打开设置",
                    breakType = HyMtTranslationEngine.SegmentBreakType.PARAGRAPH
                ),
                HyMtTranslationEngine.TranslatedSegment(
                    text = "继续学习",
                    breakType = HyMtTranslationEngine.SegmentBreakType.CONTINUATION
                ),
                HyMtTranslationEngine.TranslatedSegment(
                    text = "下一段说明",
                    breakType = HyMtTranslationEngine.SegmentBreakType.PARAGRAPH
                )
            )
        )

        assertEquals("打开设置继续学习\n\n下一段说明", merged)
    }

    @Test
    fun mergeTranslatedSegments_insertsSpaceForLatinContinuation() {
        val merged = HyMtTranslationEngine.mergeTranslatedSegments(
            listOf(
                HyMtTranslationEngine.TranslatedSegment(
                    text = "Open settings",
                    breakType = HyMtTranslationEngine.SegmentBreakType.PARAGRAPH
                ),
                HyMtTranslationEngine.TranslatedSegment(
                    text = "and continue learning",
                    breakType = HyMtTranslationEngine.SegmentBreakType.CONTINUATION
                )
            )
        )

        assertEquals("Open settings and continue learning", merged)
    }
}
