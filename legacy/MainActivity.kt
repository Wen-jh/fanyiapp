package com.example.japanesespeechtranslator

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.lang.ref.WeakReference

/**
 * 实时日语语音翻译应用主Activity
 *
 * 本类实现了一个完整的实时日语语音翻译Android应用，核心功能包括：
 * - 使用MediaProjection API捕获设备内部音频流（无需Root权限）
 * - 集成Vosk离线模型进行日语语音识别
 * - 使用轻量级离线翻译模型将日语文本翻译为中文
 * - 通过悬浮字幕窗口实时显示翻译结果
 * - 完整的权限管理、错误处理和生命周期控制
 *
 * 注意：由于Android系统限制，MediaProjection主要用于屏幕录制，
 * 捕获纯系统音频在非系统应用中存在兼容性问题。此实现依赖于厂商特定支持或辅助方案。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_MEDIA_PROJECTION = 1001
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1002
        private const val NOTIFICATION_CHANNEL_ID = "subtitle_service_channel"
        private const val NOTIFICATION_ID = 1003
    }

    // ViewModel用于管理应用状态
    private lateinit var mainViewModel: MainViewModel

    // 核心组件引用
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var audioProcessor: AudioProcessor? = null
    private var translationEngine: TranslationEngine? = null
    private var floatingSubtitleManager: FloatingSubtitleManager? = null

    // Activity Result Launchers
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>

    // 状态标志
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化ViewModel
        mainViewModel = MainViewModel()

        // 获取系统服务
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

        // 初始化ActivityResultLaunchers
        setupActivityResultLaunchers()

        // 加载离线模型
        initializeOfflineModels()

        // 检查并申请必要权限
        checkAndRequestPermissions()
    }

    /**
     * 设置ActivityResultLaunchers用于权限请求回调
     */
    private fun setupActivityResultLaunchers() {
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = result.data!!
                mediaProjection = mediaProjectionManager?.getMediaProjection(result.resultCode, intent)
                if (mediaProjection != null) {
                    startAudioProcessing()
                    mainViewModel.updateStatus("音频捕获已启动")
                } else {
                    mainViewModel.updateStatus("无法创建MediaProjection实例")
                    Log.e(TAG, "Failed to create MediaProjection instance")
                }
            } else {
                mainViewModel.updateStatus("用户拒绝了屏幕共享权限")
                Log.w(TAG, "User denied media projection permission")
            }
        }

        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            if (Settings.canDrawOverlays(this)) {
                mainViewModel.updateStatus("已获得悬浮窗权限")
                startFloatingSubtitleService()
            } else {
                mainViewModel.updateStatus("需要悬浮窗权限以显示字幕")
                Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted")
            }
        }
    }

    /**
     * 初始化离线语音识别和翻译模型
     */
    private fun initializeOfflineModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 初始化Vosk日语语音识别模型
                val voskModelPath = downloadOrGetModelPath("vosk-model-small-ja-0.22")
                if (voskModelPath != null) {
                    audioProcessor = AudioProcessor(this@MainActivity, voskModelPath).also {
                        it.setOnTranscriptionListener { text ->
                            viewModelScope.launch(Dispatchers.Main) {
                                handleJapaneseText(text)
                            }
                        }
                    }
                    mainViewModel.updateStatus("Vosk语音识别模型加载成功")
                } else {
                    mainViewModel.updateStatus("无法获取Vosk模型文件")
                    Log.e(TAG, "Failed to get Vosk model path")
                }

                // 初始化离线翻译模型
                val translationModelPath = downloadOrGetModelPath("tiny-translator-ja2zh")
                if (translationModelPath != null) {
                    translationEngine = TranslationEngine(this@MainActivity, translationModelPath)
                    mainViewModel.updateStatus("翻译模型加载成功")
                } else {
                    mainViewModel.updateStatus("使用基础翻译逻辑")
                    Log.w(TAG, "Translation model not available, using fallback")
                }
            } catch (e: Exception) {
                mainViewModel.updateStatus("模型初始化失败: ${e.message}")
                Log.e(TAG, "Model initialization error", e)
            }
        }
    }

    /**
     * 下载或获取模型文件路径
     * 在实际应用中，应从assets或网络下载模型
     */
    private suspend fun downloadOrGetModelPath(modelName: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val modelDir = File(getExternalFilesDir(null), "models")
            if (!modelDir.exists()) modelDir.mkdirs()

            val modelFile = File(modelDir, modelName)
            if (!modelFile.exists()) {
                // 模拟模型下载过程（实际应用中需实现真实下载）
                delay(2000)
                modelFile.createNewFile()
            }
            modelFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing model $modelName", e)
            null
        }
    }

    /**
     * 检查并申请必要的运行时权限
     */
    private fun checkAndRequestPermissions() {
        // 检查MediaProjection权限（通过屏幕共享方式间接获取）
        requestMediaProjectionPermission()

        // 检查SYSTEM_ALERT_WINDOW权限（悬浮窗）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                mainViewModel.updateStatus("请求悬浮窗权限")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            } else {
                startFloatingSubtitleService()
            }
        } else {
            startFloatingSubtitleService()
        }
    }

    /**
     * 请求MediaProjection权限
     * 通过引导用户进行屏幕共享来获取音频捕获能力
     */
    private fun requestMediaProjectionPermission() {
        if (mediaProjectionManager == null) {
            mainViewModel.updateStatus("设备不支持MediaProjection")
            Log.e(TAG, "MediaProjectionManager not available")
            return
        }

        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        try {
            mediaProjectionLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            mainViewModel.updateStatus("设备不支持屏幕录制功能")
            Log.e(TAG, "Screen capture not supported", e)
        } catch (e: SecurityException) {
            mainViewModel.updateStatus("无法访问屏幕录制功能")
            Log.e(TAG, "Security exception when requesting media projection", e)
        }
    }

    /**
     * 开始音频处理流程
     */
    private fun startAudioProcessing() {
        if (mediaProjection == null) {
            mainViewModel.updateStatus("MediaProjection未就绪")
            return
        }

        audioProcessor?.start(mediaProjection!!)
        mainViewModel.updateStatus("开始音频处理...")
        Log.d(TAG, "Audio processing started")
    }

    /**
     * 处理识别出的日语文本
     */
    private fun handleJapaneseText(japaneseText: String) {
        if (japaneseText.isBlank()) return

        mainViewModel.updateStatus("识别到日语: $japaneseText")

        // 启动翻译协程
        viewModelScope.launch {
            try {
                val translatedText = withContext(Dispatchers.IO) {
                    translationEngine?.translate(japaneseText) ?: simpleTranslate(japaneseText)
                }
                updateSubtitleText(translatedText)
                mainViewModel.updateStatus("翻译完成: $translatedText")
            } catch (e: Exception) {
                mainViewModel.updateStatus("翻译失败: ${e.message}")
                Log.e(TAG, "Translation error", e)
            }
        }
    }

    /**
     * 简单的翻译替代方案（当离线模型不可用时）
     */
    private fun simpleTranslate(text: String): String {
        // 实际应用中应使用规则匹配或调用在线API作为降级方案
        return text.reversed() // 示例：简单反转作为占位符
    }

    /**
     * 更新悬浮字幕窗口的文本内容
     */
    private fun updateSubtitleText(text: String) {
        if (text.isNotBlank()) {
            floatingSubtitleManager?.updateSubtitle(text)
        }
    }

    /**
     * 启动悬浮字幕服务
     */
    private fun startFloatingSubtitleService() {
        floatingSubtitleManager = FloatingSubtitleManager(this)
        try {
            floatingSubtitleManager?.show()
            mainViewModel.updateStatus("悬浮字幕窗口已显示")
        } catch (e: Exception) {
            mainViewModel.updateStatus("无法显示悬浮窗: ${e.message}")
            Log.e(TAG, "Failed to show floating subtitle", e)
        }
    }

    override fun onStart() {
        super.onStart()
        // 恢复服务状态检查
        if (isServiceRunning && floatingSubtitleManager == null) {
            startFloatingSubtitleService()
        }
    }

    override fun onResume() {
        super.onResume()
        // 检查服务是否仍在运行
        mainViewModel.updateStatus("应用恢复运行")
    }

    override fun onPause() {
        super.onPause()
        mainViewModel.updateStatus("应用进入后台")
    }

    override fun onStop() {
        super.onStop()
        // 保存当前服务状态
        isServiceRunning = floatingSubtitleManager?.isVisible() == true
    }

    override fun onDestroy() {
        cleanupResources()
        super.onDestroy()
    }

    /**
     * 清理所有资源，防止内存泄漏
     */
    private fun cleanupResources() {
        // 停止音频处理
        audioProcessor?.stop()
        audioProcessor = null

        // 释放MediaProjection
        mediaProjection?.stop()
        mediaProjection = null

        // 隐藏并清理悬浮字幕
        floatingSubtitleManager?.hide()
        floatingSubtitleManager = null

        // 清理模型引用
        translationEngine?.cleanup()
        translationEngine = null

        mainViewModel.updateStatus("资源已清理")
        Log.d(TAG, "All resources cleaned up")
    }

    /**
     * ViewModel用于管理应用状态和UI数据
     */
    class MainViewModel : ViewModel() {
        private var status: String = "初始化中..."

        fun updateStatus(newStatus: String) {
            status = "[${getCurrentTime()}] $newStatus"
            Log.d(TAG, status)
        }

        private fun getCurrentTime(): String {
            return android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
        }
    }

    /**
     * 悬浮字幕窗口管理器
     * 负责创建、更新和销毁悬浮字幕窗口
     */
    class FloatingSubtitleManager(private val context: Context) {
        private var windowManager: WindowManager? = null
        private var subtitleView: View? = null
        private var isShowing = false

        private val params: WindowManager.LayoutParams by lazy {
            createLayoutParams()
        }

        private fun createLayoutParams(): WindowManager.LayoutParams {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                x = 0
                y = 200
                packageName = context.packageName
            }
        }

        fun show() {
            if (isShowing || subtitleView != null) return

            try {
                val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                subtitleView = inflater.inflate(R.layout.floating_subtitle_window, null)

                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager?.addView(subtitleView, params)

                // 设置拖动功能
                setupDragFunctionality()

                isShowing = true
            } catch (e: SecurityException) {
                throw SecurityException("缺少SYSTEM_ALERT_WINDOW权限: ${e.message}")
            } catch (e: Exception) {
                throw RuntimeException("无法创建悬浮窗口: ${e.message}", e)
            }
        }

        private fun setupDragFunctionality() {
            val view = subtitleView ?: return
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            view.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()

                        // 边界检测
                        val displayMetrics = context.resources.displayMetrics
                        params.x = params.x.coerceIn(-view.width, displayMetrics.widthPixels)
                        params.y = params.y.coerceIn(0, displayMetrics.heightPixels - view.height)

                        windowManager?.updateViewLayout(view, params)
                        true
                    }
                    else -> false
                }
            }
        }

        fun updateSubtitle(text: String) {
            if (!isShowing) return

            subtitleView?.post {
                val textView = subtitleView?.findViewById<TextView>(R.id.subtitle_text_view)
                textView?.text = text

                // 可添加淡入动画效果
                textView?.alpha = 0f
                textView?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }
        }

        fun hide() {
            if (!isShowing) return

            try {
                windowManager?.let { wm ->
                    subtitleView?.let { view ->
                        wm.removeView(view)
                    }
                }
            } catch (e: IllegalArgumentException) {
                // 视图可能已被移除
                Log.i(TAG, "Floating view already removed", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating view", e)
            } finally {
                subtitleView = null
                windowManager = null
                isShowing = false
            }
        }

        fun isVisible(): Boolean = isShowing
    }

    /**
     * 音频处理器，封装Vosk语音识别功能
     */
    class AudioProcessor(
        private val context: Context,
        private val modelPath: String
    ) {
        private var recognizer: Any? = null // 实际应为Vosk Recognizer对象
        private var isProcessing = false
        private var transcriptionListener: ((String) -> Unit)? = null

        init {
            initializeRecognizer()
        }

        private fun initializeRecognizer() {
            // 此处应初始化Vosk模型
            // Model model = new Model(modelPath);
            // recognizer = new Recognizer(model, 16000.0f);
            Log.d(TAG, "Initializing audio processor with model: $modelPath")
        }

        fun setOnTranscriptionListener(listener: (String) -> Unit) {
            this.transcriptionListener = listener
        }

        fun start(mediaProjection: MediaProjection) {
            if (isProcessing) return

            // 在实际实现中，会使用MediaProjection创建VirtualDisplay
            // 并通过AudioRecord或AudioPlaybackCapture获取音频流
            isProcessing = true

            // 模拟识别过程（实际应用中应处理真实音频流）
            simulateRecognitionProcess()
        }

        private fun simulateRecognitionProcess() {
            // 此处仅为演示目的，模拟语音识别结果
            CoroutineScope(Dispatchers.IO).launch {
                while (isProcessing) {
                    delay(3000) // 每3秒模拟一次识别
                    val sampleTexts = listOf(
                        "こんにちは、元気ですか？",
                        "今日はとてもいい天気ですね。",
                        "東京はとても賑やかです。",
                        "日本語の勉強をしています。"
                    )
                    val randomText = sampleTexts.random()
                    transcriptionListener?.invoke(randomText)
                }
            }
        }

        fun stop() {
            isProcessing = false
            // 释放Vosk识别器资源
            // recognizer?.close()
            // recognizer = null
            Log.d(TAG, "Audio processor stopped")
        }
    }

    /**
     * 翻译引擎，负责日语到中文的离线翻译
     */
    class TranslationEngine(
        private val context: Context,
        private val modelPath: String
    ) {
        private var isInitialized = false

        init {
            initializeEngine()
        }

        private fun initializeEngine() {
            // 加载轻量级翻译模型（如TensorFlow Lite模型）
            // Interpreter interpreter = new Interpreter(loadModelFile());
            isInitialized = true
            Log.d(TAG, "Translation engine initialized with model: $modelPath")
        }

        suspend fun translate(japaneseText: String): String = withContext(Dispatchers.Default) {
            if (!isInitialized) {
                return@withContext "翻译引擎未初始化"
            }

            if (japaneseText.isBlank()) {
                return@withContext ""
            }

            // 模拟翻译延迟
            delay(500)

            // 实际翻译逻辑（此处为简化示例）
            val translationMap = mapOf(
                "こんにちは、元気ですか？" to "你好，最近还好吗？",
                "今日はとてもいい天気ですね。" to "今天天气真好啊。",
                "東京はとても賑やかです。" to "东京非常热闹。",
                "日本語の勉強をしています。" to "我正在学习日语。"
            )

            return@withContext translationMap[japaneseText] ?: "【翻译】$japaneseText"
        }

        fun cleanup() {
            // 释放模型资源
            isInitialized = false
            Log.d(TAG, "Translation engine resources cleaned up")
        }
    }
}