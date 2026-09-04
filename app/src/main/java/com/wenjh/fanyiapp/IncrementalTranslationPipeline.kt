package com.wenjh.fanyiapp

import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class IncrementalTranslationPipeline(
    private val translator: Translator,
    private val maxConcurrency: Int = 3
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Deferred<String>>()
    private val translationCache = LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true)
    private var lastSubmittedSource: String = ""

    var onTranslationUpdate: ((source: String, translated: String, isPartial: Boolean) -> Unit)? = null

    @Synchronized
    fun submitPartial(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        val commonPrefix = findCommonPrefix(lastSubmittedSource, normalized)
        val newPart = if (commonPrefix < normalized.length) normalized.substring(commonPrefix) else ""

        if (newPart.length < MIN_NEW_CHARS && !isSentenceEnd(normalized)) return

        translationCache[normalized]?.let { cached ->
            emitUpdate(normalized, cached, isPartial = true)
            lastSubmittedSource = normalized
            return
        }

        cancelStaleJobs(normalized)

        if (activeJobs.size < maxConcurrency) {
            submitTranslation(normalized, isPartial = true)
        }

        lastSubmittedSource = normalized
    }

    @Synchronized
    fun submitFinal(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        cancelAllJobs()

        translationCache[normalized]?.let { cached ->
            emitUpdate(normalized, cached, isPartial = false)
            lastSubmittedSource = normalized
            return
        }

        submitTranslation(normalized, isPartial = false)
        lastSubmittedSource = normalized
    }

    private fun submitTranslation(text: String, isPartial: Boolean) {
        val job = scope.async {
            try {
                translator.translate(text).await().trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }
        activeJobs[text] = job

        scope.launch {
            val result = try {
                job.await()
            } catch (_: CancellationException) {
                ""
            }
            activeJobs.remove(text)
            if (result.isNotBlank()) {
                addToCache(text, result)
                emitUpdate(text, result, isPartial)
            }
        }
    }

    private fun emitUpdate(source: String, translated: String, isPartial: Boolean) {
        onTranslationUpdate?.invoke(source, translated, isPartial)
    }

    @Synchronized
    private fun cancelStaleJobs(currentText: String) {
        val stale = activeJobs.keys.filter { key ->
            !currentText.startsWith(key) && !key.startsWith(currentText)
        }
        stale.forEach { key ->
            activeJobs[key]?.cancel()
            activeJobs.remove(key)
        }
    }

    @Synchronized
    private fun cancelAllJobs() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    @Synchronized
    private fun addToCache(key: String, value: String) {
        if (translationCache.size >= MAX_CACHE_SIZE) {
            val eldest = translationCache.keys.firstOrNull()
            eldest?.let { translationCache.remove(it) }
        }
        translationCache[key] = value
    }

    fun release() {
        cancelAllJobs()
        scope.cancel()
    }

    companion object {
        private const val MAX_CACHE_SIZE = 50
        private const val MIN_NEW_CHARS = 2

        fun findCommonPrefix(a: String, b: String): Int {
            val minLen = minOf(a.length, b.length)
            for (i in 0 until minLen) {
                if (a[i] != b[i]) return i
            }
            return minLen
        }

        fun isSentenceEnd(text: String): Boolean {
            return text.endsWith("。") || text.endsWith("！") || text.endsWith("？") ||
                text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
        }
    }
}
