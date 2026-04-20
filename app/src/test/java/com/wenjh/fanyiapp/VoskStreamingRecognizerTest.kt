package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoskStreamingRecognizerTest {
    @Test
    fun parsePartialResultJson_returnsPartialText() {
        assertEquals("こんにちは", VoskStreamingRecognizer.parsePartial("{\"partial\":\"こんにちは\"}"))
    }

    @Test
    fun parseFinalResultJson_returnsFinalText() {
        assertEquals("ありがとうございます", VoskStreamingRecognizer.parseFinal("{\"text\":\"ありがとうございます\"}"))
    }

    @Test
    fun ignoreDuplicateFinals() {
        assertNull(
            VoskStreamingRecognizer.nextFinalEvent(
                json = "{\"text\":\"ありがとうございます\"}",
                lastFinal = "ありがとうございます"
            )
        )
    }

    @Test
    fun avoidEmptyPartialChurn() {
        assertNull(
            VoskStreamingRecognizer.nextPartialEvent(
                json = "{\"partial\":\"   \"}",
                lastPartial = "",
                lastFinal = ""
            )
        )
    }

    @Test
    fun parseHelpers_ignoreMissingFields() {
        assertEquals("", VoskStreamingRecognizer.parsePartial("{}"))
        assertEquals("", VoskStreamingRecognizer.parseFinal("{}"))
    }
}
