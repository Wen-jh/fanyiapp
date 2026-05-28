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
        status: String,
        originalLabel: String = "日语",
        translatedLabel: String = "中文"
    ): String {
        val safeOriginal = original.ifBlank { "（未识别到${originalLabel}）" }
        val safeTranslated = translated.ifBlank { status.ifBlank { "翻译中…" } }
        val safeStatus = status.ifBlank { "等待中" }
        return listOf(
            "模式：$modeLabel",
            "${originalLabel}：$safeOriginal",
            "${translatedLabel}：$safeTranslated",
            "状态：$safeStatus"
        ).joinToString("\n")
    }

    fun composeOverlaySubtitle(
        original: String,
        translated: String,
        translationState: String,
        recognitionState: String,
        originalLabel: String = "识别",
        translatedLabel: String = "翻译"
    ): String {
        val safeOriginal = original.ifBlank { "（等待识别）" }
        val safeTranslated = translated.ifBlank {
            when {
                translationState.contains("先显示原文") -> safeOriginal
                translationState.contains("下载中") -> "（翻译模型下载中）"
                translationState.contains("正在准备") -> "（翻译模型准备中）"
                translationState.contains("等待更完整翻译") -> "（等待更完整翻译结果）"
                recognitionState.contains("实时") && original.isNotBlank() -> "（等待更稳定语句后翻译）"
                else -> "（等待翻译）"
            }
        }
        return listOf(
            "${originalLabel}：$safeOriginal",
            "${translatedLabel}：$safeTranslated"
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
        levelHint: String,
        originalLabel: String = "日语",
        translatedLabel: String = "中文"
    ): String {
        val safeOriginal = original.ifBlank { "（未识别到${originalLabel}）" }
        val safeTranslated = translated.ifBlank {
            when {
                translationState.contains("先显示原文") -> "（翻译尚未就绪，当前先显示原文）"
                translationState.contains("下载中") -> "（翻译模型下载中）"
                translationState.contains("正在准备") -> "（翻译模型准备中）"
                translationState.contains("等待更完整翻译") -> "（等待更完整翻译结果）"
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
            "${originalLabel}：$safeOriginal",
            "${translatedLabel}：$safeTranslated"
        ).joinToString("\n")
    }
}
