package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleOverlayFormatterTest {
    @Test
    fun composeStatus_showsModeAndStatusWithoutEmptyLines() {
        val result = SubtitleOverlayFormatter.composeStatus(
            modeLabel = "麦克风模式",
            status = "正在准备翻译模型…"
        )

        assertEquals("模式：麦克风模式\n状态：正在准备翻译模型…", result)
    }

    @Test
    fun composeTranslation_showsOriginalAndTranslatedText() {
        val result = SubtitleOverlayFormatter.composeTranslation(
            modeLabel = "麦克风模式",
            original = "こんにちは",
            translated = "你好",
            status = "识别中"
        )

        assertEquals(
            "模式：麦克风模式\n日语：こんにちは\n中文：你好\n状态：识别中",
            result
        )
    }

    @Test
    fun composeTranslation_fallsBackWhenTranslationMissing() {
        val result = SubtitleOverlayFormatter.composeTranslation(
            modeLabel = "麦克风模式",
            original = "おはよう",
            translated = "",
            status = "翻译中…"
        )

        assertEquals(
            "模式：麦克风模式\n日语：おはよう\n中文：翻译中…\n状态：翻译中…",
            result
        )
    }

    @Test
    fun composePipeline_showsFullVisualizedProgress() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "麦克风模式",
            captureState = "已接收到音频",
            recognitionState = "正在识别",
            translationState = "正在翻译",
            original = "お願いします",
            translated = "拜托了",
            levelHint = "音量: 27%"
        )

        assertEquals(
            "模式：麦克风模式\n采集：已接收到音频\n识别：正在识别\n翻译：正在翻译\n音量: 27%\n日语：お願いします\n中文：拜托了",
            result
        )
    }

    @Test
    fun composePipeline_showsPreparingHintWhenTranslatorStillLoading() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "等待音频输入",
            recognitionState = "已就绪，等待日语语音",
            translationState = "正在准备日语→中文翻译模型（后台）",
            original = "",
            translated = "",
            levelHint = ""
        )

        assertEquals(
            "模式：播放捕获模式\n采集：等待音频输入\n识别：已就绪，等待日语语音\n翻译：正在准备日语→中文翻译模型（后台）\n音量: 未知\n日语：（未识别到日语）\n中文：（翻译模型准备中）",
            result
        )
    }

    @Test
    fun composePipeline_showsRawTextHintWhenTranslatorNotReady() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "已接收到音频",
            recognitionState = "已识别到日语文本",
            translationState = "翻译器未就绪，先显示原文",
            original = "テストです",
            translated = "",
            levelHint = "音量: 11%"
        )

        assertEquals(
            "模式：播放捕获模式\n采集：已接收到音频\n识别：已识别到日语文本\n翻译：翻译器未就绪，先显示原文\n音量: 11%\n日语：テストです\n中文：（翻译尚未就绪，当前先显示原文）",
            result
        )
    }

    @Test
    fun composePipeline_usesFallbacksWhenDataMissing() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "等待音频输入",
            recognitionState = "未开始",
            translationState = "未开始",
            original = "",
            translated = "",
            levelHint = ""
        )

        assertEquals(
            "模式：播放捕获模式\n采集：等待音频输入\n识别：未开始\n翻译：未开始\n音量: 未知\n日语：（未识别到日语）\n中文：（暂无翻译结果）",
            result
        )
    }
}
