package com.wenjh.fanyiapp

data class PendingTranslationRequest(
    val text: String,
    val provisional: Boolean
)

class PendingTranslationCoordinator {
    private var pending: PendingTranslationRequest? = null
    private var inFlight: PendingTranslationRequest? = null

    fun rememberPending(text: String, provisional: Boolean) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        val candidate = PendingTranslationRequest(normalized, provisional)
        val current = pending
        pending = when {
            current == null -> candidate
            shouldReplace(current, candidate) -> candidate
            else -> current
        }
    }

    fun markInFlight(text: String, provisional: Boolean) {
        val normalized = text.trim()
        inFlight = if (normalized.isBlank()) null else PendingTranslationRequest(normalized, provisional)
    }

    fun peek(): PendingTranslationRequest? = pending

    fun hasPending(): Boolean = pending != null

    fun hasInFlight(): Boolean = inFlight != null

    fun requeueInFlight(): PendingTranslationRequest? {
        val active = inFlight ?: return null
        inFlight = null
        rememberPending(active.text, active.provisional)
        return active
    }

    fun consumeReady(): PendingTranslationRequest? {
        val ready = pending
        pending = null
        return ready
    }

    fun consumeReadyAfter(completedText: String): PendingTranslationRequest? {
        val normalizedCompleted = completedText.trim()
        if (normalizedCompleted.isBlank()) return null

        val completed = inFlight
        inFlight = null
        val queued = pending ?: return null
        pending = null

        if (completed != null && queued.text == completed.text) {
            if (!completed.provisional && queued.provisional == completed.provisional) {
                return null
            }
            if (completed.provisional && !queued.provisional) {
                return queued
            }
        }
        if (queued.text == normalizedCompleted && !queued.provisional) {
            return queued
        }
        if (queued.text == normalizedCompleted) {
            return null
        }
        if (completed != null && queued.text == completed.text && queued.provisional == completed.provisional) {
            return null
        }
        return queued
    }

    private fun shouldReplace(current: PendingTranslationRequest, candidate: PendingTranslationRequest): Boolean {
        return when {
            !candidate.provisional && current.provisional -> true
            candidate.provisional && !current.provisional -> false
            candidate.text.length > current.text.length -> true
            candidate.text.length < current.text.length -> false
            else -> true
        }
    }
}
