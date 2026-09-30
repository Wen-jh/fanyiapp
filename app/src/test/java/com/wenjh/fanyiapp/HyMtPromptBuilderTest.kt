package com.wenjh.fanyiapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtPromptBuilderTest {
    @Test
    fun build_usesOfficialHyMtTemplateWithTargetLanguage() {
        val prompt = HyMtPromptBuilder.build(
            recognizedText = "Open settings",
            sourceLanguage = "English",
            targetLanguage = "Japanese"
        )

        assertTrue(prompt.startsWith("Translate the following segment from English into Japanese"))
        assertTrue(prompt.contains("without additional explanation."))
        assertTrue(prompt.contains("Open settings"))
    }

    @Test
    fun build_omitsFromClauseWhenSourceLanguageIsUnknown() {
        val prompt = HyMtPromptBuilder.build(
            recognizedText = "你好",
            sourceLanguage = null,
            targetLanguage = "Japanese"
        )

        assertTrue(prompt.startsWith("Translate the following segment into Japanese"))
        assertFalse(prompt.contains("from "))
    }

    @Test
    fun build_keepsSourceTextVerbatim() {
        val prompt = HyMtPromptBuilder.build(
            recognizedText = "  Hello  \n\n  World  ",
            sourceLanguage = "English",
            targetLanguage = "Chinese"
        )

        assertTrue(prompt.contains("Hello  \n\n  World"))
        assertFalse(prompt.trim().endsWith("World  "))
    }

    @Test
    fun buildStrict_namesTargetLanguageAndForbidsKeepingSourceLanguage() {
        val prompt = HyMtPromptBuilder.buildStrict(
            recognizedText = "你好",
            targetLanguage = "Japanese",
            targetLocalName = "日语"
        )

        assertTrue(prompt.contains("翻译成日语（Japanese）"))
        assertTrue(prompt.contains("不要保留原文语言"))
        assertTrue(prompt.contains("你好"))
    }

    @Test
    fun buildStrict_skipsRedundantEnglishNameWhenLocalNameIsSame() {
        val prompt = HyMtPromptBuilder.buildStrict(
            recognizedText = "Hello",
            targetLanguage = "English",
            targetLocalName = "English"
        )

        assertTrue(prompt.contains("翻译成English。"))
        assertFalse(prompt.contains("（English）"))
    }
}
