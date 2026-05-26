package com.wenjh.fanyiapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class HyMtModelManager(private val context: Context) {
    private val prepareMutex = Mutex()
    private var state: EngineState = EngineState.Idle

    val modelFileName: String = "Hy-MT1.5-1.8B-1.25bit.gguf"
    private val assetPath = "hy_mt/$modelFileName"
    private val installDir by lazy { File(context.filesDir, "hy_mt") }
    val installedModelFile: File by lazy { File(installDir, modelFileName) }

    fun currentState(): EngineState = state

    suspend fun prepareIfNeeded(onProgress: ((PreparationProgress) -> Unit)? = null): PreparationResult =
        prepareMutex.withLock {
            withContext(Dispatchers.IO) {
                if (installedModelFile.exists() && installedModelFile.length() > 0L) {
                    state = EngineState.Ready
                    return@withContext PreparationResult(true, "Hy-MT 离线模型已就绪")
                }

                state = EngineState.Preparing("正在检查 Hy-MT 离线模型", null)
                installDir.mkdirs()

                val totalBytes = runCatching { context.assets.openFd(assetPath).length }
                    .getOrElse { -1L }

                context.assets.open(assetPath).use { input ->
                    FileOutputStream(installedModelFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val progress = PreparationProgress(
                                message = if (totalBytes > 0L) {
                                    "正在解包 Hy-MT 离线模型（${((copied * 100L) / totalBytes).toInt().coerceIn(0, 100)}%）"
                                } else {
                                    "正在解包 Hy-MT 离线模型"
                                },
                                copiedBytes = copied,
                                totalBytes = totalBytes
                            )
                            state = EngineState.Preparing(progress.message, progress.percent)
                            onProgress?.invoke(progress)
                        }
                        output.fd.sync()
                        if (!installedModelFile.exists() || installedModelFile.length() <= 0L) {
                            state = EngineState.Error("Hy-MT 离线模型解包失败")
                            return@withContext PreparationResult(false, "Hy-MT 离线模型解包失败")
                        }
                        state = EngineState.Ready
                        PreparationResult(
                            ready = true,
                            message = "Hy-MT 离线模型已解包完成",
                            copiedBytes = copied,
                            totalBytes = if (totalBytes > 0L) totalBytes else null
                        )
                    }
                }
            }
        }
}
