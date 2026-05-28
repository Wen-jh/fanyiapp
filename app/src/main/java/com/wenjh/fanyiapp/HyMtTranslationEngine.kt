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

    override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): TranslationResult = inferenceMutex.withLock {
        state = EngineState.Translating("正在进行 Hy-MT 离线翻译（${sourceLanguage} → ${targetLanguage}）")
        if (!HyMtNativeBridge.isReady()) {
            error("Hy-MT native 推理尚未就绪")
        }

        val segments = splitForTranslation(text)
        val translatedSegments = mutableListOf<String>()
        val rawSegments = mutableListOf<String>()
        var fallbackUsed = false

        for (segment in segments) {
            val prompt = HyMtPromptBuilder.build(
                recognizedText = segment,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )
            val rawOutput = withContext(Dispatchers.Default) {
                HyMtNativeBridge.translate(prompt, maxTokens = 384, temperature = 0.2f)
            }
            val cleaned = HyMtResultParser.clean(rawOutput)
            val finalized = finalizeTranslationResult(
                recognizedText = segment,
                cleanedOutput = cleaned,
                rawOutput = rawOutput,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )
            translatedSegments += finalized.text
            rawSegments += rawOutput
            if (finalized.backend == "builtin-fallback") {
                fallbackUsed = true
            }
        }

        state = EngineState.Ready
        return TranslationResult(
            text = translatedSegments.joinToString("\n").trim(),
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
            if (!sourceLanguage.equals("English", ignoreCase = true) || !targetLanguage.equals("Chinese", ignoreCase = true)) {
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

        internal fun splitForTranslation(text: String, maxCharsPerSegment: Int = 260): List<String> {
            val normalized = ImageTranslationFormatter.normalizeRecognizedText(text)
            if (normalized.isBlank()) return emptyList()
            if (normalized.length <= maxCharsPerSegment) return listOf(normalized)

            val segments = mutableListOf<String>()
            val current = StringBuilder()
            val paragraphs = normalized.lines().filter { it.isNotBlank() }
            for (paragraph in paragraphs) {
                if (current.isEmpty()) {
                    appendChunk(current, paragraph, maxCharsPerSegment, segments)
                    continue
                }
                if (current.length + 1 + paragraph.length <= maxCharsPerSegment) {
                    current.append('\n').append(paragraph)
                } else {
                    segments += current.toString()
                    current.clear()
                    appendChunk(current, paragraph, maxCharsPerSegment, segments)
                }
            }
            if (current.isNotEmpty()) {
                segments += current.toString()
            }
            return segments
        }

        private fun appendChunk(
            current: StringBuilder,
            paragraph: String,
            maxCharsPerSegment: Int,
            segments: MutableList<String>
        ) {
            if (paragraph.length <= maxCharsPerSegment) {
                current.append(paragraph)
                return
            }

            var start = 0
            while (start < paragraph.length) {
                val end = (start + maxCharsPerSegment).coerceAtMost(paragraph.length)
                val chunk = paragraph.substring(start, end).trim()
                if (chunk.isNotEmpty()) {
                    if (current.isEmpty()) {
                        current.append(chunk)
                    } else {
                        segments += current.toString()
                        current.clear()
                        current.append(chunk)
                    }
                    if (current.length >= maxCharsPerSegment) {
                        segments += current.toString()
                        current.clear()
                    }
                }
                start = end
            }
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
