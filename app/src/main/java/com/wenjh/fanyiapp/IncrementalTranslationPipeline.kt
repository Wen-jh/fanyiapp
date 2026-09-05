package com.wenjh.fanyiapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 增量翻译管道 — 支持任意翻译后端（ML Kit / Hy-MT / 其他）
 * 通过 translateFn 注入翻译能力，解耦具体引擎
 */
class IncrementalTranslationPipeline(
    private val translateFn: suspend (String) -> String,
    private val maxConcurrency: Int = 1
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Deferred<String>>()
    private val translationCache = LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true)
    private var lastSubmittedSource: String = ""
    private var generation: Long = 0L
    private var latestRequestId: Long = 0L
    private var pendingLatest: Pair<String, Boolean>? = null

    var onTranslationUpdate: ((source: String, translated: String, isPartial: Boolean) -> Unit)? = null

    @Synchronized
    fun submitPartial(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        // 新句子检测：如果与上次提交完全不同，清空旧状态
        if (lastSubmittedSource.isNotBlank() && isNewSentence(lastSubmittedSource, normalized)) {
            cancelAllJobs()
            translationCache.clear()
            lastSubmittedSource = ""
            generation++
        }

        val commonPrefix = findCommonPrefix(lastSubmittedSource, normalized)
        val newPart = if (commonPrefix < normalized.length) normalized.substring(commonPrefix) else ""

        if (newPart.length < MIN_NEW_CHARS && !isSentenceEnd(normalized)) return

        // 同一句话的前缀增长：取消旧版本，只保留最新版本，避免旧结果晚到后覆盖新结果。
        cancelStaleJobs(normalized)
        translationCache[normalized]?.let { cached ->
            emitUpdate(normalized, cached, isPartial = true)
            lastSubmittedSource = normalized
            return
        }

        submitTranslation(normalized, isPartial = true)
        lastSubmittedSource = normalized
    }

    @Synchronized
    fun submitFinal(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        // 新句子检测
        if (lastSubmittedSource.isNotBlank() && isNewSentence(lastSubmittedSource, normalized)) {
            cancelAllJobs()
            translationCache.clear()
            generation++
        }

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
        val jobGeneration = generation
        val requestId = ++latestRequestId
        val job = scope.async {
            try {
                translateFn(text).trim()
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
            if (jobGeneration != generation || requestId != latestRequestId) return@launch
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
        // 增量翻译只需要最新前缀。旧前缀即使完成也不能用于当前字幕，继续推理只会拖慢下一次更新。
        val stale = activeJobs.keys.filter { it != currentText }
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

        fun isNewSentence(previous: String, current: String): Boolean {
            // 如果两个文本的前10个字符完全不同，认为是新句子
            val prevPrefix = previous.take(10)
            val currPrefix = current.take(10)
            return !current.startsWith(prevPrefix) && !previous.startsWith(currPrefix)
        }
    }
}
