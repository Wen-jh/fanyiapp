package com.wenjh.fanyiapp

object HyMtResultParser {
    private val removablePrefixes = listOf("翻译：", "译文：", "中文：", "翻译如下：")
    private val translatedLinePrefixes = listOf("译文：", "翻译：", "中文：")

    fun clean(rawOutput: String): String {
        var result = rawOutput.trim()
        extractTranslatedSection(result)?.let { extracted ->
            result = extracted
        }
        removablePrefixes.forEach { prefix ->
            if (result.startsWith(prefix)) {
                result = result.removePrefix(prefix).trim()
            }
        }
        return result
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }

    private fun extractTranslatedSection(rawOutput: String): String? {
        val lines = rawOutput
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        lines.firstOrNull { line -> translatedLinePrefixes.any { prefix -> line.startsWith(prefix) } }?.let { line ->
            var extracted = line
            translatedLinePrefixes.forEach { prefix ->
                if (extracted.startsWith(prefix)) {
                    extracted = extracted.removePrefix(prefix).trim()
                }
            }
            return extracted
        }

        val sourceIndex = lines.indexOfFirst { it.startsWith("原文：") || it.startsWith("OCR 文本：") }
        val targetIndex = lines.indexOfFirst { it.startsWith("译文：") || it.startsWith("翻译：") || it.startsWith("中文：") }
        if (sourceIndex >= 0 && targetIndex >= 0 && targetIndex > sourceIndex) {
            return lines.subList(targetIndex, lines.size).joinToString("\n")
        }

        return null
    }
}
