package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePipelineBootstrapPlannerTest {
    @Test
    fun plan_prefersAudioAndAsrStartupBeforeTranslatorPreparation() {
        val plan = SubtitlePipelineBootstrapPlanner.planFor(InputAvailability.AVAILABLE)

        assertEquals(
            listOf(
                BootstrapStep.PREPARE_AUDIO_SOURCE,
                BootstrapStep.PREPARE_ASR,
                BootstrapStep.START_RECOGNITION,
                BootstrapStep.PREPARE_TRANSLATOR
            ),
            plan
        )
    }

    @Test
    fun plan_skipsRecognitionStartWhenAudioInputUnavailable() {
        val plan = SubtitlePipelineBootstrapPlanner.planFor(InputAvailability.UNAVAILABLE)

        assertEquals(
            listOf(
                BootstrapStep.PREPARE_AUDIO_SOURCE,
                BootstrapStep.PREPARE_ASR,
                BootstrapStep.PREPARE_TRANSLATOR
            ),
            plan
        )
    }

    @Test
    fun plan_keepsTranslatorPreparationAsLastBackgroundWarmupStep() {
        assertTrue(SubtitlePipelineBootstrapPlanner.describesBackgroundTranslatorWarmup(InputAvailability.AVAILABLE))
        assertTrue(SubtitlePipelineBootstrapPlanner.describesBackgroundTranslatorWarmup(InputAvailability.UNAVAILABLE))
    }
}
