package com.wenjh.fanyiapp

object ImageTranslationFormatter {
    data class UiContent(
        val recognizedText: String,
        val translatedText: String,
        val statusText: String
    )

    fun normalizeRecognizedText(rawText: String): String {
        return rawText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun buildUiContent(
        recognizedText: String,
        translatedText: String,
        status: String
    ): UiContent {
        val safeRecognized = recognizedText.ifBlank { "（未识别到英文）" }
        val safeTranslated = translatedText.ifBlank {
            when {
                status.contains("下载中") -> "（翻译模型下载中）"
                status.contains("正在准备") || status.contains("模型准备") || status.contains("正在解包") || status.contains("Hy-MT 离线模型") -> "（翻译模型准备中）"
                status.contains("识别") -> "（等待识别英文）"
                else -> "（暂无翻译结果）"
            }
        }
        val safeStatus = status.ifBlank { "等待中" }
        return UiContent(
            recognizedText = safeRecognized,
            translatedText = safeTranslated,
            statusText = safeStatus
        )
    }

    fun composeResult(
        recognizedText: String,
        translatedText: String,
        status: String
    ): String {
        val uiContent = buildUiContent(
            recognizedText = recognizedText,
            translatedText = translatedText,
            status = status
        )
        return listOf(
            "英文：${uiContent.recognizedText}",
            "中文：${uiContent.translatedText}",
            "状态：${uiContent.statusText}"
        ).joinToString("\n")
    }
}
