package com.wenjh.fanyiapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFinalPolishGuardTest {
    @Test
    fun shouldApplyPolishedResult_acceptsMatchingCurrentFinal() {
        assertTrue(
            SubtitleOverlayService.shouldApplyPolishedResult(
                finalToken = 3L,
                sourceText = "ありがとうございます",
                currentSourceText = "ありがとうございます",
                expectedMlKitTranslation = "非常感谢",
                currentDisplayedTranslation = "非常感谢",
                latestFinalToken = 3L
            )
        )
    }

    @Test
    fun shouldApplyPolishedResult_rejectsStaleToken() {
        assertFalse(
            SubtitleOverlayService.shouldApplyPolishedResult(
                finalToken = 3L,
                sourceText = "ありがとうございます",
                currentSourceText = "ありがとうございます",
                expectedMlKitTranslation = "非常感谢",
                currentDisplayedTranslation = "非常感谢",
                latestFinalToken = 4L
            )
        )
    }

    @Test
    fun shouldApplyPolishedResult_rejectsWhenCurrentSourceHasChanged() {
        assertFalse(
            SubtitleOverlayService.shouldApplyPolishedResult(
                finalToken = 3L,
                sourceText = "ありがとうございます",
                currentSourceText = "次の文です",
                expectedMlKitTranslation = "非常感谢",
                currentDisplayedTranslation = "非常感谢",
                latestFinalToken = 3L
            )
        )
    }

    @Test
    fun shouldApplyPolishedResult_rejectsWhenDisplayedTextAlreadyMovedOn() {
        assertFalse(
            SubtitleOverlayService.shouldApplyPolishedResult(
                finalToken = 3L,
                sourceText = "ありがとうございます",
                currentSourceText = "ありがとうございます",
                expectedMlKitTranslation = "非常感谢",
                currentDisplayedTranslation = "这是新的译文",
                latestFinalToken = 3L
            )
        )
    }
}
