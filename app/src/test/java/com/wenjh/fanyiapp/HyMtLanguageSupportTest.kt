package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
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
}
