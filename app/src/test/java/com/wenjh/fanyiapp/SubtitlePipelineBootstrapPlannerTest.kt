package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlePipelineBootstrapPlannerTest {
    @Test
    fun plan_prefersRecognizerStartupBeforeTranslatorPreparation() {
        val plan = SubtitlePipelineBootstrapPlanner.planFor(InputAvailability.AVAILABLE)

        assertEquals(
            listOf(
                BootstrapStep.PREPARE_RECOGNIZER,
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
                BootstrapStep.PREPARE_RECOGNIZER,
                BootstrapStep.PREPARE_TRANSLATOR
            ),
            plan
        )
    }
}
