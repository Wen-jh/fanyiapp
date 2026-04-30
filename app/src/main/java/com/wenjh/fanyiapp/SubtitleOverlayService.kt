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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
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
        const val EXTRA_ENABLE_AUDIO_DUMP = "extra_enable_audio_dump"
        const val EXTRA_AUDIO_DUMP_WAV = "extra_audio_dump_wav"
        private const val CHANNEL_ID = "subtitle_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var subtitleText: TextView? = null
    private var detailsToggleButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var mediaProjection: MediaProjection? = null
    private var translator: Translator? = null
    private var translatorJob: Job? = null
    private var translatorDownloadStatusJob: Job? = null
    private var audioLoopJob: Job? = null
    private var audioSource: PlaybackCaptureAudioSource? = null
    private var voskRecognizer: VoskStreamingRecognizer? = null
    private var debugDumpWriter: AudioDebugDumpWriter? = null
    private val translationSegmenter = TranslationSegmenter()
    private val pendingTranslationCoordinator = PendingTranslationCoordinator()

    private var inputModeLabel: String = "检测中"
    private var playbackCaptureInitiallyAvailable: Boolean = false
    private var lastOriginalText: String = ""
    private var lastTranslatedText: String = ""
    private var captureState: String = "等待初始化"
    private var modelState: String = "未开始"
    private var recognitionState: String = "未开始"
    private var translationState: String = "未开始"
    private var dumpState: String = "未启用"
    private var isTranslatorReady: Boolean = false
    private var showOverlayDetails: Boolean = true
    private var hasAutoCollapsedAfterTranslatorReady: Boolean = false
    private var enableAudioDump: Boolean = false
    private var dumpAsWav: Boolean = true
    private var lastLevelHint: String = "音量: 未知"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在初始化链路"))

        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        enableAudioDump = intent.getBooleanExtra(EXTRA_ENABLE_AUDIO_DUMP, false)
        dumpAsWav = intent.getBooleanExtra(EXTRA_AUDIO_DUMP_WAV, true)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val dataIntent = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)
        if (resultCode != 0 && dataIntent != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)
        }
        showOverlay()
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
            AudioInputMode.PLAYBACK_CAPTURE.label -> "已检测到播放捕获能力，准备建立真实 PCM 采集"
            AudioInputMode.MICROPHONE.label -> "播放捕获不可用，准备回退到麦克风本地识别"
            else -> "设备未通过音频输入探测"
        }
        modelState = "等待本地识别模型初始化"
        recognitionState = "等待本地识别启动"
        translationState = "等待翻译模型初始化"
        dumpState = if (enableAudioDump) "等待写入器初始化" else "未启用"
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
                playbackCaptureInitiallyAvailable && source.mode == AudioInputMode.MICROPHONE -> "播放捕获初始化失败，已切换到麦克风本地识别"
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
        hasAutoCollapsedAfterTranslatorReady = hasAutoCollapsedAfterTranslatorReady || !showOverlayDetails
        translatorDownloadStatusJob?.cancel()
        translationState = "正在准备日语→中文翻译模型（后台）"
        renderPipeline()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()

        translatorJob?.cancel()
        translator?.close()
        translator = com.google.mlkit.nl.translate.Translation.getClient(options)

        val startedAt = SystemClock.elapsedRealtime()
        fun buildDownloadState(extra: String): String {
            val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).coerceAtLeast(0L)
            return "下载中（已耗时 ${elapsedSeconds}s，$extra；进度：ML Kit 未提供百分比）"
        }
        fun startDownloadStatusTicker(extra: String) {
            translatorDownloadStatusJob?.cancel()
            translatorDownloadStatusJob = serviceScope.launch {
                while (isActive && !isTranslatorReady) {
                    translationState = buildDownloadState(extra)
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
            startDownloadStatusTicker("已允许移动网络")
            translator?.downloadModelIfNeeded()?.await()
            stopDownloadStatusTicker()
            isTranslatorReady = true
            translationState = "翻译模型就绪（允许流量下载）"
        } catch (error: Throwable) {
            stopDownloadStatusTicker()
            isTranslatorReady = false
            translationState = "翻译模型下载失败：${error.message ?: error.javaClass.simpleName}"
        }

        if (isTranslatorReady) {
            pendingTranslationCoordinator.consumeReady()?.let { pending ->
                translateRecognizedText(pending.text, pending.provisional)
                return
            }
        }
        renderPipeline()
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
            debugDumpWriter?.close()
            debugDumpWriter = runCatching {
                AudioDebugDumpWriter.create(
                    baseDir = getExternalFilesDir("audio-dumps") ?: filesDir,
                    enabled = enableAudioDump,
                    preferWav = dumpAsWav,
                    sampleRate = source.sampleRate,
                    channelCount = source.channelCount
                )
            }.onSuccess { writer ->
                dumpState = when {
                    !enableAudioDump -> "未启用"
                    writer != null -> "调试录音已保存"
                    else -> "调试录音未启用"
                }
            }.onFailure { error ->
                dumpState = "调试录音保存失败：${error.message ?: error.javaClass.simpleName}"
            }.getOrNull()
            runCatching { source.start() }
                .onFailure { error ->
                    serviceScope.launch {
                        captureState = "音频源启动失败：${error.message ?: error.javaClass.simpleName}"
                        renderPipeline()
                    }
                    return@launch
                }

            serviceScope.launch {
                captureState = if (source.mode == AudioInputMode.PLAYBACK_CAPTURE) "播放音频捕获中" else "麦克风音频采集中"
                recognitionState = "本地识别中（实时）"
                renderPipeline()
            }

            try {
                while (isActive) {
                    val read = source.read(buffer)
                    if (read <= 0) continue
                    debugDumpWriter?.write(buffer, read)
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
                            lastOriginalText = event.text
                            recognitionState = "本地识别中（实时）"
                            val candidate = translationSegmenter.onPartial(event.text, SystemClock.elapsedRealtime())
                            if (candidate != null) {
                                translateRecognizedText(candidate, provisional = true)
                            } else {
                                translationState = if (isTranslatorReady) "等待稳定分段后再翻译" else translationState
                                renderPipeline(levelOverride = levelHint)
                            }
                        }

                        is AsrEvent.Final -> serviceScope.launch {
                            lastOriginalText = event.text
                            recognitionState = "本地识别完成"
                            translationSegmenter.onFinal(event.text)?.let {
                                translateRecognizedText(it, provisional = false)
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
                        lastOriginalText = finalEvent.text
                        recognitionState = "本地识别完成"
                        translationSegmenter.onFinal(finalEvent.text)?.let {
                            translateRecognizedText(it, provisional = false)
                        } ?: run {
                            if (lastTranslatedText.isBlank()) {
                                translationState = "等待下一句翻译"
                            }
                            renderPipeline()
                        }
                    }
                }
                runCatching { source.stop() }
                debugDumpWriter?.close()
                debugDumpWriter = null
            }
        }
    }

    private fun translateRecognizedText(text: String, provisional: Boolean) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return
        }

        val currentTranslator = translator
        if (!isTranslatorReady || currentTranslator == null) {
            pendingTranslationCoordinator.rememberPending(normalizedText, provisional)
            translationState = "翻译器未就绪，先显示原文"
            lastTranslatedText = ""
            renderPipeline()
            return
        }

        if (pendingTranslationCoordinator.hasInFlight()) {
            pendingTranslationCoordinator.rememberPending(normalizedText, provisional)
            translationState = if (provisional) "等待更完整语句后接力翻译" else "等待当前翻译完成后接力"
            renderPipeline()
            return
        }

        pendingTranslationCoordinator.markInFlight(normalizedText, provisional)
        translatorJob = serviceScope.launch {
            translationState = if (provisional) "正在低延迟翻译（预测）" else "正在翻译"
            renderPipeline()
            try {
                val translated = withContext(Dispatchers.IO) { currentTranslator.translate(normalizedText).await().trim() }
                val queuedFollowUp = pendingTranslationCoordinator.peek()
                val suppressProvisionalResult = provisional && queuedFollowUp != null &&
                    (!queuedFollowUp.provisional || queuedFollowUp.text.length >= normalizedText.length)
                if (translated.isNotBlank()) {
                    if (suppressProvisionalResult) {
                        lastTranslatedText = ""
                    } else {
                        lastTranslatedText = translated
                    }
                    translationState = if (suppressProvisionalResult) {
                        "已收到更完整语句，等待更完整翻译"
                    } else if (provisional) {
                        "低延迟翻译已更新"
                    } else {
                        "翻译完成"
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

    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_subtitle, null)
        subtitleText = overlayView?.findViewById(R.id.subtitleText)
        detailsToggleButton = overlayView?.findViewById(R.id.detailsToggleButton)
        detailsToggleButton?.setOnClickListener {
            showOverlayDetails = !showOverlayDetails
            hasAutoCollapsedAfterTranslatorReady = true
            renderPipeline()
        }

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
        val dragBounds = overlayView as? ViewGroup

        fun shouldTreatAsTapTarget(v: View): Boolean {
            return v === detailsToggleButton
        }

        fun attachDragTouch(target: View) {
            target.setOnTouchListener(object : View.OnTouchListener {
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
                            return dragging
                        }

                        MotionEvent.ACTION_UP -> {
                            val wasDragging = dragging
                            dragging = false
                            if (wasDragging) {
                                return true
                            }
                            if (shouldTreatAsTapTarget(v)) {
                                v.performClick()
                                return true
                            }
                            return false
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            dragging = false
                            return false
                        }
                    }
                    return false
                }
            })
        }

        dragBounds?.let(::attachDragTouch)
        subtitleText?.let(::attachDragTouch)
        detailsToggleButton?.let(::attachDragTouch)
    }

    private fun renderPipeline(levelOverride: String? = null) {
        if (!hasAutoCollapsedAfterTranslatorReady && OverlayUiModeDecider.shouldAutoCollapse(translationState, lastTranslatedText)) {
            showOverlayDetails = false
            hasAutoCollapsedAfterTranslatorReady = true
        }
        detailsToggleButton?.text = OverlayUiModeDecider.detailsToggleLabel(showOverlayDetails)
        val effectiveLevelHint = levelOverride ?: lastLevelHint
        val overlayStatus = SubtitleOverlayFormatter.composePipeline(
            modeLabel = inputModeLabel,
            captureState = captureState,
            modelState = modelState,
            recognitionState = recognitionState,
            translationState = translationState,
            dumpState = dumpState,
            original = lastOriginalText,
            translated = lastTranslatedText,
            levelHint = effectiveLevelHint,
            showDetails = showOverlayDetails
        )
        val notificationStatus = SubtitleOverlayFormatter.composePipeline(
            modeLabel = inputModeLabel,
            captureState = captureState,
            modelState = modelState,
            recognitionState = recognitionState,
            translationState = translationState,
            dumpState = dumpState,
            original = lastOriginalText,
            translated = lastTranslatedText,
            levelHint = effectiveLevelHint,
            showDetails = true
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
        debugDumpWriter?.close()
        debugDumpWriter = null
        voskRecognizer?.close()
        voskRecognizer = null
        audioSource?.stop()
        audioSource?.release()
        audioSource = null
        translator?.close()
        translator = null
        mediaProjection?.stop()
        mediaProjection = null
        overlayView?.let { view -> windowManager?.removeView(view) }
        overlayView = null
        subtitleText = null
        detailsToggleButton = null
        overlayParams = null
        windowManager = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
