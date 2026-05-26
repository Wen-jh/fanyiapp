package com.arm.aichat.internal

class InferenceEngineImpl(
    private val nativeLibDir: String
) {
    init {
        ensureLibrariesLoaded()
        init(nativeLibDir)
    }

    @Synchronized
    fun loadModel(modelPath: String) {
        check(load(modelPath) == 0) { "加载 Hy-MT 模型失败：$modelPath" }
    }

    @Synchronized
    fun prepareModel() {
        check(prepare() == 0) { "准备 Hy-MT 推理上下文失败" }
    }

    @Synchronized
    fun setSystemPrompt(prompt: String) {
        when (processSystemPrompt(prompt)) {
            0 -> Unit
            1 -> error("Hy-MT system prompt 超过上下文限制")
            2 -> error("Hy-MT system prompt 解码失败")
            else -> error("Hy-MT system prompt 处理失败")
        }
    }

    @Synchronized
    fun processPrompt(userPrompt: String, predictLength: Int) {
        when (processUserPrompt(userPrompt, predictLength)) {
            0 -> Unit
            2 -> error("Hy-MT user prompt 解码失败")
            else -> error("Hy-MT user prompt 处理失败")
        }
    }

    @Synchronized
    fun generateAllTokens(): String {
        val builder = StringBuilder()
        while (true) {
            val token = generateNextToken() ?: break
            builder.append(token)
        }
        return builder.toString()
    }

    @Synchronized
    fun unloadModel() {
        unload()
    }

    @Synchronized
    fun destroy() {
        shutdown()
    }

    @Synchronized
    fun getSystemInfo(): String = systemInfo()

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun prepare(): Int
    private external fun systemInfo(): String
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()

    companion object {
        @Volatile
        private var librariesLoaded = false

        @Synchronized
        private fun ensureLibrariesLoaded() {
            if (librariesLoaded) return
            listOf(
                "omp",
                "ggml-base",
                "ggml",
                "llama",
                "ai-chat"
            ).forEach(System::loadLibrary)
            librariesLoaded = true
        }
    }
}
