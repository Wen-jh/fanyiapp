package com.wenjh.fanyiapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

class HyMtTranslationEngine(private val context: Context) : PhotoTranslationEngine {
    private val inferenceMutex = Mutex()
    private val modelManager = HyMtModelManager(context)

    @Volatile
    private var state: EngineState = EngineState.Idle

    override suspend fun prepareIfNeeded(onProgress: ((PreparationProgress) -> Unit)?): PreparationResult {
        state = EngineState.Preparing("正在检查 Hy-MT 离线模型", null)
        val prepared = modelManager.prepareIfNeeded(onProgress)
        if (!prepared.ready) {
            state = EngineState.Error(prepared.message)
            return prepared
        }

        state = EngineState.Loading("正在加载 Hy-MT 模型到内存")
        val initialized = withContext(Dispatchers.Default) {
            HyMtNativeBridge.initialize(
                nativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty(),
                modelPath = modelManager.installedModelFile.absolutePath,
                threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                contextSize = 1024
            )
        }
        state = if (initialized) {
            EngineState.Ready
        } else {
            EngineState.Error("Hy-MT native 推理初始化失败")
        }
        return if (initialized) {
            PreparationResult(true, "Hy-MT 模型加载完成")
        } else {
            PreparationResult(false, "Hy-MT native 推理初始化失败")
        }
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationResult = inferenceMutex.withLock {
        state = EngineState.Translating("正在进行 Hy-MT 离线翻译：$sourceLanguage → $targetLanguage")
        if (!HyMtNativeBridge.isReady()) {
            error("Hy-MT native 推理尚未就绪")
        }

        val segments = splitForTranslation(text)
        val translatedSegments = mutableListOf<TranslatedSegment>()
        val rawSegments = mutableListOf<String>()
        var fallbackUsed = false

        for (segment in segments) {
            val prompt = HyMtPromptBuilder.build(
                recognizedText = segment.text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )
            val rawOutput = withContext(Dispatchers.Default) {
                HyMtNativeBridge.translate(prompt, maxTokens = 384, temperature = 0.2f)
            }
            val cleaned = HyMtResultParser.clean(rawOutput)
            val finalized = finalizeTranslationResult(
                recognizedText = segment.text,
                cleanedOutput = cleaned,
                rawOutput = rawOutput,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )
            translatedSegments += TranslatedSegment(
                text = finalized.text,
                breakType = segment.breakType
            )
            rawSegments += rawOutput
            if (finalized.backend == "builtin-fallback") {
                fallbackUsed = true
            }
        }

        state = EngineState.Ready
        TranslationResult(
            text = mergeTranslatedSegments(translatedSegments),
            backend = if (fallbackUsed) "builtin-fallback" else "hy-mt-native",
            rawOutput = rawSegments.joinToString("\n---\n")
        )
    }

    override fun currentState(): EngineState = when (val managerState = modelManager.currentState()) {
        is EngineState.Idle -> state
        else -> managerState
    }

    override fun release() {
        HyMtNativeBridge.release()
        state = EngineState.Idle
    }

    companion object {
        internal data class TranslationSegment(
            val text: String,
            val breakType: SegmentBreakType
        )

        internal data class TranslatedSegment(
            val text: String,
            val breakType: SegmentBreakType
        )

        internal enum class SegmentBreakType {
            PARAGRAPH,
            CONTINUATION
        }

        internal fun finalizeTranslationResult(
            recognizedText: String,
            cleanedOutput: String,
            rawOutput: String? = null,
            sourceLanguage: String,
            targetLanguage: String
        ): TranslationResult {
            if (shouldUseEnglishChineseFallback(recognizedText, cleanedOutput, sourceLanguage, targetLanguage)) {
                val fallback = OfflineEnglishChineseTranslator.translate(recognizedText)
                if (fallback.usedBuiltinPhrase || fallback.usedWordFallback) {
                    return TranslationResult(
                        text = fallback.text,
                        backend = "builtin-fallback",
                        rawOutput = rawOutput,
                        statusMessage = "Used builtin English-Chinese fallback"
                    )
                }
            }
            return TranslationResult(
                text = cleanedOutput,
                backend = "hy-mt-native",
                rawOutput = rawOutput
            )
        }

        internal fun shouldUseEnglishChineseFallback(
            recognizedText: String,
            translatedText: String,
            sourceLanguage: String,
            targetLanguage: String
        ): Boolean {
            if (!sourceLanguage.equals("English", ignoreCase = true) ||
                !targetLanguage.equals("Chinese", ignoreCase = true)
            ) {
                return false
            }

            val normalizedSource = normalizeForComparison(recognizedText)
            val normalizedTranslated = normalizeForComparison(translatedText)
            if (normalizedSource.isBlank()) {
                return false
            }
            if (normalizedTranslated.isBlank()) {
                return true
            }
            if (normalizedSource == normalizedTranslated) {
                return true
            }
            if (looksMostlyLatin(normalizedTranslated) && normalizedTranslated.contains(normalizedSource)) {
                return true
            }
            return looksMostlyLatin(normalizedTranslated) && !containsCjk(translatedText)
        }

        internal fun splitForTranslation(
            text: String,
            maxCharsPerSegment: Int = 260
        ): List<TranslationSegment> {
            val normalized = ImageTranslationFormatter.normalizeOcrTextForSegmentation(text)
            if (normalized.isBlank()) return emptyList()
            if (normalized.length <= maxCharsPerSegment && !normalized.contains("\n\n")) {
                return listOf(TranslationSegment(normalized, SegmentBreakType.PARAGRAPH))
            }

            val segments = mutableListOf<TranslationSegment>()
            val paragraphs = normalized
                .split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            for (paragraph in paragraphs) {
                val paragraphSegments = splitParagraph(paragraph, maxCharsPerSegment)
                paragraphSegments.forEachIndexed { index, segment ->
                    segments += TranslationSegment(
                        text = segment,
                        breakType = if (index == 0) SegmentBreakType.PARAGRAPH else SegmentBreakType.CONTINUATION
                    )
                }
            }
            return segments
        }

        internal fun mergeTranslatedSegments(segments: List<TranslatedSegment>): String {
            if (segments.isEmpty()) return ""

            val merged = StringBuilder()
            segments.forEachIndexed { index, segment ->
                val text = segment.text.trim()
                if (text.isEmpty()) return@forEachIndexed
                if (merged.isEmpty()) {
                    merged.append(text)
                    return@forEachIndexed
                }

                when (segment.breakType) {
                    SegmentBreakType.PARAGRAPH -> merged.append("\n\n").append(text)
                    SegmentBreakType.CONTINUATION -> {
                        if (shouldAppendWithSpace(merged.last(), text.first())) {
                            merged.append(' ')
                        }
                        merged.append(text)
                    }
                }
            }
            return merged.toString().trim()
        }

        private fun splitParagraph(paragraph: String, maxCharsPerSegment: Int): List<String> {
            val normalizedParagraph = paragraph.replace(Regex("\\s+"), " ").trim()
            if (normalizedParagraph.length <= maxCharsPerSegment) {
                return listOf(normalizedParagraph)
            }

            val segments = mutableListOf<String>()
            var remaining = normalizedParagraph
            while (remaining.length > maxCharsPerSegment) {
                val splitIndex = findBestSplitIndex(remaining, maxCharsPerSegment)
                val head = remaining.substring(0, splitIndex).trim()
                if (head.isNotEmpty()) {
                    segments += head
                }
                remaining = remaining.substring(splitIndex).trim()
                if (remaining.isEmpty()) break
            }
            if (remaining.isNotEmpty()) {
                segments += remaining
            }
            return segments
        }

        private fun findBestSplitIndex(text: String, maxCharsPerSegment: Int): Int {
            val sentenceBreak = findLastBoundary(text, maxCharsPerSegment, listOf('.', '!', '?', '。', '！', '？', ';', '；'))
            if (sentenceBreak != null) return sentenceBreak

            val softBreak = findLastBoundary(text, maxCharsPerSegment, listOf(',', '，', ':', '：', '、'))
            if (softBreak != null) return softBreak

            val wordBreak = text.lastIndexOf(' ', startIndex = maxCharsPerSegment.coerceAtMost(text.lastIndex))
            if (wordBreak >= maxCharsPerSegment / 2) return wordBreak + 1

            return maxCharsPerSegment.coerceAtMost(text.length)
        }

        private fun findLastBoundary(text: String, maxCharsPerSegment: Int, boundaries: List<Char>): Int? {
            val searchEnd = maxCharsPerSegment.coerceAtMost(text.length - 1)
            for (index in searchEnd downTo 0) {
                val current = text[index]
                if (current in boundaries && index >= maxCharsPerSegment / 2) {
                    return index + 1
                }
            }
            return null
        }

        private fun shouldAppendWithSpace(previousChar: Char, nextChar: Char): Boolean {
            if (previousChar.isWhitespace() || nextChar.isWhitespace()) return false
            if (containsCjk(previousChar.toString()) || containsCjk(nextChar.toString())) return false
            return true
        }

        private fun normalizeForComparison(text: String): String {
            return text
                .lowercase(Locale.US)
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun looksMostlyLatin(text: String): Boolean {
            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return false
            val latinCount = letters.count { it.code in 0x0041..0x007A || it.code in 0x00C0..0x024F }
            return latinCount * 10 >= letters.length * 8
        }

        private fun containsCjk(text: String): Boolean {
            return text.any { character ->
                val block = Character.UnicodeBlock.of(character)
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                    block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            }
        }
    }
}
