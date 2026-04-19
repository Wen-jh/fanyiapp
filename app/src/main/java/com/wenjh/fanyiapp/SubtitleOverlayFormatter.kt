package com.wenjh.fanyiapp

object SubtitleOverlayFormatter {
    fun composeStatus(modeLabel: String, status: String): String {
        return listOf(
            "模式：$modeLabel",
            "状态：${status.ifBlank { "等待中" }}"
        ).joinToString("\n")
    }

    fun composeTranslation(
        modeLabel: String,
        original: String,
        translated: String,
        status: String
    ): String {
        val safeOriginal = original.ifBlank { "（未识别到日语）" }
        val safeTranslated = translated.ifBlank { status.ifBlank { "翻译中…" } }
        val safeStatus = status.ifBlank { "等待中" }
        return listOf(
            "模式：$modeLabel",
            "日语：$safeOriginal",
            "中文：$safeTranslated",
            "状态：$safeStatus"
        ).joinToString("\n")
    }

    fun composePipeline(
        modeLabel: String,
        captureState: String,
        recognitionState: String,
        translationState: String,
        original: String,
        translated: String,
        levelHint: String
    ): String {
        val safeOriginal = original.ifBlank { "（未识别到日语）" }
        val safeTranslated = translated.ifBlank { "（暂无翻译结果）" }
        val safeLevelHint = levelHint.ifBlank { "音量: 未知" }
        return listOf(
            "模式：$modeLabel",
            "采集：${captureState.ifBlank { "等待中" }}",
            "识别：${recognitionState.ifBlank { "未开始" }}",
            "翻译：${translationState.ifBlank { "未开始" }}",
            safeLevelHint,
            "日语：$safeOriginal",
            "中文：$safeTranslated"
        ).joinToString("\n")
    }
}
