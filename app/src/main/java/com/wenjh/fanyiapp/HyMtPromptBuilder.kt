package com.wenjh.fanyiapp

object HyMtPromptBuilder {
    fun build(recognizedText: String): String {
        val normalizedText = ImageTranslationFormatter.normalizeRecognizedText(recognizedText)
        return """
            请把下面的英文 OCR 文本翻译成自然、简洁、准确的简体中文。
            只输出中文译文，不要解释，不要重复原文。

            英文 OCR 文本：
            $normalizedText
        """.trimIndent()
    }
}
