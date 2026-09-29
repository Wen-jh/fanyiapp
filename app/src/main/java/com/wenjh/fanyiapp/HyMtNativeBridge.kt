package com.wenjh.fanyiapp

import com.arm.aichat.internal.InferenceEngineImpl

object HyMtNativeBridge {
    /**
     * 系统提示词只说明角色，绝不能出现某种具体语言的名字。
     *
     * 旧版本写的是「请把用户给出的 OCR 文本翻译成…目标语言」，整段中文且没有点明目标语言，
     * 1.25bit 量化的小模型会默认往中文上靠，导致无论选什么语言都输出中文。
     * 目标语言现在由用户提示词（HyMtPromptBuilder）显式指定。
     */
    private const val SYSTEM_PROMPT =
        "You are a professional translation engine. Translate the user's text into the target " +
            "language specified in the instruction. Output only the translation, with no explanation " +
            "and no extra notes."

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
