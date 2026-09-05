package com.wenjh.fanyiapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

class SubtitleOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.wenjh.fanyiapp.action.START"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"

        private const val CHANNEL_ID = "subtitle_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val FINAL_TRANSLATION_DEBOUNCE_MS = 100L
        private const val IMMEDIATE_FINAL_TRANSLATION_LENGTH = 20
        private const val MAX_SPEAK_LENGTH = 160

        fun shouldApplyPolishedResult(
            finalToken: Long,
            sourceText: String,
            currentSourceText: String,
            expectedMlKitTranslation: String,
            currentDisplayedTranslation: String,
            latestFinalToken: Long
        ): Boolean {
            if (finalToken != latestFinalToken) return false
            if (sourceText != currentSourceText) return false
            return currentDisplayedTranslation == expectedMlKitTranslation
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var subtitleText: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var mediaProjection: MediaProjection? = null
    private var translator: Translator? = null
    private var translatorJob: Job? = null
    private var translatorDownloadStatusJob: Job? = null
    private var hyMtEngine: PhotoTranslationEngine? = null
    private var hyMtPrepareJob: Job? = null
    private var hyMtPolishJob: Job? = null
    private var audioLoopJob: Job? = null
    private var bufferedFinalFlushJob: Job? = null
    private var audioSource: PlaybackCaptureAudioSource? = null
    private var voskRecognizer: VoskStreamingRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var lastSpokenTranslation: String = ""
    private var lastSpokenSource: String = ""

    private val translationSegmenter = TranslationSegmenter()
    private val pendingTranslationCoordinator = PendingTranslationCoordinator()
    private var translationPipeline: IncrementalTranslationPipeline? = null
    private val smoothRenderer = SmoothSubtitleRenderer()

    private var inputModeLabel: String = "检测中"
    private var playbackCaptureInitiallyAvailable: Boolean = false
    private var lastOriginalText: String = ""
    private var lastTranslatedText: String = ""
    private var captureState: String = "等待初始化"
    private var modelState: String = "未开始"
    private var recognitionState: String = "未开始"
    private var translationState: String = "未开始"
    private var isTranslatorReady: Boolean = false
    private var lastLevelHint: String = "音量: 未知"
    private var latestFinalToken: Long = 0L
    private var latestDisplayedFinalSource: String = ""
    private var latestDisplayedFinalMlKit: String = ""
    private var lastSubmittedTranslationText: String = ""
    private var lastSubmittedTranslationWasProvisional: Boolean = false
    private var bufferedFinalText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在初始化链路"))

        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val dataIntent = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)
        if (resultCode != 0 && dataIntent != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)
        }

        showOverlay()
        initializeTextToSpeech()
        serviceScope.launch { bootstrapPipeline() }
        return START_NOT_STICKY
    }

    private suspend fun bootstrapPipeline() {
        determineInputMode()
        renderPipeline()
        val availability = if (hasAnyAudioInput()) InputAvailability.AVAILABLE else InputAvailability.UNAVAILABLE
        SubtitlePipelineBootstrapPlanner.planFor(availability).forEach { step ->
            when (step) {
                BootstrapStep.PREPARE_AUDIO_SOURCE -> prepareAudioSource()
                BootstrapStep.PREPARE_ASR -> prepareLocalAsr()
                BootstrapStep.START_RECOGNITION -> startRecognitionLoop()
                BootstrapStep.PREPARE_TRANSLATOR -> serviceScope.launch { prepareTranslator() }
            }
            renderPipeline()
        }
    }

    private fun determineInputMode() {
        playbackCaptureInitiallyAvailable = PlaybackCaptureAudioSource.canAttemptPlaybackCapture(mediaProjection)
        inputModeLabel = when {
            playbackCaptureInitiallyAvailable -> AudioInputMode.PLAYBACK_CAPTURE.label
            PlaybackCaptureAudioSource.microphoneFallbackAvailable() -> AudioInputMode.MICROPHONE.label
            else -> "不可用"
        }
        captureState = when (inputModeLabel) {
            AudioInputMode.PLAYBACK_CAPTURE.label -> "检测到播放捕获能力，准备建立 PCM 采集"
            AudioInputMode.MICROPHONE.label -> "播放捕获不可用，准备回退到麦克风本地识别"
            else -> "设备未通过音频输入检测"
        }
        modelState = "等待本地识别模型初始化"
        recognitionState = "等待本地识别启动"
        translationState = "等待翻译模型初始化"
        pushNotification("$inputModeLabel / $captureState")
    }

    private fun hasAnyAudioInput(): Boolean {
        return PlaybackCaptureAudioSource.canAttemptPlaybackCapture(mediaProjection) ||
            PlaybackCaptureAudioSource.microphoneFallbackAvailable()
    }

    private fun prepareAudioSource() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            captureState = "缺少录音权限"
            return
        }

        runCatching {
            audioSource?.stop()
            audioSource?.release()
            audioSource = PlaybackCaptureAudioSource.create(mediaProjection)
        }.onSuccess {
            val source = audioSource ?: return@onSuccess
            inputModeLabel = source.mode.label
            captureState = when {
                playbackCaptureInitiallyAvailable && source.mode == AudioInputMode.MICROPHONE ->
                    "播放捕获初始化失败，已切换到麦克风本地识别"
                source.mode == AudioInputMode.PLAYBACK_CAPTURE -> "播放音频捕获已就绪"
                else -> "麦克风本地识别已就绪"
            }
        }.onFailure { error ->
            captureState = "音频源初始化失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private suspend fun prepareLocalAsr() {
        modelState = ModelPreparationState.PREPARING.statusText
        recognitionState = "等待本地识别启动"
        renderPipeline()

        val result = withContext(Dispatchers.IO) { VoskModelManager().prepareModel(this@SubtitleOverlayService) }
        result.onSuccess { modelDir ->
            runCatching {
                voskRecognizer?.close()
                val rate = audioSource?.sampleRate?.toFloat() ?: PlaybackCaptureAudioSource.DEFAULT_SAMPLE_RATE.toFloat()
                voskRecognizer = VoskStreamingRecognizer(modelDir, rate)
                modelState = ModelPreparationState.READY.statusText
            }.onFailure { error ->
                modelState = "${ModelPreparationState.FAILED.statusText}：${error.message ?: error.javaClass.simpleName}"
                recognitionState = "本地识别器未就绪"
            }
        }.onFailure { error ->
            modelState = "${ModelPreparationState.FAILED.statusText}：${error.message ?: error.javaClass.simpleName}"
            recognitionState = "本地识别器未就绪"
        }
    }

    private suspend fun prepareTranslator() {
        isTranslatorReady = false
        translatorDownloadStatusJob?.cancel()
        translationState = "正在准备翻译引擎"
        renderPipeline()

        // 优先尝试 Hy-MT 离线翻译（不需要网络）
        val hyMtResult = withContext(Dispatchers.IO) {
            runCatching {
                val engine = HyMtTranslationEngine(applicationContext)
                val result = engine.prepareIfNeeded()
                if (result.ready) {
                    hyMtEngine = engine
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
        }

        if (hyMtResult) {
            isTranslatorReady = true
            translationState = "Hy-MT 离线翻译已就绪"
            renderPipeline()
            setupHyMtPipeline()
            return
        }

        // Hy-MT 不可用，回退到 ML Kit
        translationState = "Hy-MT 不可用，正在准备 ML Kit 翻译模型"
        renderPipeline()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()

        translatorJob?.cancel()
        translator?.close()
        translator = com.google.mlkit.nl.translate.Translation.getClient(options)

        val startedAt = SystemClock.elapsedRealtime()
        fun elapsedSeconds(): Long = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).coerceAtLeast(0L)

        fun startDownloadStatusTicker() {
            translatorDownloadStatusJob?.cancel()
            translatorDownloadStatusJob = serviceScope.launch {
                while (isActive && !isTranslatorReady) {
                    translationState = "翻译模型下载中（已耗时 ${elapsedSeconds()}s）"
                    renderPipeline()
                    delay(1000)
                }
            }
        }

        fun stopDownloadStatusTicker() {
            translatorDownloadStatusJob?.cancel()
            translatorDownloadStatusJob = null
        }

        try {
            translationState = "开始下载翻译模型"
            renderPipeline()
            startDownloadStatusTicker()
            translator?.downloadModelIfNeeded()?.await()
            stopDownloadStatusTicker()
            translationState = "翻译模型已下载，正在初始化"
            renderPipeline()
            isTranslatorReady = true
            translationState = "ML Kit 翻译已就绪"
        } catch (error: Throwable) {
            stopDownloadStatusTicker()
            isTranslatorReady = false
            translationState = "翻译模型下载失败：${error.message ?: error.javaClass.simpleName}"
        }

        if (isTranslatorReady) {
            setupMlKitPipeline()
            pendingTranslationCoordinator.consumeReady()?.let { pending ->
                translateRecognizedText(pending.text, pending.provisional)
                return
            }
        }
        renderPipeline()
    }

    private fun setupHyMtPipeline() {
        val engine = hyMtEngine ?: return
        translationPipeline?.release()
        smoothRenderer.reset()
        translationPipeline = IncrementalTranslationPipeline(
            translateFn = { text ->
                val result = engine.translate(
                    text = text,
                    sourceLanguage = "Japanese",
                    targetLanguage = "Chinese"
                )
                result.text
            }
        ).apply {
            onTranslationUpdate = { source, translated, isPartial ->
                serviceScope.launch {
                    if (isPartial) {
                        smoothRenderer.onPartialUpdate(source, translated)
                    } else {
                        smoothRenderer.onFinalUpdate(source, translated)
                    }
                    val (displayOrig, displayTrans) = smoothRenderer.getDisplayText()
                    lastOriginalText = displayOrig
                    lastTranslatedText = displayTrans
                    if (!isPartial) speakTranslation(displayTrans, source)
                    translationState = if (isPartial) "实时翻译中(Hy-MT)" else "翻译完成(Hy-MT)"
                    renderPipeline()
                }
            }
        }
    }

    private fun setupMlKitPipeline() {
        val currentTranslator = translator ?: return
        translationPipeline?.release()
        smoothRenderer.reset()
        translationPipeline = IncrementalTranslationPipeline(
            translateFn = { text ->
                currentTranslator.translate(text).await().trim()
            }
        ).apply {
            onTranslationUpdate = { source, translated, isPartial ->
                serviceScope.launch {
                    if (isPartial) {
                        smoothRenderer.onPartialUpdate(source, translated)
                    } else {
                        smoothRenderer.onFinalUpdate(source, translated)
                    }
                    val (displayOrig, displayTrans) = smoothRenderer.getDisplayText()
                    lastOriginalText = displayOrig
                    lastTranslatedText = displayTrans
                    if (!isPartial) speakTranslation(displayTrans, source)
                    translationState = if (isPartial) "实时翻译中(ML Kit)" else "翻译完成(ML Kit)"
                    renderPipeline()
                }
            }
        }
    }

    private fun ensureHyMtPreparation() {
        val existingEngine = hyMtEngine
        if (existingEngine != null && existingEngine.currentState() is EngineState.Ready) {
            return
        }
        if (hyMtPrepareJob?.isActive == true) {
            return
        }
        val engine = existingEngine ?: HyMtTranslationEngine(applicationContext).also { hyMtEngine = it }
        hyMtPrepareJob = serviceScope.launch {
            runCatching { engine.prepareIfNeeded() }
        }
    }

    private fun startRecognitionLoop() {
        val source = audioSource
        val recognizer = voskRecognizer
        if (source == null || recognizer == null) {
            if (source == null) captureState = "音频源未就绪"
            if (recognizer == null) recognitionState = "本地识别器未就绪"
            renderPipeline()
            return
        }

        audioLoopJob?.cancel()
        audioLoopJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(3200)

            runCatching { source.start() }
                .onFailure { error ->
                    serviceScope.launch {
                        captureState = "音频源启动失败：${error.message ?: error.javaClass.simpleName}"
                        renderPipeline()
                    }
                    return@launch
                }

            serviceScope.launch {
                captureState = when (source.mode) {
                    AudioInputMode.PLAYBACK_CAPTURE -> "播放音频捕获中"
                    AudioInputMode.MICROPHONE -> "麦克风音频采集中"
                }
                recognitionState = "本地识别中（实时）"
                renderPipeline()
            }

            try {
                while (isActive) {
                    val read = source.read(buffer)
                    if (read <= 0) continue

                    val level = PlaybackCaptureAudioSource.normalizePcmLevel(buffer, read)
                    val levelHint = "音量: ${level.toInt()}%"
                    val capture = when (source.mode) {
                        AudioInputMode.PLAYBACK_CAPTURE -> "播放音频捕获中"
                        AudioInputMode.MICROPHONE -> "麦克风音频采集中"
                    }
                    serviceScope.launch {
                        lastLevelHint = levelHint
                        captureState = capture
                        renderPipeline(levelOverride = levelHint)
                    }

                    when (val event = recognizer.accept(buffer, read)) {
                        is AsrEvent.Partial -> serviceScope.launch {
                            val normalizedPartial = normalizeSubtitleText(event.text)
                            val startsNewSentence = lastOriginalText.isNotBlank() &&
                                IncrementalTranslationPipeline.isNewSentence(lastOriginalText, normalizedPartial)
                            if (startsNewSentence) {
                                bufferedFinalText = ""
                                lastOriginalText = ""
                                lastTranslatedText = ""
                                smoothRenderer.reset()
                            }

                            // 如果新 partial 与 buffered 内容完全不同（新句子），清空旧缓冲
                            if (startsNewSentence || (bufferedFinalText.isNotBlank() &&
                                !event.text.startsWith(bufferedFinalText.take(10)) &&
                                !bufferedFinalText.take(10).startsWith(event.text.take(10)))) {
                                bufferedFinalText = ""
                                smoothRenderer.reset()
                            }

                            lastOriginalText = if (bufferedFinalText.isBlank()) {
                                event.text
                            } else {
                                mergeRecognizedText(bufferedFinalText, event.text)
                            }
                            recognitionState = "本地识别中（实时）"

                            // 新管道：直接提交 partial 到增量翻译
                            if (isTranslatorReady && translationPipeline != null) {
                                translationPipeline?.submitPartial(lastOriginalText)
                                translationState = "实时翻译中"
                                renderPipeline(levelOverride = levelHint)
                                return@launch
                            }

                            if (bufferedFinalText.isNotBlank()) {
                                translationState = "正在等待更完整语句"
                                renderPipeline(levelOverride = levelHint)
                                return@launch
                            }

                            val candidate = translationSegmenter.onPartial(event.text, SystemClock.elapsedRealtime())
                            if (candidate != null) {
                                translateRecognizedText(candidate, provisional = true)
                            } else {
                                if (isTranslatorReady) {
                                    translationState = "等待更稳定语句后翻译"
                                }
                                renderPipeline(levelOverride = levelHint)
                            }
                        }

                        is AsrEvent.Final -> serviceScope.launch {
                            recognitionState = "本地识别完成"

                            // 新管道：直接提交 final 到增量翻译
                            if (isTranslatorReady && translationPipeline != null) {
                                translationPipeline?.submitFinal(event.text)
                                bufferedFinalText = ""
                                renderPipeline(levelOverride = levelHint)
                                return@launch
                            }

                            translationSegmenter.onFinal(event.text)?.let {
                                queueFinalTranslation(it, levelHint)
                            } ?: run {
                                if (lastTranslatedText.isBlank()) {
                                    translationState = "等待下一句翻译"
                                }
                                renderPipeline(levelOverride = levelHint)
                            }
                        }

                        null -> Unit
                    }
                }
            } catch (error: Throwable) {
                serviceScope.launch {
                    recognitionState = "本地识别异常：${error.message ?: error.javaClass.simpleName}"
                    renderPipeline()
                }
            } finally {
                recognizer.flushFinal()?.let { finalEvent ->
                    serviceScope.launch {
                        recognitionState = "本地识别完成"
                        translationSegmenter.onFinal(finalEvent.text)?.let {
                            queueFinalTranslation(it)
                        } ?: run {
                            if (lastTranslatedText.isBlank()) {
                                translationState = "等待下一句翻译"
                            }
                            renderPipeline()
                        }
                    }
                }
                runCatching { source.stop() }
            }
        }
    }

    private fun queueFinalTranslation(text: String, levelHintOverride: String? = null) {
        val normalized = normalizeSubtitleText(text)
        if (normalized.isBlank()) return

        // 新句子开始时清空旧缓冲，防止字幕无限增长
        if (bufferedFinalText.isNotBlank() &&
            !normalized.startsWith(bufferedFinalText.take(10)) &&
            !bufferedFinalText.take(10).startsWith(normalized.take(10))) {
            bufferedFinalText = ""
        }

        bufferedFinalText = mergeRecognizedText(bufferedFinalText, normalized)
        lastOriginalText = bufferedFinalText
        translationState = "正在等待更完整语句"
        renderPipeline(levelOverride = levelHintOverride)

        if (lastSubmittedTranslationWasProvisional && translatorJob?.isActive == true) {
            translatorJob?.cancel()
        }

        bufferedFinalFlushJob?.cancel()
        val delayMs = if (
            endsWithSentenceBoundary(bufferedFinalText) ||
            bufferedFinalText.length >= IMMEDIATE_FINAL_TRANSLATION_LENGTH
        ) {
            120L
        } else {
            FINAL_TRANSLATION_DEBOUNCE_MS
        }
        bufferedFinalFlushJob = serviceScope.launch {
            delay(delayMs)
            flushBufferedFinalTranslation(levelHintOverride)
        }
    }

    private fun flushBufferedFinalTranslation(levelHintOverride: String? = null) {
        val candidate = normalizeSubtitleText(bufferedFinalText)
        bufferedFinalText = ""
        bufferedFinalFlushJob = null
        if (candidate.isBlank()) {
            renderPipeline(levelOverride = levelHintOverride)
            return
        }
        translateRecognizedText(candidate, provisional = false)
    }

    private fun translateRecognizedText(text: String, provisional: Boolean) {
        val normalizedText = normalizeSubtitleText(text)
        if (normalizedText.isBlank()) {
            return
        }
        if (shouldSkipTranslation(normalizedText, provisional)) {
            if (provisional) {
                translationState = "等待更完整语句后翻译"
            }
            renderPipeline()
            return
        }

        val currentTranslator = translator
        if (!isTranslatorReady || currentTranslator == null) {
            pendingTranslationCoordinator.rememberPending(normalizedText, provisional)
            translationState = "翻译器未就绪，先显示原文"
            if (!provisional) {
                lastTranslatedText = ""
            }
            renderPipeline()
            return
        }

        if (pendingTranslationCoordinator.hasInFlight()) {
            pendingTranslationCoordinator.rememberPending(normalizedText, provisional)
            if (!provisional && lastSubmittedTranslationWasProvisional && translatorJob?.isActive == true) {
                translatorJob?.cancel()
            }
            translationState = if (provisional) {
                "等待更完整语句后接力翻译"
            } else {
                "等待当前翻译完成后接力"
            }
            renderPipeline()
            return
        }

        val finalToken = if (provisional) latestFinalToken else latestFinalToken + 1L
        if (!provisional) {
            latestFinalToken = finalToken
            hyMtPolishJob?.cancel()
            ensureHyMtPreparation()
        }

        pendingTranslationCoordinator.markInFlight(normalizedText, provisional)
        lastSubmittedTranslationText = normalizedText
        lastSubmittedTranslationWasProvisional = provisional

        translatorJob = serviceScope.launch {
            translationState = if (provisional) "正在预测翻译" else "正在翻译"
            renderPipeline()
            try {
                val translated = withContext(Dispatchers.IO) {
                    currentTranslator.translate(normalizedText).await().trim()
                }
                val queuedFollowUp = pendingTranslationCoordinator.peek()
                val suppressProvisionalResult = provisional && queuedFollowUp != null &&
                    (!queuedFollowUp.provisional || queuedFollowUp.text.length >= normalizedText.length)

                if (translated.isNotBlank()) {
                    if (suppressProvisionalResult) {
                        translationState = "已收到更完整语句，等待更完整翻译"
                    } else {
                        lastTranslatedText = translated
                        translationState = if (provisional) {
                            "实时翻译已更新"
                        } else {
                            latestDisplayedFinalSource = normalizedText
                            latestDisplayedFinalMlKit = translated
                            launchFinalPolish(
                                sourceText = normalizedText,
                                mlKitTranslation = translated,
                                finalToken = finalToken
                            )
                            "翻译完成"
                        }
                    }
                } else {
                    translationState = "翻译完成，但结果为空"
                }
            } catch (_: CancellationException) {
                pendingTranslationCoordinator.requeueInFlight()
                throw CancellationException()
            } catch (error: Throwable) {
                if (!provisional) {
                    lastTranslatedText = ""
                }
                translationState = "翻译失败：${error.message ?: error.javaClass.simpleName}"
            }
            renderPipeline()
            pendingTranslationCoordinator.consumeReadyAfter(normalizedText)?.let { next ->
                translateRecognizedText(next.text, next.provisional)
            }
        }
    }

    private fun normalizeSubtitleText(text: String): String {
        return text
            .replace('\u3000', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[。？！]{2,}"), "。")
            .replace(Regex("[!?！？]{2,}"), "！")
            .replace(Regex("[,.，、]{2,}"), "，")
            .trim()
    }

    private fun shouldSkipTranslation(normalizedText: String, provisional: Boolean): Boolean {
        if (bufferedFinalText.isNotBlank() && provisional) {
            return true
        }
        if (!provisional) {
            return false
        }
        if (normalizedText == lastSubmittedTranslationText && lastSubmittedTranslationWasProvisional) {
            return true
        }
        if (!lastSubmittedTranslationWasProvisional) {
            return normalizedText == latestDisplayedFinalSource ||
                (latestDisplayedFinalSource.startsWith(normalizedText) && latestDisplayedFinalSource != normalizedText)
        }
        if (lastSubmittedTranslationText.isBlank()) {
            return false
        }
        if (!normalizedText.startsWith(lastSubmittedTranslationText)) {
            return false
        }
        return normalizedText.length - lastSubmittedTranslationText.length < 5
    }

    private fun launchFinalPolish(sourceText: String, mlKitTranslation: String, finalToken: Long) {
        val engine = hyMtEngine ?: return
        val state = engine.currentState()
        if (state !is EngineState.Ready) {
            return
        }

        hyMtPolishJob?.cancel()
        hyMtPolishJob = serviceScope.launch {
            if (finalToken != latestFinalToken || sourceText != latestDisplayedFinalSource) {
                return@launch
            }
            translationState = "翻译完成（正在润色）"
            renderPipeline()
            runCatching {
                engine.translate(
                    text = sourceText,
                    sourceLanguage = "Japanese",
                    targetLanguage = "Chinese"
                )
            }.onSuccess { result ->
                val polished = result.text.trim()
                if (!shouldApplyPolishedResult(
                        finalToken = finalToken,
                        sourceText = sourceText,
                        currentSourceText = latestDisplayedFinalSource,
                        expectedMlKitTranslation = mlKitTranslation,
                        currentDisplayedTranslation = lastTranslatedText,
                        latestFinalToken = latestFinalToken
                    )) {
                    return@onSuccess
                }
                if (polished.isNotBlank()) {
                    lastTranslatedText = polished
                    translationState = "翻译完成（已润色）"
                    renderPipeline()
                }
            }.onFailure {
                if (finalToken == latestFinalToken &&
                    sourceText == latestDisplayedFinalSource &&
                    lastTranslatedText == mlKitTranslation
                ) {
                    translationState = "翻译完成"
                    renderPipeline()
                }
            }
        }
    }

    private fun mergeRecognizedText(previous: String, next: String): String {
        val left = normalizeSubtitleText(previous)
        val right = normalizeSubtitleText(next)
        if (left.isBlank()) return right
        if (right.isBlank()) return left
        if (right == left) return left
        if (right.startsWith(left)) return right
        if (left.endsWith(right)) return left
        return if (shouldAppendSpaceBetween(left.last(), right.first())) {
            "$left $right"
        } else {
            left + right
        }
    }

    private fun endsWithSentenceBoundary(text: String): Boolean {
        return text.endsWith("。") || text.endsWith("！") || text.endsWith("？") ||
            text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
    }

    private fun shouldAppendSpaceBetween(previousChar: Char, nextChar: Char): Boolean {
        if (previousChar.isWhitespace() || nextChar.isWhitespace()) return false
        if (isCjk(previousChar) || isCjk(nextChar)) return false
        return true
    }

    private fun isCjk(character: Char): Boolean {
        val block = Character.UnicodeBlock.of(character)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.let { tts ->
                    val languageStatus = tts.setLanguage(java.util.Locale.SIMPLIFIED_CHINESE)
                    textToSpeechReady = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                        languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
                }
            }
        }.also { tts ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) = Unit
            })
        }
    }

    private fun speakTranslation(translation: String, source: String) {
        val text = translation.trim()
        if (textToSpeechReady && text.isNotBlank() && text != lastSpokenTranslation && source != lastSpokenSource && text.length <= MAX_SPEAK_LENGTH) {
            lastSpokenTranslation = text
            lastSpokenSource = source
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "subtitle-$source")
        }
    }


        if (overlayView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_subtitle, null)
        subtitleText = overlayView?.findViewById(R.id.subtitleText)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }

        bindDragGesture()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(overlayView, overlayParams)
        renderPipeline()
    }

    private fun bindDragGesture() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val dragTarget = overlayView ?: return

        dragTarget.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = overlayParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downX
                        val deltaY = event.rawY - downY
                        if (!dragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = startX + deltaX.toInt()
                            params.y = startY + deltaY.toInt()
                            windowManager?.updateViewLayout(overlayView, params)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = dragging
                        dragging = false
                        return wasDragging
                    }
                }
                return false
            }
        })
    }

    private fun renderPipeline(levelOverride: String? = null) {
        val effectiveLevelHint = levelOverride ?: lastLevelHint
        val overlayStatus = SubtitleOverlayFormatter.composeOverlaySubtitle(
            original = lastOriginalText,
            translated = lastTranslatedText,
            translationState = translationState,
            recognitionState = recognitionState
        )
        val notificationStatus = SubtitleOverlayFormatter.composePipeline(
            modeLabel = inputModeLabel,
            captureState = captureState,
            modelState = modelState,
            recognitionState = recognitionState,
            translationState = translationState,
            original = lastOriginalText,
            translated = lastTranslatedText,
            levelHint = effectiveLevelHint
        )
        subtitleText?.text = overlayStatus
        pushNotification(notificationStatus)
    }

    private fun buildNotification(status: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("翻译悬浮窗运行中")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun pushNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "字幕悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        audioLoopJob?.cancel()
        translatorJob?.cancel()
        translatorDownloadStatusJob?.cancel()
        hyMtPrepareJob?.cancel()
        hyMtPolishJob?.cancel()
        bufferedFinalFlushJob?.cancel()
        translationPipeline?.release()
        translationPipeline = null
        voskRecognizer?.close()
        voskRecognizer = null
        audioSource?.stop()
        audioSource?.release()
        audioSource = null
        translator?.close()
        translator = null
        hyMtEngine?.release()
        hyMtEngine = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        textToSpeechReady = false
        mediaProjection?.stop()
        mediaProjection = null
        overlayView?.let { view -> windowManager?.removeView(view) }
        overlayView = null
        subtitleText = null
        overlayParams = null
        windowManager = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
