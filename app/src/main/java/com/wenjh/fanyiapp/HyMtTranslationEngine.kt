package com.wenjh.fanyiapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

        val prompt = HyMtPromptBuilder.build(
            recognizedText = text,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
        val rawOutput = withContext(Dispatchers.Default) {
            HyMtNativeBridge.translate(prompt, maxTokens = 256, temperature = 0.2f)
        }
        val cleaned = HyMtResultParser.clean(rawOutput)
        state = EngineState.Ready
        TranslationResult(
            text = cleaned,
            backend = "hy-mt-native",
            rawOutput = rawOutput
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
}
