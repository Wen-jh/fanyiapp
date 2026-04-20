package com.wenjh.fanyiapp

data class PendingTranslationRequest(
    val text: String,
    val provisional: Boolean
)

class PendingTranslationCoordinator {
    private var pending: PendingTranslationRequest? = null

    fun rememberPending(text: String, provisional: Boolean) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        val candidate = PendingTranslationRequest(normalized, provisional)
        val current = pending
        pending = when {
            current == null -> candidate
            !provisional -> candidate
            current.provisional -> candidate
            else -> current
        }
    }

    fun peek(): PendingTranslationRequest? = pending

    fun hasPending(): Boolean = pending != null

    fun consumeReady(): PendingTranslationRequest? {
        val ready = pending
        pending = null
        return ready
    }
}
