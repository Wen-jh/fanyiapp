package com.wenjh.fanyiapp

object HyMtResultParser {
    private val removablePrefixes = listOf("翻译：", "译文：", "中文：", "翻译如下：")

    fun clean(rawOutput: String): String {
        var result = rawOutput.trim()
        removablePrefixes.forEach { prefix ->
            if (result.startsWith(prefix)) {
                result = result.removePrefix(prefix).trim()
            }
        }
        return result
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }
}
