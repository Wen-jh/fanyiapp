package com.wenjh.fanyiapp

object HyMtPromptBuilder {
    fun build(recognizedText: String, sourceLanguage: String, targetLanguage: String): String {
        val normalizedText = ImageTranslationFormatter.normalizeRecognizedText(recognizedText)
        return """
            请把下面的 OCR 文本从 ${sourceLanguage} 翻译成自然、简洁、准确的 ${targetLanguage}。
            只输出当前这一段对应的译文，不要解释，不要重复原文，不要补充额外说明。
            保持本段内部的句子顺序和段落结构。
            源语言：${sourceLanguage}
            目标语言：${targetLanguage}

            OCR 文本：
            $normalizedText
        """.trimIndent()
    }
}
