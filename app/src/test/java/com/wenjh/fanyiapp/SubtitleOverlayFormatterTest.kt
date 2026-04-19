package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleOverlayFormatterTest {
    @Test
    fun composeStatus_showsModeAndStatusWithoutEmptyLines() {
        val result = SubtitleOverlayFormatter.composeStatus(
            modeLabel = "麦克风模式",
            status = "正在准备翻译模型…"
        )

        assertEquals("模式：麦克风模式\n状态：正在准备翻译模型…", result)
    }

    @Test
    fun composeTranslation_showsOriginalAndTranslatedText() {
        val result = SubtitleOverlayFormatter.composeTranslation(
            modeLabel = "麦克风模式",
            original = "こんにちは",
            translated = "你好",
            status = "识别中"
        )

        assertEquals(
            "模式：麦克风模式\n日语：こんにちは\n中文：你好\n状态：识别中",
            result
        )
    }

    @Test
    fun composeTranslation_fallsBackWhenTranslationMissing() {
        val result = SubtitleOverlayFormatter.composeTranslation(
            modeLabel = "麦克风模式",
            original = "おはよう",
            translated = "",
            status = "翻译中…"
        )

        assertEquals(
            "模式：麦克风模式\n日语：おはよう\n中文：翻译中…\n状态：翻译中…",
            result
        )
    }
}
