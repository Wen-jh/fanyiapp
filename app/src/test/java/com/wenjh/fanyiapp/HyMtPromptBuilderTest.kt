package com.wenjh.fanyiapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtPromptBuilderTest {
    @Test
    fun build_containsInstructionAndRecognizedText() {
        val prompt = HyMtPromptBuilder.build("Open settings\nNetwork error")

        assertTrue(prompt.contains("只输出中文译文"))
        assertTrue(prompt.contains("Open settings"))
        assertTrue(prompt.contains("Network error"))
    }

    @Test
    fun build_trimsAndRemovesBlankLines() {
        val prompt = HyMtPromptBuilder.build("  Hello  \n\n  World  ")

        assertTrue(prompt.contains("Hello\nWorld"))
        assertFalse(prompt.contains("\n\n\n"))
    }
}
