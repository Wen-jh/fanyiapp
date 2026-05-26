package com.wenjh.fanyiapp

interface PhotoTranslationEngine {
    suspend fun prepareIfNeeded(onProgress: ((PreparationProgress) -> Unit)? = null): PreparationResult
    suspend fun translate(text: String): TranslationResult
    fun currentState(): EngineState
    fun release()
}

data class PreparationProgress(
    val message: String,
    val copiedBytes: Long,
    val totalBytes: Long
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

data class PreparationResult(
    val ready: Boolean,
    val message: String,
    val copiedBytes: Long? = null,
    val totalBytes: Long? = null
)

data class TranslationResult(
    val text: String,
    val backend: String,
    val rawOutput: String? = null
)

sealed class EngineState {
    data object Idle : EngineState()
    data class Preparing(val message: String, val progress: Int? = null) : EngineState()
    data class Loading(val message: String) : EngineState()
    data object Ready : EngineState()
    data class Translating(val message: String) : EngineState()
    data class Error(val message: String) : EngineState()
}
