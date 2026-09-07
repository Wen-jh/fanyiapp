package com.wenjh.fanyiapp

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class ModelPreparationState(val statusText: String) {
    PREPARING("正在准备本地日语识别模型"),
    READY("本地日语识别模型就绪"),
    FAILED("本地日语识别模型准备失败")
}

class VoskModelManager(
    private val assetRoot: String = ASSET_MODEL_ROOT,
    private val installDirName: String = MODEL_DIR_NAME
) {
    companion object {
        const val ASSET_MODEL_ROOT = "model-ja-small"
        const val MODEL_DIR_NAME = "model-ja-small"
        private const val READY_MARKER = ".ready"
        private val REQUIRED_MODEL_PATHS = listOf(
            "am/final.mdl",
            "conf/model.conf",
            "graph/Gr.fst",
            "graph/HCLr.fst",
            "graph/phones/word_boundary.int"
        )
        private val OPTIONAL_TOP_LEVEL_WRAPPERS = listOf(
            "vosk-model-small-ja-0.22",
            MODEL_DIR_NAME
        )
    }

    data class ModelResolution(
        val state: ModelPreparationState,
        val modelDir: File,
        val markerFile: File
    )

    fun resolveModelState(baseDir: File): ModelResolution {
        val modelDir = File(baseDir, installDirName)
        val markerFile = File(modelDir, READY_MARKER)
        val state = when {
            modelDir.exists() && markerFile.exists() -> ModelPreparationState.READY
            else -> ModelPreparationState.PREPARING
        }
        return ModelResolution(state, modelDir, markerFile)
    }

    fun hasRequiredModelFiles(modelDir: File): Boolean {
        return REQUIRED_MODEL_PATHS.all { relativePath -> File(modelDir, relativePath).isFile }
    }

    fun prepareModel(
        context: Context,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> {
        return runCatching {
            val rootDir = File(context.filesDir, "vosk")
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                throw IOException("无法创建模型根目录: ${rootDir.absolutePath}")
            }
            val resolution = resolveModelState(rootDir)
            if (resolution.state == ModelPreparationState.READY && hasRequiredModelFiles(resolution.modelDir)) {
                return@runCatching resolution.modelDir
            }
            if (resolution.modelDir.exists()) {
                resolution.modelDir.deleteRecursively()
            }
            if (!resolution.modelDir.mkdirs()) {
                throw IOException("无法创建模型目录: ${resolution.modelDir.absolutePath}")
            }
            // 先统计总大小用于进度计算
            val totalBytes = calculateAssetSize(context)
            var copiedBytes = 0L
            copyModelAssets(context, resolution.modelDir) { bytes ->
                copiedBytes += bytes
                onProgress?.invoke(copiedBytes, totalBytes)
            }
            if (!hasRequiredModelFiles(resolution.modelDir)) {
                throw IllegalStateException(
                    "未发现完整的 Vosk 日语模型文件，请将 vosk-model-small-ja-0.22 解压到 app/src/main/assets/$assetRoot/"
                )
            }
            resolution.markerFile.writeText("ready")
            resolution.modelDir
        }
    }

    private fun calculateAssetSize(context: Context): Long {
        val assetManager = context.assets
        val nestedRoot = OPTIONAL_TOP_LEVEL_WRAPPERS
            .asSequence()
            .map { "$assetRoot/$it" }
            .firstOrNull { assetManager.list(it)?.isNotEmpty() == true }
        val rootPath = nestedRoot ?: assetRoot
        return calculateAssetDirSize(assetManager, rootPath)
    }

    private fun calculateAssetDirSize(assetManager: android.content.res.AssetManager, path: String): Long {
        val children = assetManager.list(path).orEmpty()
        if (children.isEmpty()) {
            return runCatching { assetManager.openFd(path).length }.getOrElse {
                runCatching {
                    var size = 0L
                    assetManager.open(path).use { input ->
                        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            size += r
                        }
                    }
                    size
                }.getOrDefault(0L)
            }
        }
        var total = 0L
        for (child in children) {
            total += calculateAssetDirSize(assetManager, if (path.isBlank()) child else "$path/$child")
        }
        return total
    }

    private fun copyModelAssets(
        context: Context,
        targetDir: File,
        onBytesCopied: ((Long) -> Unit)? = null
    ) {
        val assetManager = context.assets
        val directRootChildren = assetManager.list(assetRoot).orEmpty()
        val nestedRoot = OPTIONAL_TOP_LEVEL_WRAPPERS
            .asSequence()
            .map { "$assetRoot/$it" }
            .firstOrNull { assetManager.list(it)?.isNotEmpty() == true }

        when {
            nestedRoot != null -> copyAssetDirectory(context, nestedRoot, targetDir, onBytesCopied)
            directRootChildren.isNotEmpty() -> copyAssetDirectory(context, assetRoot, targetDir, onBytesCopied)
            else -> throw IOException("模型资源目录为空或不存在: $assetRoot")
        }
    }

    private fun copyAssetDirectory(
        context: Context,
        assetPath: String,
        targetDir: File,
        onBytesCopied: ((Long) -> Unit)? = null
    ) {
        val assetManager = context.assets
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            targetDir.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        onBytesCopied?.invoke(read.toLong())
                    }
                }
            }
            return
        }
        targetDir.mkdirs()
        for (child in children) {
            val childAssetPath = if (assetPath.isBlank()) child else "$assetPath/$child"
            val childTarget = File(targetDir, child)
            copyAssetDirectory(context, childAssetPath, childTarget, onBytesCopied)
        }
    }
}
