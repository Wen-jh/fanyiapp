package com.wenjh.fanyiapp

import com.arm.aichat.internal.InferenceEngineImpl

object HyMtNativeBridge {
    private const val SYSTEM_PROMPT =
        "你是一个离线翻译引擎。请把用户给出的 OCR 文本翻译成自然、准确、简洁的目标语言。" +
            "只输出译文，不要解释，不要重复原文，不要补充额外说明。"

    @Volatile
    private var ready = false
    private var nativeLibDir: String? = null
    private var modelPath: String? = null
    private var engine: InferenceEngineImpl? = null

    @Synchronized
    fun initialize(nativeLibDir: String, modelPath: String, threads: Int, contextSize: Int): Boolean {
        release()
        this.nativeLibDir = nativeLibDir
        this.modelPath = modelPath
        return runCatching {
            val inferenceEngine = InferenceEngineImpl(nativeLibDir)
            inferenceEngine.loadModel(modelPath)
            inferenceEngine.prepareModel()
            inferenceEngine.setSystemPrompt(SYSTEM_PROMPT)
            engine = inferenceEngine
            ready = true
            true
        }.getOrElse {
            ready = false
            engine = null
            false
        }
    }

    fun isReady(): Boolean = ready

    @Synchronized
    fun translate(prompt: String, maxTokens: Int, temperature: Float): String {
        val inferenceEngine = engine ?: error("Hy-MT 引擎未初始化，请先调用 initialize()")
        if (!ready) error("Hy-MT 引擎未就绪")

        // 模型已常驻内存，只需设置 prompt 并生成
        inferenceEngine.processPrompt(prompt, maxTokens)
        return inferenceEngine.generateAllTokens()
    }

    @Synchronized
    fun release() {
        runCatching { engine?.unloadModel() }
        runCatching { engine?.destroy() }
        engine = null
        ready = false
    }
}
