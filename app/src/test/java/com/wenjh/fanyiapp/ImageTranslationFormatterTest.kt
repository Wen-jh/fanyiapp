package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTranslationFormatterTest {
    @Test
    fun normalizeRecognizedText_collapsesBlankLinesAndTrimsWhitespace() {
        val result = ImageTranslationFormatter.normalizeRecognizedText(
            "  Hello world  \n\n This is a test. \n   \n OCR line 3  "
        )

        assertEquals("Hello world\nThis is a test.\nOCR line 3", result)
    }

    @Test
    fun composeResult_showsRecognizedEnglishAndTranslatedChinese() {
        val result = ImageTranslationFormatter.composeResult(
            recognizedText = "Open settings",
            translatedText = "打开设置",
            status = "翻译完成"
        )

        assertEquals(
            "英文：Open settings\n中文：打开设置\n状态：翻译完成",
            result
        )
    }

    @Test
    fun composeResult_showsPreparingHintWhenTranslatorStillLoading() {
        val result = ImageTranslationFormatter.composeResult(
            recognizedText = "",
            translatedText = "",
            status = "正在准备英语→中文翻译模型"
        )

        assertEquals(
            "英文：（未识别到英文）\n中文：（翻译模型准备中）\n状态：正在准备英语→中文翻译模型",
            result
        )
    }

    @Test
    fun composeResult_showsDownloadHintWhenTranslatorModelIsDownloading() {
        val result = ImageTranslationFormatter.composeResult(
            recognizedText = "",
            translatedText = "",
            status = "下载中（已耗时 8s，进度：ML Kit 未提供百分比）"
        )

        assertEquals(
            "英文：（未识别到英文）\n中文：（翻译模型下载中）\n状态：下载中（已耗时 8s，进度：ML Kit 未提供百分比）",
            result
        )
    }

    @Test
    fun composeResult_showsRecognitionHintWhileWaitingForOcrOutput() {
        val result = ImageTranslationFormatter.composeResult(
            recognizedText = "",
            translatedText = "",
            status = "正在识别图片中的英文"
        )

        assertEquals(
            "英文：（未识别到英文）\n中文：（等待识别英文）\n状态：正在识别图片中的英文",
            result
        )
    }
}
