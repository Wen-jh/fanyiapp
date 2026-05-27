package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTranslationFormatterLanguageLabelsTest {
    @Test
    fun buildUiContent_keepsExistingTranslationContentBehavior() {
        val result = ImageTranslationFormatter.buildUiContent(
            recognizedText = "設定を開く",
            translatedText = "打开设置",
            status = "Hy-MT 离线翻译完成（Japanese → Chinese）"
        )

        assertEquals("設定を開く", result.recognizedText)
        assertEquals("打开设置", result.translatedText)
        assertEquals("Hy-MT 离线翻译完成（Japanese → Chinese）", result.statusText)
    }
}
