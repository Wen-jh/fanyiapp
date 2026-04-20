package com.wenjh.fanyiapp

enum class BootstrapStep {
    PREPARE_AUDIO_SOURCE,
    PREPARE_ASR,
    START_RECOGNITION,
    PREPARE_TRANSLATOR
}

enum class InputAvailability {
    AVAILABLE,
    UNAVAILABLE
}

object SubtitlePipelineBootstrapPlanner {
    fun planFor(inputAvailability: InputAvailability): List<BootstrapStep> {
        return when (inputAvailability) {
            InputAvailability.AVAILABLE -> listOf(
                BootstrapStep.PREPARE_AUDIO_SOURCE,
                BootstrapStep.PREPARE_ASR,
                BootstrapStep.START_RECOGNITION,
                BootstrapStep.PREPARE_TRANSLATOR
            )

            InputAvailability.UNAVAILABLE -> listOf(
                BootstrapStep.PREPARE_AUDIO_SOURCE,
                BootstrapStep.PREPARE_ASR,
                BootstrapStep.PREPARE_TRANSLATOR
            )
        }
    }

    fun describesBackgroundTranslatorWarmup(inputAvailability: InputAvailability): Boolean {
        return planFor(inputAvailability).lastOrNull() == BootstrapStep.PREPARE_TRANSLATOR
    }
}
