package com.wenjh.fanyiapp

object HyMtPromptBuilder {
    /**
     * Hy-MT（混元翻译模型）官方推荐的单段翻译模板：英文指令 + 空行 + 原文。
     *
     * 之前用的是「请把下面这段文本从 X 翻译成 Y…源语言：X 目标语言：Y…文本：…」这种
     * 长中文模板，配合中文系统提示词，1.25bit 量化的小模型几乎必吐中文。
     * 换成官方模板后目标语言指令更直接，实测能正常输出日语/英语等目标语言。
     *
     * @param sourceLanguage 为 null 表示源语言未知（自动检测失败，或用户声明与原文文字系统
     *                       冲突）：此时不写 from 子句，交给模型自己判断，避免约束错了方向。
     */
    fun build(recognizedText: String, sourceLanguage: String?, targetLanguage: String): String {
        val text = recognizedText.trim()
        val target = targetLanguage.trim().ifBlank { "Chinese" }
        val source = sourceLanguage?.trim().orEmpty()
        return buildString {
            append("Translate the following segment ")
            if (source.isNotEmpty()) {
                append("from ").append(source).append(' ')
            }
            append("into ").append(target).append(", without additional explanation.")
            append("\n\n")
            append(text)
        }
    }

    /**
     * 输出语言校验没通过时的强指令模板。
     * 点名目标语言 + 明确禁止沿用原文语言，专门用来治「问日语答中文」。
     */
    fun buildStrict(recognizedText: String, targetLanguage: String, targetLocalName: String): String {
        val text = recognizedText.trim()
        val english = targetLanguage.trim().ifBlank { "Chinese" }
        val local = targetLocalName.trim().ifBlank { english }
        return buildString {
            append("把下面的文本翻译成").append(local)
            if (!local.equals(english, ignoreCase = true)) {
                append("（").append(english).append("）")
            }
            append("。\n")
            append("只输出").append(local).append("译文，不要解释，不要输出拼音，不要保留原文语言。\n\n")
            append(text)
        }
    }
}
