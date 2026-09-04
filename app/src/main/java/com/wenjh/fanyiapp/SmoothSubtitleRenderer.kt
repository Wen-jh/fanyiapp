package com.wenjh.fanyiapp

class SmoothSubtitleRenderer {
    private var displayedOriginal: String = ""
    private var displayedTranslation: String = ""
    private var committedOriginal: String = ""
    private var committedTranslation: String = ""

    fun onPartialUpdate(source: String, translated: String) {
        val committedLen = IncrementalTranslationPipeline.findCommonPrefix(committedOriginal, source)
        committedOriginal = source.substring(0, committedLen)
        committedTranslation = safeSubstring(translated, 0, mapSourceIndexToTarget(committedLen, source, translated))

        displayedOriginal = source
        displayedTranslation = translated
    }

    fun onFinalUpdate(source: String, translated: String) {
        committedOriginal = source
        committedTranslation = translated
        displayedOriginal = source
        displayedTranslation = translated
    }

    fun getDisplayText(): Pair<String, String> {
        return displayedOriginal to displayedTranslation
    }

    fun reset() {
        displayedOriginal = ""
        displayedTranslation = ""
        committedOriginal = ""
        committedTranslation = ""
    }

    private fun mapSourceIndexToTarget(sourceIndex: Int, source: String, target: String): Int {
        if (source.isEmpty()) return 0
        val ratio = target.length.toFloat() / source.length.toFloat()
        return (sourceIndex * ratio).toInt().coerceIn(0, target.length)
    }

    private fun safeSubstring(text: String, start: Int, end: Int): String {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        return text.substring(safeStart, safeEnd)
    }
}
