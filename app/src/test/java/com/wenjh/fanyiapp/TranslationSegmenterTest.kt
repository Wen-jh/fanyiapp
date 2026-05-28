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
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500, minMeaningfulGrowthChars = 2)
        assertNull(segmenter.onPartial("ありがとう", 1000))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 1600))
    }

    @Test
    fun growingPartial_canFlushWithoutWaitingForExactRepeatFrame() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500, minMeaningfulGrowthChars = 2)

        assertNull(segmenter.onPartial("あり", 1000))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 1600))
    }

    @Test
    fun punctuatedPartial_flushesImmediatelyWithoutWaitingForRepeatFrame() {
        val segmenter = TranslationSegmenter(minPartialLength = 14, stableWindowMs = 1400, minMeaningfulGrowthChars = 5)
        assertEquals("こんにちは。", segmenter.onPartial("こんにちは。", 1000))
    }

    @Test
    fun shortPartialStillWaitsWhenNotStableAndNotPunctuated() {
        val segmenter = TranslationSegmenter(minPartialLength = 14, stableWindowMs = 1400, minMeaningfulGrowthChars = 5)
        assertNull(segmenter.onPartial("こんにちは", 1000))
    }

    @Test
    fun defaultSegmenter_waitsLongerBeforeFlushingRoughRealtimePartial() {
        val segmenter = TranslationSegmenter()
        assertNull(segmenter.onPartial("ありがとうございました", 1000))
        assertNull(segmenter.onPartial("ありがとうございました", 2200))
        assertEquals("ありがとうございました", segmenter.onPartial("ありがとうございました", 2500))
    }

    @Test
    fun repeatedSmallPrefixGrowth_doesNotFlushAgain() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500, minMeaningfulGrowthChars = 2)

        assertNull(segmenter.onPartial("ありがとう", 1000))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 1600))
        assertNull(segmenter.onPartial("ありがとうござ", 2300))
    }

    @Test
    fun meaningfulPrefixGrowth_stillFlushesAfterThreshold() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500, minMeaningfulGrowthChars = 2)

        assertNull(segmenter.onPartial("ありがとう", 1000))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 1600))
        assertNull(segmenter.onPartial("ありがとうございました", 2200))
        assertEquals("ありがとうございました", segmenter.onPartial("ありがとうございました", 2800))
    }

    @Test
    fun flushFinalImmediately() {
        val segmenter = TranslationSegmenter()
        assertEquals("おはようございます", segmenter.onFinal("おはようございます"))
    }

    @Test
    fun finalDuplicateAfterProvisionalFlush_isIgnored() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500, minMeaningfulGrowthChars = 2)

        assertNull(segmenter.onPartial("ありがとう", 1000))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 1600))
        assertNull(segmenter.onFinal("ありがとう"))
    }

    @Test
    fun repeatedUtteranceWithSameText_isAllowedAfterNewPrefixBuildsUpAgain() {
        val segmenter = TranslationSegmenter(minPartialLength = 3, stableWindowMs = 500, minMeaningfulGrowthChars = 2)

        assertEquals("ありがとう", segmenter.onFinal("ありがとう"))
        assertNull(segmenter.onPartial("あり", 2200))
        assertEquals("ありがとう", segmenter.onPartial("ありがとう", 2900))
    }
}
