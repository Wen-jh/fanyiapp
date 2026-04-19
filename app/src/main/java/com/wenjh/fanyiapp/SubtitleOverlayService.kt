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
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var subtitleText: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var mediaProjection: MediaProjection? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var recognitionRestartJob: Job? = null
    private var inputMode: InputMode = InputMode.UNDECIDED
    private var lastRms: Float = 0f
    private var lastOriginalText: String = ""
    private var lastTranslatedText: String = ""
    private var captureState: String = "等待初始化"
    private var recognitionState: String = "未开始"
    private var translationState: String = "未开始"
    private var isRecognizerReady: Boolean = false
    private var isTranslatorReady: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在初始化链路"))

        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val dataIntent = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)
            if (resultCode != 0 && dataIntent != null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)
            }
            showOverlay()
            serviceScope.launch {
                bootstrapPipeline()
            }
        }
        return START_STICKY
    }

    private suspend fun bootstrapPipeline() {
        determineInputMode()
        renderPipeline()
        prepareTranslator()
        prepareSpeechRecognizer()
        renderPipeline()
        maybeStartRecognition()
    }

    private fun determineInputMode() {
        val probe = AudioCaptureProbe()
        val projection = mediaProjection
        inputMode = when {
            projection != null && probe.canAttemptPlaybackCapture(projection) -> InputMode.PLAYBACK_CAPTURE
            probe.microphoneFallbackAvailable() -> InputMode.MICROPHONE
            else -> InputMode.UNAVAILABLE
        }

        captureState = when (inputMode) {
            InputMode.PLAYBACK_CAPTURE -> "已获得播放捕获权限，但当前版本实际识别仍走麦克风 SpeechRecognizer"
            InputMode.MICROPHONE -> "播放捕获不可直用，已切换到麦克风识别"
            InputMode.UNAVAILABLE -> "设备未通过音频输入探测"
            InputMode.UNDECIDED -> "等待检测"
        }
        recognitionState = "等待识别器初始化"
        translationState = "等待翻译模型初始化"
        pushNotification("${inputMode.label} / ${captureState}")
    }

    private suspend fun prepareTranslator() {
        translationState = "正在准备日语→中文翻译模型"
        renderPipeline()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()

        translator?.close()
        translator = com.google.mlkit.nl.translate.Translation.getClient(options)

        try {
            translator?.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())?.await()
            isTranslatorReady = true
            translationState = "翻译模型就绪"
        } catch (wifiError: Throwable) {
            try {
                translator?.downloadModelIfNeeded()?.await()
                isTranslatorReady = true
                translationState = "翻译模型就绪（已允许移动网络下载）"
            } catch (error: Throwable) {
                isTranslatorReady = false
                translationState = "翻译模型下载失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
        renderPipeline()
    }

    private fun prepareSpeechRecognizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recognitionState = "缺少录音权限"
            renderPipeline()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            recognitionState = "系统 SpeechRecognizer 不可用"
            renderPipeline()
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isRecognizerReady = true
                    recognitionState = "已就绪，等待日语语音"
                    captureState = "麦克风监听中"
                    renderPipeline()
                }

                override fun onBeginningOfSpeech() {
                    captureState = "已接收到音频"
                    recognitionState = "正在识别"
                    renderPipeline()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    lastRms = rmsdB
                    val normalized = (((rmsdB + 2f) / 12f) * 100f).coerceIn(0f, 100f)
                    captureState = if (normalized > 8f) "已接收到音频" else "等待音频输入"
                    renderPipeline(levelOverride = "音量: ${normalized.toInt()}%")
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    recognitionState = "已收到语音，等待识别结果"
                    renderPipeline()
                }

                override fun onError(error: Int) {
                    recognitionState = "识别错误: ${speechErrorToText(error)}"
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        captureState = "未检测到可识别语音"
                    }
                    renderPipeline()
                    scheduleRecognitionRestart()
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isBlank()) {
                        recognitionState = "识别完成，但文本为空"
                        renderPipeline()
                        scheduleRecognitionRestart()
                        return
                    }
                    lastOriginalText = text
                    recognitionState = "已识别到日语文本"
                    renderPipeline()
                    translateRecognizedText(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        lastOriginalText = partial
                        recognitionState = "识别中（实时）"
                        renderPipeline()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }

        recognitionState = "识别器已初始化"
    }

    private fun maybeStartRecognition() {
        if (inputMode == InputMode.UNAVAILABLE) {
            captureState = "无法开始，设备探测失败"
            renderPipeline()
            return
        }
        if (!isRecognizerReady && speechRecognizer == null) {
            renderPipeline()
        }
        startListening()
    }

    private fun startListening() {
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        recognitionState = "正在启动识别"
        renderPipeline()
        try {
            recognizer.startListening(intent)
        } catch (error: Throwable) {
            recognitionState = "启动识别失败：${error.message ?: error.javaClass.simpleName}"
            renderPipeline()
        }
    }

    private fun scheduleRecognitionRestart() {
        recognitionRestartJob?.cancel()
        recognitionRestartJob = serviceScope.launch {
            delay(1200)
            if (isActive) startListening()
        }
    }

    private fun translateRecognizedText(text: String) {
        val currentTranslator = translator
        if (!isTranslatorReady || currentTranslator == null) {
            translationState = "翻译器未就绪，保留原文"
            lastTranslatedText = ""
            renderPipeline()
            scheduleRecognitionRestart()
            return
        }

        serviceScope.launch {
            translationState = "正在翻译"
            renderPipeline()
            try {
                val translated = withContext(Dispatchers.IO) { currentTranslator.translate(text).await() }
                lastTranslatedText = translated.trim()
                translationState = if (lastTranslatedText.isBlank()) "翻译完成，但结果为空" else "翻译完成"
            } catch (error: Throwable) {
                lastTranslatedText = ""
                translationState = "翻译失败：${error.message ?: error.javaClass.simpleName}"
            }
            renderPipeline()
            scheduleRecognitionRestart()
        }
    }

    private fun showOverlay() {
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
        val view = overlayView ?: return
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var downAt = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = overlayParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        downAt = SystemClock.elapsedRealtime()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - downX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = abs(event.rawX - downX) > 8 || abs(event.rawY - downY) > 8
                        val held = SystemClock.elapsedRealtime() - downAt
                        return moved || held > 120
                    }
                }
                return false
            }
        })
    }

    private fun renderPipeline(levelOverride: String? = null) {
        val text = SubtitleOverlayFormatter.composePipeline(
            modeLabel = inputMode.label,
            captureState = captureState,
            recognitionState = recognitionState,
            translationState = translationState,
            original = lastOriginalText,
            translated = lastTranslatedText,
            levelHint = levelOverride ?: levelHintFromRms(lastRms)
        )
        subtitleText?.text = text
        pushNotification("${inputMode.label} / ${recognitionState}")
    }

    private fun levelHintFromRms(rms: Float): String {
        if (rms == 0f) return "音量: 未知"
        val normalized = (((rms + 2f) / 12f) * 100f).coerceIn(0f, 100f)
        return "音量: ${normalized.toInt()}%"
    }

    private fun speechErrorToText(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "音频输入异常"
            SpeechRecognizer.ERROR_CLIENT -> "客户端异常"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "录音权限不足"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到匹配文本"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
            SpeechRecognizer.ERROR_SERVER -> "识别服务异常"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
            else -> "未知错误($code)"
        }
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
        recognitionRestartJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        translator?.close()
        translator = null
        mediaProjection?.stop()
        mediaProjection = null
        overlayView?.let { view ->
            windowManager?.removeView(view)
        }
        overlayView = null
        overlayParams = null
        windowManager = null
        serviceScope.cancel()
        super.onDestroy()
    }
}

private enum class InputMode(val label: String) {
    UNDECIDED("检测中"),
    PLAYBACK_CAPTURE("播放捕获+麦克风识别"),
    MICROPHONE("麦克风识别"),
    UNAVAILABLE("不可用")
}
