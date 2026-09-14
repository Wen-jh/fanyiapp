package com.wenjh.fanyiapp

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内置离线语音合成（sherpa-onnx + Piper VITS 模型）。
 *
 * 中文 / 英文使用随 APK 一起分发的离线模型，不依赖系统 TTS 引擎、
 * 不依赖 Google 服务、不需要联网，因此换任何一台手机都能正常朗读。
 *
 * 其他语言由调用方回退到系统 TTS（见 ConversationTranslationActivity）。
 */
class OfflineTtsEngine private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "OfflineTtsEngine"

        /** assets 下的模型根目录 */
        private const val ASSET_ROOT = "tts"

        /** 释放完成标记，换模型后改版本号可强制重新释放 */
        private const val MARKER_FILE = ".ready_v1"

        /** 语言代码 -> assets 下的模型子目录 */
        private val MODEL_DIRS: Map<String, String> = mapOf(
            "zh" to "zh",
            "en" to "en",
        )

        @Volatile
        private var shared: OfflineTtsEngine? = null

        fun get(context: Context): OfflineTtsEngine =
            shared ?: synchronized(this) {
                shared ?: OfflineTtsEngine(context.applicationContext).also { shared = it }
            }

        /** 该语言是否有内置离线语音 */
        fun supports(languageCode: String): Boolean = MODEL_DIRS.containsKey(languageCode)

        /** 内置离线语音覆盖的语言代码 */
        fun supportedLanguages(): Set<String> = MODEL_DIRS.keys
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparing = AtomicBoolean(false)

    /** 模型文件已释放到私有目录 */
    @Volatile
    var prepared: Boolean = false
        private set

    /** 准备失败原因，null 表示未失败 */
    @Volatile
    var prepareError: String? = null
        private set

    /** 释放进度 0-100 */
    @Volatile
    var prepareProgress: Int = 0
        private set

    /** 是否正在释放模型文件 */
    val isPreparing: Boolean
        get() = preparing.get()

    private val engines = mutableMapOf<String, OfflineTts>()

    private val playGeneration = AtomicInteger(0)

    @Volatile
    private var activeTrack: AudioTrack? = null

    /**
     * 首次调用时把 assets 里的模型释放到 filesDir（只做一次），
     * 之后每次启动都只是检查标记文件，开销极小。
     */
    fun prepare(onProgress: ((Int) -> Unit)? = null) {
        if (prepared || !preparing.compareAndSet(false, true)) return
        prepareError = null
        scope.launch {
            try {
                val root = File(appContext.filesDir, ASSET_ROOT)
                val marker = File(root, MARKER_FILE)
                if (!marker.exists()) {
                    if (root.exists()) root.deleteRecursively()
                    if (!root.mkdirs()) {
                        throw IllegalStateException("无法创建语音模型目录")
                    }
                    val total = countAssets(ASSET_ROOT).coerceAtLeast(1)
                    val copied = intArrayOf(0)
                    copyAssetTree(ASSET_ROOT, root) {
                        copied[0]++
                        val percent = (copied[0] * 100 / total).coerceIn(0, 100)
                        prepareProgress = percent
                        onProgress?.invoke(percent)
                    }
                    marker.writeText("ready")
                }
                prepareProgress = 100
                prepared = true
                onProgress?.invoke(100)
                Log.i(TAG, "离线语音模型已就绪: ${root.absolutePath}")
            } catch (error: Throwable) {
                prepareError = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "释放离线语音模型失败", error)
            } finally {
                preparing.set(false)
            }
        }
    }

    /**
     * 用内置离线模型朗读。返回 false 表示当前不可用（未就绪 / 不支持该语言），
     * 调用方应回退到系统 TTS。
     */
    fun speak(
        text: String,
        languageCode: String,
        onFinished: (() -> Unit)? = null,
    ): Boolean {
        val dirName = MODEL_DIRS[languageCode] ?: return false
        if (!prepared) return false
        val content = text.trim()
        if (content.isBlank()) return false

        val generation = playGeneration.incrementAndGet()
        haltPlayback()

        scope.launch {
            try {
                val engine = ensureEngine(languageCode, dirName)
                if (engine == null) {
                    Log.w(TAG, "离线引擎不可用: $languageCode")
                    return@launch
                }
                if (playGeneration.get() != generation) return@launch

                val sampleRate = engine.sampleRate()
                val track = createAudioTrack(sampleRate)
                if (track == null) {
                    Log.w(TAG, "AudioTrack 创建失败")
                    return@launch
                }
                activeTrack = track
                track.play()

                val config = GenerationConfig(speed = 1.0f, sid = 0)
                engine.generateWithConfigAndCallback(content, config) { samples ->
                    if (playGeneration.get() != generation) {
                        0
                    } else {
                        runCatching {
                            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        }
                        1
                    }
                }

                runCatching { track.stop() }
                runCatching { track.release() }
                if (activeTrack === track) activeTrack = null
            } catch (error: Throwable) {
                Log.e(TAG, "离线朗读失败", error)
                activeTrack?.let { runCatching { it.release() } }
                activeTrack = null
            } finally {
                if (playGeneration.get() == generation) onFinished?.invoke()
            }
        }
        return true
    }

    /** 停止当前朗读 */
    fun stop() {
        playGeneration.incrementAndGet()
        haltPlayback()
    }

    /** 状态描述，便于界面展示 */
    fun statusText(): String = when {
        prepared -> "内置语音已就绪"
        prepareError != null -> "内置语音不可用：$prepareError"
        preparing.get() -> "正在准备内置语音（$prepareProgress%）"
        else -> "内置语音未初始化"
    }

    private fun haltPlayback() {
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    private fun ensureEngine(languageCode: String, dirName: String): OfflineTts? {
        engines[languageCode]?.let { return it }
        synchronized(engines) {
            engines[languageCode]?.let { return it }
            val dir = File(File(appContext.filesDir, ASSET_ROOT), dirName)
            if (!dir.isDirectory) return null
            val engine = when (languageCode) {
                "zh" -> createChineseEngine(dir)
                "en" -> createEnglishEngine(dir)
                else -> null
            } ?: return null
            engines[languageCode] = engine
            return engine
        }
    }

    /** 中文 Piper 模型：依赖 lexicon.txt + tokens.txt + 三个文本正则 fst */
    private fun createChineseEngine(dir: File): OfflineTts? = runCatching {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, "zh_CN-xiao_ya-medium.onnx").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    lexicon = File(dir, "lexicon.txt").absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = listOf("phone.fst", "number.fst", "date.fst")
                .joinToString(",") { File(dir, it).absolutePath },
            maxNumSentences = 1,
        )
        OfflineTts(config = config)
    }.onFailure { Log.e(TAG, "创建中文离线引擎失败", it) }.getOrNull()

    /** 英文 Piper 模型：依赖 tokens.txt + espeak-ng-data */
    private fun createEnglishEngine(dir: File): OfflineTts? = runCatching {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, "en_US-lessac-medium.onnx").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        OfflineTts(config = config)
    }.onFailure { Log.e(TAG, "创建英文离线引擎失败", it) }.getOrNull()

    private fun createAudioTrack(sampleRate: Int): AudioTrack? = runCatching {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)

        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }.onFailure { Log.e(TAG, "创建 AudioTrack 失败", it) }.getOrNull()

    private fun countAssets(assetPath: String): Int {
        val children = listAsset(assetPath)
        if (children.isEmpty()) return 1
        return children.sumOf { countAssets("$assetPath/$it") }
    }

    private fun copyAssetTree(assetPath: String, target: File, onFileCopied: () -> Unit) {
        val assets: AssetManager = appContext.assets
        val children = listAsset(assetPath)
        if (children.isEmpty()) {
            // assets.list() 对文件和空目录都返回空，这里先按文件处理，
            // 打开失败说明是空目录，直接建目录即可（空目录不会被打进 APK）。
            val copiedAsFile = runCatching {
                target.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                true
            }.getOrDefault(false)
            if (copiedAsFile) {
                onFileCopied()
            } else {
                target.mkdirs()
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(target, child), onFileCopied)
        }
    }

    private fun listAsset(assetPath: String): List<String> =
        runCatching { appContext.assets.list(assetPath)?.toList().orEmpty() }
            .getOrDefault(emptyList())
}
