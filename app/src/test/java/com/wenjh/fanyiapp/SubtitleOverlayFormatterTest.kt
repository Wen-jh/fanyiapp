package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            modelState = "本地识别模型就绪",
            recognitionState = "正在识别",
            translationState = "正在翻译",
            dumpState = "未启用",
            original = "お願いします",
            translated = "拜托了",
            levelHint = "音量: 27%"
        )

        assertEquals(
            "模式：麦克风模式\n采集：已接收到音频\n模型：本地识别模型就绪\n识别：正在识别\n翻译：正在翻译\n调试：未启用\n音量: 27%\n日语：お願いします\n中文：拜托了",
            result
        )
    }

    @Test
    fun composePipeline_showsCompactTranslationOnlyWhenDetailsHidden() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "播放音频捕获中",
            modelState = "本地识别模型就绪",
            recognitionState = "本地识别中（实时）",
            translationState = "翻译模型就绪（允许流量下载）",
            dumpState = "调试录音已保存",
            original = "こんにちは",
            translated = "你好",
            levelHint = "音量: 24%",
            showDetails = false
        )

        assertEquals(
            "日语：こんにちは\n中文：你好",
            result
        )
        assertFalse(result.contains("采集："))
        assertFalse(result.contains("翻译："))
        assertFalse(result.contains("音量:"))
        assertFalse(result.contains("按钮："))
    }

    @Test
    fun composePipeline_keepsFallbackTranslationInCompactMode() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "等待音频输入",
            modelState = "本地识别模型就绪",
            recognitionState = "等待本地识别启动",
            translationState = "下载中（已耗时 12s，等待 Wi‑Fi；进度：ML Kit 未提供百分比）",
            dumpState = "未启用",
            original = "",
            translated = "",
            levelHint = "",
            showDetails = false
        )

        assertEquals(
            "日语：（未识别到日语）\n中文：（翻译模型下载中）",
            result
        )
    }

    @Test
    fun composePipeline_showsQueuedFollowUpHintWithoutStaleTranslation() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "播放音频捕获中",
            modelState = "本地识别模型就绪",
            recognitionState = "本地识别中（实时）",
            translationState = "已收到更完整语句，等待更完整翻译",
            dumpState = "调试录音已保存",
            original = "ありがとう",
            translated = "",
            levelHint = "音量: 20%",
            showDetails = false
        )

        assertEquals(
            "日语：ありがとう\n中文：（等待更完整翻译结果）",
            result
        )
    }

    @Test
    fun composePipeline_showsPreparingHintWhenTranslatorStillLoading() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "等待音频输入",
            modelState = "本地识别模型就绪",
            recognitionState = "等待本地识别启动",
            translationState = "正在准备日语→中文翻译模型（后台）",
            dumpState = "未启用",
            original = "",
            translated = "",
            levelHint = ""
        )

        assertEquals(
            "模式：播放捕获模式\n采集：等待音频输入\n模型：本地识别模型就绪\n识别：等待本地识别启动\n翻译：正在准备日语→中文翻译模型（后台）\n调试：未启用\n音量: 未知\n日语：（未识别到日语）\n中文：（翻译模型准备中）",
            result
        )
    }

    @Test
    fun composePipeline_showsDownloadHintWhenTranslatorModelIsDownloading() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "等待音频输入",
            modelState = "本地识别模型就绪",
            recognitionState = "等待本地识别启动",
            translationState = "下载中（已耗时 12s，等待 Wi‑Fi；进度：ML Kit 未提供百分比）",
            dumpState = "未启用",
            original = "",
            translated = "",
            levelHint = ""
        )

        assertEquals(
            "模式：播放捕获模式\n采集：等待音频输入\n模型：本地识别模型就绪\n识别：等待本地识别启动\n翻译：下载中（已耗时 12s，等待 Wi‑Fi；进度：ML Kit 未提供百分比）\n调试：未启用\n音量: 未知\n日语：（未识别到日语）\n中文：（翻译模型下载中）",
            result
        )
    }

    @Test
    fun composePipeline_showsRawTextHintWhenTranslatorNotReady() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "已接收到音频",
            modelState = "本地识别模型就绪",
            recognitionState = "本地识别完成",
            translationState = "翻译器未就绪，先显示原文",
            dumpState = "未启用",
            original = "テストです",
            translated = "",
            levelHint = "音量: 11%"
        )

        assertEquals(
            "模式：播放捕获模式\n采集：已接收到音频\n模型：本地识别模型就绪\n识别：本地识别完成\n翻译：翻译器未就绪，先显示原文\n调试：未启用\n音量: 11%\n日语：テストです\n中文：（翻译尚未就绪，当前先显示原文）",
            result
        )
    }

    @Test
    fun composePipeline_showsPartialAsrFallbackWhileWaitingForStableTranslation() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获+本地识别",
            captureState = "播放音频捕获中",
            modelState = "本地识别模型就绪",
            recognitionState = "本地识别中（实时）",
            translationState = "等待稳定分段后再翻译",
            dumpState = "调试录音已保存",
            original = "ありが",
            translated = "",
            levelHint = "音量: 42%"
        )

        assertTrue(result.contains("中文：（等待更稳定语句后翻译）"))
        assertTrue(result.contains("调试：调试录音已保存"))
        assertTrue(result.contains("音量: 42%"))
    }

    @Test
    fun composePipeline_usesFallbacksWhenDataMissing() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获模式",
            captureState = "等待音频输入",
            modelState = "未开始",
            recognitionState = "未开始",
            translationState = "未开始",
            dumpState = "未启用",
            original = "",
            translated = "",
            levelHint = ""
        )

        assertEquals(
            "模式：播放捕获模式\n采集：等待音频输入\n模型：未开始\n识别：未开始\n翻译：未开始\n调试：未启用\n音量: 未知\n日语：（未识别到日语）\n中文：（暂无翻译结果）",
            result
        )
    }

    @Test
    fun composePipeline_surfacesDetailedLocalAsrStates() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "播放捕获+本地识别",
            captureState = "播放音频捕获中",
            modelState = "本地识别模型就绪",
            recognitionState = "本地识别中（实时）",
            translationState = "等待稳定分段后再翻译",
            dumpState = "调试录音已保存",
            original = "",
            translated = "",
            levelHint = "音量: 9%"
        )

        assertTrue(result.contains("调试：调试录音已保存"))
        assertTrue(result.contains("模型：本地识别模型就绪"))
        assertTrue(result.contains("识别：本地识别中（实时）"))
        assertTrue(result.contains("音量: 9%"))
    }

    @Test
    fun composePipeline_surfacesFallbackAndSeparatedStatesForLocalAsrFlow() {
        val result = SubtitleOverlayFormatter.composePipeline(
            modeLabel = "麦克风+本地识别",
            captureState = "播放捕获初始化失败，已切换到麦克风本地识别",
            modelState = "本地日语识别模型就绪",
            recognitionState = "等待本地识别启动",
            translationState = "等待翻译模型初始化",
            dumpState = "等待写入器初始化",
            original = "",
            translated = "",
            levelHint = ""
        )

        assertTrue(result.contains("采集：播放捕获初始化失败，已切换到麦克风本地识别"))
        assertTrue(result.contains("模型：本地日语识别模型就绪"))
        assertTrue(result.contains("调试：等待写入器初始化"))
    }
}
