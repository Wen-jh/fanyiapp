package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test

class HyMtResultParserTest {
    @Test
    fun clean_removesKnownPrefixesAndTrimsWhitespace() {
        val cleaned = HyMtResultParser.clean("  译文：打开设置  ")

        assertEquals("打开设置", cleaned)
    }

    @Test
    fun clean_collapsesRepeatedBlankLines() {
        val cleaned = HyMtResultParser.clean("翻译如下：\n\n网络错误\n\n\n请重试")

        assertEquals("网络错误\n请重试", cleaned)
    }

    @Test
    fun clean_extractsTranslatedLineFromMixedOutput() {
        val cleaned = HyMtResultParser.clean("原文：Open settings\n译文：打开设置\n说明：仅输出译文")

        assertEquals("打开设置", cleaned)
    }

    @Test
    fun clean_extractsFirstTranslatedPrefixLine() {
        val cleaned = HyMtResultParser.clean("翻译结果如下：\n中文：网络错误")

        assertEquals("网络错误", cleaned)
    }
}
