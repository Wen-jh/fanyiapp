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
        modelState: String,
        recognitionState: String,
        translationState: String,
        dumpState: String,
        original: String,
        translated: String,
        levelHint: String
    ): String {
        val safeOriginal = original.ifBlank { "（未识别到日语）" }
        val safeTranslated = translated.ifBlank {
            when {
                translationState.contains("先显示原文") -> "（翻译尚未就绪，当前先显示原文）"
                translationState.contains("正在准备") -> "（翻译模型准备中）"
                recognitionState.contains("实时") && original.isNotBlank() -> "（等待更稳定语句后翻译）"
                else -> "（暂无翻译结果）"
            }
        }
        val safeLevelHint = levelHint.ifBlank { "音量: 未知" }
        return listOf(
            "模式：$modeLabel",
            "采集：${captureState.ifBlank { "等待中" }}",
            "模型：${modelState.ifBlank { "未开始" }}",
            "识别：${recognitionState.ifBlank { "未开始" }}",
            "翻译：${translationState.ifBlank { "未开始" }}",
            "调试：${dumpState.ifBlank { "未启用" }}",
            safeLevelHint,
            "日语：$safeOriginal",
            "中文：$safeTranslated"
        ).joinToString("\n")
    }
}
