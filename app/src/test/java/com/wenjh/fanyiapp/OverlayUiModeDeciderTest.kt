package com.wenjh.fanyiapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayUiModeDeciderTest {
    @Test
    fun shouldAutoCollapse_returnsTrueWhenTranslatorReady() {
        assertTrue(
            OverlayUiModeDecider.shouldAutoCollapse(
                translationState = "翻译模型就绪（允许流量下载）",
                translated = "你好"
            )
        )
    }

    @Test
    fun shouldAutoCollapse_returnsFalseWhenModelReadyButNoTranslationYet() {
        assertFalse(
            OverlayUiModeDecider.shouldAutoCollapse(
                translationState = "翻译模型就绪（允许流量下载）",
                translated = ""
            )
        )
    }

    @Test
    fun shouldAutoCollapse_returnsFalseWhileTranslatorStillDownloading() {
        assertFalse(
            OverlayUiModeDecider.shouldAutoCollapse(
                translationState = "下载中（已耗时 12s，等待 Wi‑Fi；进度：ML Kit 未提供百分比）",
                translated = ""
            )
        )
    }

    @Test
    fun shouldAutoCollapse_returnsFalseOnFailureState() {
        assertFalse(
            OverlayUiModeDecider.shouldAutoCollapse(
                translationState = "翻译模型下载失败：timeout",
                translated = ""
            )
        )
    }

    @Test
    fun shouldAutoCollapse_staysTrueForPolishStates() {
        assertTrue(
            OverlayUiModeDecider.shouldAutoCollapse(
                translationState = "翻译完成（正在润色）",
                translated = "你好"
            )
        )
        assertTrue(
            OverlayUiModeDecider.shouldAutoCollapse(
                translationState = "翻译完成（已润色）",
                translated = "您好"
            )
        )
    }
}
