package com.wenjh.fanyiapp

class TranslationSegmenter(
    private val minPartialLength: Int = 8,
    private val stableWindowMs: Long = 120,
    private val minMeaningfulGrowthChars: Int = 3
) {
    private var lastPartial: String = ""
    private var lastPartialSinceMs: Long = 0L
    private var lastSubmitted: String = ""
    private var hasDivergedSinceLastSubmission: Boolean = false

    fun onPartial(text: String, nowMs: Long): String? {
        val normalized = text.trim()
        if (normalized.isBlank()) return null

        val punctuated = normalized.endsWith("。") || normalized.endsWith("！") || normalized.endsWith("？") ||
            normalized.endsWith(".") || normalized.endsWith("!") || normalized.endsWith("?")
        val stable = nowMs - lastPartialSinceMs >= stableWindowMs
        val grewFromPrevious = lastPartial.isNotBlank() && normalized.startsWith(lastPartial) && normalized != lastPartial

        if (normalized != lastSubmitted && lastSubmitted.isNotBlank()) {
            hasDivergedSinceLastSubmission = true
        }

        if (normalized == lastSubmitted && !hasDivergedSinceLastSubmission) {
            return null
        }

        if (punctuated) {
            lastPartial = normalized
            lastPartialSinceMs = nowMs
            lastSubmitted = normalized
            hasDivergedSinceLastSubmission = false
            return normalized
        }

        if (normalized != lastPartial) {
            val shouldFlushGrowingPartial =
                grewFromPrevious &&
                    stable &&
                    normalized.length >= minPartialLength &&
                    hasMeaningfulGrowthSinceLastSubmission(normalized)
            lastPartial = normalized
            lastPartialSinceMs = nowMs
            return if (shouldFlushGrowingPartial) {
                lastSubmitted = normalized
                hasDivergedSinceLastSubmission = false
                normalized
            } else {
                null
            }
        }

        return if (
            stable &&
            normalized.length >= minPartialLength &&
            hasMeaningfulGrowthSinceLastSubmission(normalized)
        ) {
            lastSubmitted = normalized
            hasDivergedSinceLastSubmission = false
            normalized
        } else {
            null
        }
    }

    fun onFinal(text: String): String? {
        val normalized = text.trim()
        if (normalized.isBlank()) return null
        if (normalized == lastSubmitted && !hasDivergedSinceLastSubmission) return null
        if (!hasSentenceEnding(normalized) && looksIncompleteJapanese(normalized) && normalized.length < MAX_FORCED_SEGMENT_LENGTH) {
            lastPartial = normalized
            lastPartialSinceMs = System.currentTimeMillis()
            return null
        }
        lastSubmitted = normalized
        lastPartial = normalized
        hasDivergedSinceLastSubmission = false
        return normalized
    }

    private fun hasSentenceEnding(text: String): Boolean {
        return text.endsWith("。") || text.endsWith("！") || text.endsWith("？") ||
            text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
    }

    private fun looksIncompleteJapanese(text: String): Boolean {
        val endings = listOf("は", "が", "を", "に", "で", "と", "も", "から", "まで", "ので", "けど", "けれど", "そして", "しかし")
        return endings.any { text.endsWith(it) }
    }

    companion object {
        private const val MAX_FORCED_SEGMENT_LENGTH = 36
    }
}