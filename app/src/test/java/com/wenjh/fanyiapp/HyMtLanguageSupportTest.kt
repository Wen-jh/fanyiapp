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
}
