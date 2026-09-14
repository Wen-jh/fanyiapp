package com.wenjh.fanyiapp

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置离线语音识别（sherpa-onnx + Whisper-tiny 多语言模型）。
 *
 * 与系统 SpeechRecognizer 不同：不依赖设备是否预装语音服务、不需要联网、
 * 不受 Google 服务在国内缺失的影响，因此任何手机都能用。
 *
 * 模型随 APK 分发（assets/asr），首次启动释放到私有目录；
 * 支持 Whisper 覆盖的语言（中/英/日/韩/法/德/西等 90+ 种）。
 */
class OfflineAsrEngine private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "OfflineAsrEngine"

        /** assets 下的识别模型目录 */
        private const val ASSET_ROOT = "asr"

        /** 释放完成标记，换模型后改版本号可强制重新释放 */
        private const val MARKER_FILE = ".ready_v1"

        /** Whisper 要求的采样率 */
        const val SAMPLE_RATE = 16000

        private const val ENCODER_FILE = "tiny-encoder.int8.onnx"
        private const val DECODER_FILE = "tiny-decoder.int8.onnx"
        private const val TOKENS_FILE = "tiny-tokens.txt"

        /** 单次录音硬上限，防止忘记停止而一直占用麦克风 */
        private const val MAX_RECORD_MS = 60_000L

        /** 有效录音下限，太短直接提示重说 */
        private const val MIN_RECORD_MS = 350L

        /** Whisper 支持的语言代码（其余走自动检测） */
        private val WHISPER_LANGUAGES = setOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl",
            "ar", "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro",
            "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy",
            "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu",
            "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km",
            "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am", "yi", "lo",
            "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg",
            "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue",
        )

        @Volatile
        private var shared: OfflineAsrEngine? = null

        fun get(context: Context): OfflineAsrEngine =
            shared ?: synchronized(this) {
                shared ?: OfflineAsrEngine(context.applicationContext).also { shared = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparing = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)

    /** 模型文件已释放到私有目录 */
    @Volatile
    var prepared: Boolean = false
        private set

    /** 准备失败原因 */
    @Volatile
    var prepareError: String? = null
        private set

    /** 释放进度 0-100 */
    @Volatile
    var prepareProgress: Int = 0
        private set

    val isPreparing: Boolean
        get() = preparing.get()

    val isRecording: Boolean
        get() = recording.get()

    private var currentLanguage: String? = null
    private var currentRecognizer: OfflineRecognizer? = null

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var recordStartedAt = 0L

    private val chunks = ArrayList<FloatArray>()

    /**
     * 首次调用时把 assets 中的模型释放到 filesDir（只做一次）。
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
                        throw IllegalStateException("无法创建识别模型目录")
                    }
                    val total = countAssets(ASSET_ROOT).coerceAtLeast(1)
                    val copied = intArrayOf(0)
                    copyAssetTree(ASSET_ROOT, root) {
                        copied[0]++
                        val percent = (copied[0] * 100 / total).coerceIn(0, 100)
                        prepareProgress = percent
                        onProgress?.invoke(percent)
                    }
                    val missing = listOf(ENCODER_FILE, DECODER_FILE, TOKENS_FILE)
                        .filterNot { File(root, it).exists() }
                    if (missing.isNotEmpty()) {
                        throw IllegalStateException("识别模型文件缺失：${missing.joinToString()}")
                    }
                    marker.writeText("ready")
                }
                prepareProgress = 100
                prepared = true
                onProgress?.invoke(100)
                Log.i(TAG, "离线识别模型已就绪: ${root.absolutePath}")
            } catch (error: Throwable) {
                prepareError = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "释放离线识别模型失败", error)
            } finally {
                preparing.set(false)
            }
        }
    }

    /** 提前加载指定语言的识别器，避免用户首次说话时才等待模型加载 */
    fun warmUp(languageCode: String) {
        if (!prepared) return
        scope.launch { runCatching { recognizerFor(languageCode) } }
    }

    /** 开始录音。返回 false 表示无法录音（模型未就绪 / 设备被占用）。 */
    fun startRecording(): Boolean {
        if (!prepared) return false
        if (!recording.compareAndSet(false, true)) return false
        synchronized(chunks) { chunks.clear() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            recording.set(false)
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE / 2)
        val record = createAudioRecord(bufferSize) ?: run {
            recording.set(false)
            return false
        }
        audioRecord = record
        recordStartedAt = SystemClock.elapsedRealtime()
        return try {
            record.startRecording()
            captureJob = scope.launch(Dispatchers.Default) { captureLoop(record) }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "启动录音失败", error)
            recording.set(false)
            releaseAudioRecord()
            false
        }
    }

    /**
     * 停止录音并识别。结果通过 [onResult] / [onError] 在主线程回调。
     */
    fun stopAndRecognize(
        languageCode: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val wasRecording = recording.getAndSet(false)
        runCatching { audioRecord?.stop() }
        val elapsed = if (wasRecording) SystemClock.elapsedRealtime() - recordStartedAt else 0L
        val samples = synchronized(chunks) { mergeChunks() }
        releaseAudioRecord()

        if (samples.isEmpty()) {
            onError("没有录到声音，请再试一次")
            return
        }
        if (elapsed in 1 until MIN_RECORD_MS || samples.size < SAMPLE_RATE / 5) {
            onError("说话时间太短，请按住多说几个字")
            return
        }

        scope.launch {
            val outcome = runCatching { recognize(samples, languageCode) }
            withContext(Dispatchers.Main) {
                outcome
                    .onSuccess { text ->
                        if (text.isBlank()) {
                            onError("没有识别到内容，请再说一次")
                        } else {
                            onResult(text)
                        }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "离线识别失败", error)
                        onError("识别失败：${error.message ?: error.javaClass.simpleName}")
                    }
            }
        }
    }

    /** 放弃当前录音（不识别） */
    fun cancelRecording() {
        recording.set(false)
        runCatching { audioRecord?.stop() }
        captureJob = null
        synchronized(chunks) { chunks.clear() }
        releaseAudioRecord()
    }

    fun release() {
        cancelRecording()
        synchronized(this) {
            currentRecognizer?.let { runCatching { it.release() } }
            currentRecognizer = null
            currentLanguage = null
        }
    }

    fun statusText(): String = when {
        prepared -> "离线语音识别已就绪"
        prepareError != null -> "离线语音识别不可用：$prepareError"
        preparing.get() -> "正在准备离线语音识别（$prepareProgress%）"
        else -> "离线语音识别未初始化"
    }

    private fun captureLoop(record: AudioRecord) {
        val buffer = ShortArray(SAMPLE_RATE / 10)
        try {
            while (recording.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val frame = FloatArray(read)
                    for (i in 0 until read) {
                        frame[i] = buffer[i] / 32768f
                    }
                    synchronized(chunks) { chunks.add(frame) }
                } else if (read < 0) {
                    break
                }
                if (SystemClock.elapsedRealtime() - recordStartedAt >= MAX_RECORD_MS) {
                    recording.set(false)
                    runCatching { record.stop() }
                    break
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "录音读取异常", error)
        }
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val record = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            runCatching { record.release() }
        }
        return null
    }

    private fun releaseAudioRecord() {
        val record = audioRecord ?: return
        audioRecord = null
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    private fun mergeChunks(): FloatArray {
        var total = 0
        for (chunk in chunks) total += chunk.size
        val merged = FloatArray(total)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.size)
            offset += chunk.size
        }
        return merged
    }

    private fun recognize(samples: FloatArray, languageCode: String): String {
        val recognizer = recognizerFor(languageCode)
            ?: throw IllegalStateException("识别引擎未就绪，请稍后重试")
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val raw = recognizer.getResult(stream).text.trim()
            normalize(raw, languageCode)
        } finally {
            runCatching { stream.release() }
        }
    }

    /** Whisper 识别中文默认输出繁体，这里转成简体 */
    private fun normalize(text: String, languageCode: String): String {
        if (text.isEmpty()) return text
        val isChinese = languageCode.startsWith("zh") || languageCode == "yue"
        return if (isChinese) ChineseTextNormalizer.toSimplified(appContext, text) else text
    }

    private fun recognizerFor(languageCode: String): OfflineRecognizer? {
        val language = whisperLanguageFor(languageCode)
        if (currentLanguage == language) {
            currentRecognizer?.let { return it }
        }
        synchronized(this) {
            if (currentLanguage == language) {
                currentRecognizer?.let { return it }
            }
            currentRecognizer?.let { runCatching { it.release() } }
            currentRecognizer = null
            currentLanguage = null
            val created = createRecognizer(language) ?: return null
            currentRecognizer = created
            currentLanguage = language
            return created
        }
    }

    private fun createRecognizer(language: String): OfflineRecognizer? {
        val dir = File(appContext.filesDir, ASSET_ROOT)
        if (!dir.isDirectory) return null
        return runCatching {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, ENCODER_FILE).absolutePath,
                        decoder = File(dir, DECODER_FILE).absolutePath,
                        language = language,
                        task = "transcribe",
                    ),
                    tokens = File(dir, TOKENS_FILE).absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            OfflineRecognizer(config = config)
        }.onFailure { Log.e(TAG, "创建离线识别引擎失败($language)", it) }.getOrNull()
    }

    private fun whisperLanguageFor(code: String): String {
        val normalized = when (code) {
            "zh-Hant" -> "zh"
            else -> code
        }
        return if (normalized in WHISPER_LANGUAGES) normalized else "auto"
    }

    private fun countAssets(assetPath: String): Int {
        val children = listAsset(assetPath)
        if (children.isEmpty()) return 1
        return children.sumOf { countAssets("$assetPath/$it") }
    }

    private fun copyAssetTree(assetPath: String, target: File, onFileCopied: () -> Unit) {
        val assets: AssetManager = appContext.assets
        val children = listAsset(assetPath)
        if (children.isEmpty()) {
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
