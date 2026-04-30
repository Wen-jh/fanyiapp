package com.wenjh.fanyiapp

object OverlayUiModeDecider {
    fun shouldAutoCollapse(translationState: String, translated: String): Boolean {
        val normalizedState = translationState.trim()
        val hasReadyState = normalizedState.contains("翻译模型就绪") || normalizedState.contains("翻译完成")
        val hasBlockingState = normalizedState.contains("下载中") ||
            normalizedState.contains("失败") ||
            normalizedState.contains("未就绪") ||
            normalizedState.contains("准备")
        return hasReadyState && !hasBlockingState && translated.isNotBlank()
    }

    fun detailsToggleLabel(showDetails: Boolean): String {
        return if (showDetails) "隐藏详情" else "显示详情"
    }
}
