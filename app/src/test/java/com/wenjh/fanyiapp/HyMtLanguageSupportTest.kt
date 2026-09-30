package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtLanguageSupportTest {
    @Test
    fun supportedLanguages_containsExpectedDefaults() {
        assertEquals("English", HyMtLanguageSupport.defaultSource.promptName)
        assertEquals("Chinese", HyMtLanguageSupport.defaultTarget.promptName)
    }

    @Test
    fun supportedLanguages_containsJapaneseAndTraditionalChinese() {
        assertTrue(HyMtLanguageSupport.supportedLanguages.any { it.promptName == "Japanese" })
        assertTrue(HyMtLanguageSupport.supportedLanguages.any { it.promptName == "Traditional Chinese" })
    }

    @Test
    fun supportedLanguages_mapsKeyLanguagesToOcrScripts() {
        assertEquals(HyMtLanguageSupport.OcrScript.LATIN, HyMtLanguageSupport.findByCode("en")?.ocrScript)
        assertEquals(HyMtLanguageSupport.OcrScript.CHINESE, HyMtLanguageSupport.findByCode("zh")?.ocrScript)
        assertEquals(HyMtLanguageSupport.OcrScript.CHINESE, HyMtLanguageSupport.findByCode("zh-Hant")?.ocrScript)
        assertEquals(HyMtLanguageSupport.OcrScript.CHINESE, HyMtLanguageSupport.findByCode("yue")?.ocrScript)
        assertEquals(HyMtLanguageSupport.OcrScript.JAPANESE, HyMtLanguageSupport.findByCode("ja")?.ocrScript)
        assertEquals(HyMtLanguageSupport.OcrScript.KOREAN, HyMtLanguageSupport.findByCode("ko")?.ocrScript)
        assertEquals(HyMtLanguageSupport.OcrScript.DEVANAGARI, HyMtLanguageSupport.findByCode("hi")?.ocrScript)
    }

    @Test
    fun supportedLanguages_defaultsUnknownLanguagesToLatinOcr() {
        assertEquals(HyMtLanguageSupport.OcrScript.LATIN, HyMtLanguageSupport.findByCode("fr")?.ocrScript)
        assertEquals(HyMtLanguageSupport.OcrScript.LATIN, HyMtLanguageSupport.findByCode("ru")?.ocrScript)
        assertEquals(null, HyMtLanguageSupport.findByCode("unknown"))
    }

    @Test
    fun textScript_isIndependentFromOcrScript() {
        // 俄语 OCR 仍用拉丁识别器（ML Kit 没有西里尔识别器），但语言判定是西里尔文字系统
        assertEquals(HyMtLanguageSupport.OcrScript.LATIN, HyMtLanguageSupport.findByCode("ru")?.ocrScript)
        assertEquals(HyMtLanguageSupport.TextScript.CYRILLIC, HyMtLanguageSupport.findByCode("ru")?.textScript)
        assertEquals(HyMtLanguageSupport.TextScript.ARABIC, HyMtLanguageSupport.findByCode("ar")?.textScript)
        assertEquals(HyMtLanguageSupport.TextScript.ARABIC, HyMtLanguageSupport.findByCode("fa")?.textScript)
        assertEquals(HyMtLanguageSupport.TextScript.JAPANESE, HyMtLanguageSupport.findByCode("ja")?.textScript)
        assertEquals(HyMtLanguageSupport.TextScript.CHINESE, HyMtLanguageSupport.findByCode("zh")?.textScript)
        assertEquals(HyMtLanguageSupport.TextScript.LATIN, HyMtLanguageSupport.findByCode("fr")?.textScript)
    }

    @Test
    fun findByPromptNameAndUiLabel_resolveSameLanguage() {
        assertEquals("ja", HyMtLanguageSupport.findByPromptName("Japanese")?.code)
        assertEquals("ja", HyMtLanguageSupport.findByUiLabel("日语")?.code)
        assertEquals("auto", HyMtLanguageSupport.findByPromptName("Auto")?.code)
        assertEquals(null, HyMtLanguageSupport.findByPromptName("Klingon"))
    }

    @Test
    fun sourceLanguages_containsAutoDetectFirst() {
        assertEquals(HyMtLanguageSupport.autoDetect.code, HyMtLanguageSupport.sourceLanguages.first().code)
        assertTrue(HyMtLanguageSupport.targetLanguages.none { it.code == HyMtLanguageSupport.autoDetect.code })
    }

    @Test
    fun detectLanguageByScript_recognisesMajorScripts() {
        assertEquals("ja", HyMtLanguageSupport.detectLanguageByScript("こんにちは")?.code)
        assertEquals("ko", HyMtLanguageSupport.detectLanguageByScript("안녕하세요")?.code)
        assertEquals("zh", HyMtLanguageSupport.detectLanguageByScript("你好世界")?.code)
        assertEquals("ru", HyMtLanguageSupport.detectLanguageByScript("Привет мир")?.code)
        assertEquals("ar", HyMtLanguageSupport.detectLanguageByScript("مرحبا")?.code)
        assertEquals("hi", HyMtLanguageSupport.detectLanguageByScript("नमस्ते")?.code)
    }

    @Test
    fun detectLanguageByScript_returnsNullForLatinAndBlankInput() {
        assertEquals(null, HyMtLanguageSupport.detectLanguageByScript("Hello world"))
        assertEquals(null, HyMtLanguageSupport.detectLanguageByScript("12345 !!"))
        assertEquals(null, HyMtLanguageSupport.detectLanguageByScript(""))
    }

    @Test
    fun outputMatchesTarget_rejectsChineseWhenTargetIsNotChinese() {
        assertFalse(HyMtLanguageSupport.outputMatchesTarget("你好世界", "ja", "你好世界"))
        assertFalse(HyMtLanguageSupport.outputMatchesTarget("你好世界", "en", "Hello world"))
        assertFalse(HyMtLanguageSupport.outputMatchesTarget("你好", "ru", "你好"))
    }

    @Test
    fun outputMatchesTarget_acceptsOutputInTargetLanguage() {
        assertTrue(HyMtLanguageSupport.outputMatchesTarget("こんにちは", "ja", "你好"))
        assertTrue(HyMtLanguageSupport.outputMatchesTarget("Hello world", "en", "你好"))
        assertTrue(HyMtLanguageSupport.outputMatchesTarget("你好世界", "zh", "Hello world"))
        assertTrue(HyMtLanguageSupport.outputMatchesTarget("電話", "ja", "电话"))
        assertTrue(HyMtLanguageSupport.outputMatchesTarget("Привет", "ru", "Hello"))
        assertTrue(HyMtLanguageSupport.outputMatchesTarget("2026", "ja", "2026"))
    }
}
