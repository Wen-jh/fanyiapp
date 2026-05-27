package com.wenjh.fanyiapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtPromptBuilderTest {
    @Test
    fun build_containsInstructionAndRecognizedText() {
        val prompt = HyMtPromptBuilder.build(
            recognizedText = "Open settings\nNetwork error",
            sourceLanguage = "English",
            targetLanguage = "Chinese"
        )

        assertTrue(prompt.contains("只输出译文"))
        assertTrue(prompt.contains("Open settings"))
        assertTrue(prompt.contains("Network error"))
    }

    @Test
    fun build_trimsAndRemovesBlankLines() {
        val prompt = HyMtPromptBuilder.build(
            recognizedText = "  Hello  \n\n  World  ",
            sourceLanguage = "English",
            targetLanguage = "Chinese"
        )

        assertTrue(prompt.contains("Hello\nWorld"))
        assertFalse(prompt.contains("\n\n\n"))
    }

    @Test
    fun build_containsSelectedSourceAndTargetLanguages() {
        val prompt = HyMtPromptBuilder.build(
            recognizedText = "設定を開く",
            sourceLanguage = "Japanese",
            targetLanguage = "Chinese"
        )

        assertTrue(prompt.contains("源语言：Japanese"))
        assertTrue(prompt.contains("目标语言：Chinese"))
        assertTrue(prompt.contains("設定を開く"))
    }

    @Test
    fun build_usesRequestedTranslationDirectionInInstructionLine() {
        val prompt = HyMtPromptBuilder.build(
            recognizedText = "こんにちは",
            sourceLanguage = "Japanese",
            targetLanguage = "English"
        )

        assertTrue(prompt.contains("请把下面的 OCR 文本从 Japanese 翻译成自然、简洁、准确的 English。"))
    }
}
