package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationSegmenterTest {
    @Test
    fun ignoreDuplicateFinalText() {
        val segmenter = TranslationSegmenter()
        assertEquals("こんにちは世界", segmenter.onFinal("こんにちは世界"))
        assertNull(segmenter.onFinal("こんにちは世界"))
    }

    @Test
    fun translateStablePartialAfterThreshold() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500)
        assertNull(segmenter.onPartial("ありがとう", 1000))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 1600))
    }

    @Test
    fun growingPartial_canFlushWithoutWaitingForExactRepeatFrame() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500)

        assertNull(segmenter.onPartial("ありが", 1000))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 1600))
    }

    @Test
    fun punctuatedPartial_flushesImmediatelyWithoutWaitingForRepeatFrame() {
        val segmenter = TranslationSegmenter(minPartialLength = 6, stableWindowMs = 650)
        assertEquals("こんにちは。", segmenter.onPartial("こんにちは。", 1000))
    }

    @Test
    fun shortPartialStillWaitsWhenNotStableAndNotPunctuated() {
        val segmenter = TranslationSegmenter(minPartialLength = 6, stableWindowMs = 650)
        assertNull(segmenter.onPartial("こんにち", 1000))
    }

    @Test
    fun flushFinalImmediately() {
        val segmenter = TranslationSegmenter()
        assertEquals("おはようございます", segmenter.onFinal("おはようございます"))
    }

    @Test
    fun finalDuplicateAfterProvisionalFlush_isIgnored() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500)

        assertNull(segmenter.onPartial("ありがとう", 1000))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 1600))
        assertNull(segmenter.onFinal("ありがとう"))
    }

    @Test
    fun repeatedUtteranceWithSameText_isAllowedAfterNewPrefixBuildsUpAgain() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500)

        assertEquals("ありがとう", segmenter.onFinal("ありがとう"))
        assertNull(segmenter.onPartial("ありが", 2200))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 2900))
    }
}
