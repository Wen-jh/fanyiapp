package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTranslationFormatterHyMtTest {
    @Test
    fun composeResult_showsPreparingHintForHyMtStatuses() {
        val result = ImageTranslationFormatter.composeResult(
            recognizedText = "",
            translatedText = "",
            status = "正在解包 Hy-MT 离线模型（42%）"
        )

        assertEquals(
            "英文：（未识别到英文）\n中文：（翻译模型准备中）\n状态：正在解包 Hy-MT 离线模型（42%）",
            result
        )
    }

    @Test
    fun composeResult_showsPlaceholderDuringHyMtTranslation() {
        val result = ImageTranslationFormatter.composeResult(
            recognizedText = "Open settings",
            translatedText = "",
            status = "正在进行 Hy-MT 离线翻译"
        )

        assertEquals(
            "英文：Open settings\n中文：（暂无翻译结果）\n状态：正在进行 Hy-MT 离线翻译",
            result
        )
    }
}
