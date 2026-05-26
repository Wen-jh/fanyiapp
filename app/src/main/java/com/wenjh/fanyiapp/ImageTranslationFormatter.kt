package com.wenjh.fanyiapp

object ImageTranslationFormatter {
    fun normalizeRecognizedText(rawText: String): String {
        return rawText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun composeResult(
        recognizedText: String,
        translatedText: String,
        status: String
    ): String {
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
        return listOf(
            "英文：$safeRecognized",
            "中文：$safeTranslated",
            "状态：$safeStatus"
        ).joinToString("\n")
    }
}
