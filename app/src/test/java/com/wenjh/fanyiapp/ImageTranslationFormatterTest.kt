package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTranslationFormatterTest {
    @Test
    fun buildUiContent_usesGenericPlaceholdersForEmptyState() {
        val content = ImageTranslationFormatter.buildUiContent(
            recognizedText = "",
            translatedText = "",
            status = ""
        )

        assertEquals("（未识别到原文）", content.recognizedText)
        assertEquals("（暂无翻译结果）", content.translatedText)
        assertEquals("等待中", content.statusText)
    }

    @Test
    fun buildUiContent_showsRecognitionWaitingPlaceholderWithoutEnglishSpecificText() {
        val content = ImageTranslationFormatter.buildUiContent(
            recognizedText = "",
            translatedText = "",
            status = "正在识别图片中的文字"
        )

        assertEquals("（等待识别原文）", content.translatedText)
    }

    @Test
    fun composeResult_usesNeutralLabels() {
        val result = ImageTranslationFormatter.composeResult(
            recognizedText = "設定を開く",
            translatedText = "打开设置",
            status = "Hy-MT 离线翻译完成"
        )

        assertEquals(
            "原文：設定を開く\n译文：打开设置\n状态：Hy-MT 离线翻译完成",
            result
        )
    }
}
