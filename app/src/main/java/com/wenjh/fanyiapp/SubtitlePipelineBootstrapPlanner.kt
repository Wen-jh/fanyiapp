package com.wenjh.fanyiapp

enum class BootstrapStep {
    PREPARE_RECOGNIZER,
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
                BootstrapStep.PREPARE_RECOGNIZER,
                BootstrapStep.START_RECOGNITION,
                BootstrapStep.PREPARE_TRANSLATOR
            )
            InputAvailability.UNAVAILABLE -> listOf(
                BootstrapStep.PREPARE_RECOGNIZER,
                BootstrapStep.PREPARE_TRANSLATOR
            )
        }
    }
}
