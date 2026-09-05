package com.wenjh.fanyiapp

/**
 * 平滑字幕渲染器 — 只保留当前正在说话的一句话，新句子开始时自动清空旧内容
 */
class SmoothSubtitleRenderer {
    private var displayedOriginal: String = ""
    private var displayedTranslation: String = ""
    private var lastFinalSource: String = ""

    fun onPartialUpdate(source: String, translated: String) {
        // 如果新 partial 与已显示的 final 完全不同（新句子开始），清空旧内容
        if (lastFinalSource.isNotBlank() && !source.startsWith(lastFinalSource) && !lastFinalSource.startsWith(source)) {
            // 新句子开始，直接替换
            displayedOriginal = source
            displayedTranslation = translated
            return
        }

        displayedOriginal = source
        displayedTranslation = translated
    }

    fun onFinalUpdate(source: String, translated: String) {
        // final 结果到达，标记为已确认
        lastFinalSource = source
        displayedOriginal = source
        displayedTranslation = translated
    }

    fun getDisplayText(): Pair<String, String> {
        return displayedOriginal to displayedTranslation
    }

    fun reset() {
        displayedOriginal = ""
        displayedTranslation = ""
        lastFinalSource = ""
    }
}
