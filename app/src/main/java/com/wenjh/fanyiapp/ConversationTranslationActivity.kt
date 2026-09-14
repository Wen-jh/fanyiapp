package com.wenjh.fanyiapp

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 对话翻译：麦克风语音输入 -> 离线识别 -> 离线翻译 -> 语音朗读。
 *
 * 识别与翻译均为内置离线模型（sherpa-onnx Whisper + Hy-MT），
 * 不依赖系统语音识别服务、不依赖系统 TTS 语音包、不需要联网。
 * 与实时字幕翻译（SubtitleControlActivity / SubtitleOverlayService）完全独立。
 */
class ConversationTranslationActivity : AppCompatActivity() {

    private lateinit var conversationContainer: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var sourceLanguageText: TextView
    private lateinit var targetLanguageText: TextView
    private lateinit var engineStatusText: TextView
    private lateinit var micHintText: TextView
    private lateinit var micButton: ImageButton

    private var sourceLanguage: HyMtLanguageSupport.LanguageOption =
        HyMtLanguageSupport.findByCode("zh") ?: HyMtLanguageSupport.defaultSource
    private var targetLanguage: HyMtLanguageSupport.LanguageOption =
        HyMtLanguageSupport.findByCode("en") ?: HyMtLanguageSupport.defaultTarget

    private val translationEngine by lazy { HyMtTranslationEngine(applicationContext) }
    private val asrEngine by lazy { OfflineAsrEngine.get(applicationContext) }
    private val offlineTts by lazy { OfflineTtsEngine.get(applicationContext) }

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private var emptyHintView: View? = null

    /** 短于该阈值视为"点击"模式：开始录音后保持，等待再次点击结束 */
    private val longPressThresholdMs = 500L
    private var pressStartedAt = 0L
    private var startedByCurrentPress = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginRecording()
        } else {
            toast(getString(R.string.conversation_permission_needed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_translation)

        intent.getStringExtra(EXTRA_SOURCE_LANGUAGE_CODE)
            ?.let { HyMtLanguageSupport.findByCode(it) }
            ?.let { sourceLanguage = it }
        intent.getStringExtra(EXTRA_TARGET_LANGUAGE_CODE)
            ?.let { HyMtLanguageSupport.findByCode(it) }
            ?.let { targetLanguage = it }

        conversationContainer = findViewById(R.id.conversationContainer)
        conversationScroll = findViewById(R.id.conversationScroll)
        sourceLanguageText = findViewById(R.id.sourceLanguageText)
        targetLanguageText = findViewById(R.id.targetLanguageText)
        engineStatusText = findViewById(R.id.engineStatusText)
        micHintText = findViewById(R.id.micHintText)
        micButton = findViewById(R.id.micButton)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.clearButton).setOnClickListener { clearConversation() }
        findViewById<View>(R.id.sourceLanguageChip).setOnClickListener { showLanguagePicker(true) }
        findViewById<View>(R.id.targetLanguageChip).setOnClickListener { showLanguagePicker(false) }
        findViewById<ImageButton>(R.id.swapLanguageButton).setOnClickListener { swapLanguages() }
        bindMicGesture()

        updateLanguageLabels()
        showEmptyHint()
        initTextToSpeech()
        prepareTranslationEngine()
        prepareAsrEngine()
        offlineTts.prepare()
    }

    override fun onDestroy() {
        asrEngine.cancelRecording()
        textToSpeech?.let { tts ->
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        textToSpeech = null
        offlineTts.stop()
        translationEngine.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 麦克风交互：点击 / 长按说话
    // ------------------------------------------------------------------

    private fun bindMicGesture() {
        micButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressStartedAt = SystemClock.elapsedRealtime()
                    if (!asrEngine.isRecording) {
                        beginRecording()
                        startedByCurrentPress = true
                    } else {
                        // 已经在录音：这次按下属于"点击模式"的结束点击
                        startedByCurrentPress = false
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val duration = SystemClock.elapsedRealtime() - pressStartedAt
                    if (asrEngine.isRecording) {
                        val isSecondClick = !startedByCurrentPress
                        val isLongPressRelease = startedByCurrentPress && duration >= longPressThresholdMs
                        if (isSecondClick || isLongPressRelease) {
                            finishRecording()
                        }
                        // 短按开始：保持录音，等待用户再次点击结束
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun beginRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!asrEngine.prepared) {
            toast(getString(R.string.conversation_asr_not_ready))
            return
        }
        asrEngine.warmUp(sourceLanguage.code)
        if (!asrEngine.startRecording()) {
            toast(getString(R.string.conversation_record_failed))
            return
        }
        updateMicVisual(true)
        micHintText.text = getString(R.string.conversation_listening)
    }

    private fun finishRecording() {
        updateMicVisual(false)
        micHintText.text = getString(R.string.conversation_recognizing)
        asrEngine.stopAndRecognize(
            languageCode = sourceLanguage.code,
            onResult = { text -> handleRecognizedText(text) },
            onError = { message ->
                updateMicVisual(false)
                micHintText.text = getString(R.string.conversation_mic_hint)
                toast(message)
            }
        )
    }

    private fun updateMicVisual(recording: Boolean) {
        micButton.setBackgroundResource(
            if (recording) R.drawable.bg_conv_mic_active else R.drawable.bg_conv_mic
        )
    }

    // ------------------------------------------------------------------
    // 识别 -> 翻译 -> 朗读
    // ------------------------------------------------------------------

    private fun handleRecognizedText(sourceText: String) {
        micHintText.text = getString(R.string.conversation_mic_hint)
        val bubble = appendBubble(sourceText, getString(R.string.conversation_translating))
        bubble.translatedView.setTextColor(
            ContextCompat.getColor(this, R.color.conv_text_secondary)
        )

        lifecycleScope.launch {
            val state = translationEngine.currentState()
            if (state !is EngineState.Ready) {
                val prepared = runCatching { translationEngine.prepareIfNeeded() }.getOrNull()
                if (prepared?.ready != true) {
                    bubble.translatedView.text = getString(R.string.conversation_engine_failed)
                    return@launch
                }
            }
            val result = runCatching {
                translationEngine.translate(
                    text = sourceText,
                    sourceLanguage = sourceLanguage.promptName,
                    targetLanguage = targetLanguage.promptName
                )
            }.getOrElse { error ->
                bubble.translatedView.text =
                    "${getString(R.string.conversation_translate_failed)}：${error.message ?: "未知错误"}"
                return@launch
            }
            val translated = result.text.trim()
            if (translated.isBlank()) {
                bubble.translatedView.text = getString(R.string.conversation_translate_failed)
                return@launch
            }
            bubble.translatedView.text = translated
            bubble.translatedView.setTextColor(
                ContextCompat.getColor(this@ConversationTranslationActivity, R.color.conv_text_primary)
            )
            bubble.playButton.alpha = 1f
            bubble.playButton.isEnabled = true
            bubble.playButton.setOnClickListener { speak(translated) }
            scrollToBottom()
            speak(translated)
        }
        scrollToBottom()
    }

    private class BubbleHolder(
        val container: View,
        val translatedView: TextView,
        val playButton: ImageButton
    )

    private fun appendBubble(sourceText: String, translatedText: String): BubbleHolder {
        removeEmptyHint()
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val bubble = inflater.inflate(R.layout.item_conversation_bubble, conversationContainer, false)
        val sourceView = bubble.findViewById<TextView>(R.id.bubbleSourceText)
        val translatedView = bubble.findViewById<TextView>(R.id.bubbleTranslatedText)
        val playButton = bubble.findViewById<ImageButton>(R.id.bubblePlayButton)
        sourceView.text = sourceText
        translatedView.text = translatedText
        playButton.alpha = 0.4f
        playButton.isEnabled = false
        conversationContainer.addView(bubble)
        scrollToBottom()
        return BubbleHolder(bubble, translatedView, playButton)
    }

    private fun showEmptyHint() {
        if (emptyHintView != null) return
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val hint = inflater.inflate(R.layout.item_conversation_empty, conversationContainer, false)
        conversationContainer.addView(hint)
        emptyHintView = hint
    }

    private fun removeEmptyHint() {
        emptyHintView?.let { conversationContainer.removeView(it) }
        emptyHintView = null
    }

    private fun clearConversation() {
        if (conversationContainer.childCount == 0 || emptyHintView != null) return
        conversationContainer.removeAllViews()
        emptyHintView = null
        showEmptyHint()
        toast(getString(R.string.conversation_cleared))
    }

    private fun scrollToBottom() {
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    // 语言
    // ------------------------------------------------------------------

    private fun swapLanguages() {
        val previousSource = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = previousSource
        updateLanguageLabels()
        onLanguageChanged()
    }

    private fun updateLanguageLabels() {
        sourceLanguageText.text = sourceLanguage.uiLabel
        targetLanguageText.text = targetLanguage.uiLabel
    }

    private fun onLanguageChanged() {
        applyTtsLanguage()
        if (asrEngine.prepared) {
            asrEngine.warmUp(sourceLanguage.code)
        }
    }

    private fun showLanguagePicker(selectingSource: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_language_picker)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val title = TextView(this).apply {
            text = getString(R.string.main_language_picker_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        val current = if (selectingSource) sourceLanguage else targetLanguage
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        HyMtLanguageSupport.supportedLanguages.forEach { language ->
            val selected = language.code == current.code
            val row = TextView(this).apply {
                text = language.uiLabel
                setTextColor(if (selected) Color.parseColor("#0B84FF") else Color.parseColor("#F4F4F5"))
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener {
                    if (selectingSource) sourceLanguage = language else targetLanguage = language
                    updateLanguageLabels()
                    onLanguageChanged()
                    dialog.dismiss()
                }
            }
            listContainer.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
            )
        }

        val scroll = ScrollView(this).apply { addView(listContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                (resources.displayMetrics.heightPixels * 0.75f).toInt()
            )
        }
    }

    // ------------------------------------------------------------------
    // 引擎准备
    // ------------------------------------------------------------------

    private fun prepareTranslationEngine() {
        lifecycleScope.launch {
            val result = runCatching { translationEngine.prepareIfNeeded() }.getOrElse { error ->
                PreparationResult(false, error.message ?: "初始化异常")
            }
            if (!result.ready) {
                showStatus("${getString(R.string.conversation_engine_failed)}：${result.message}")
            }
        }
    }

    private fun prepareAsrEngine() {
        if (asrEngine.prepared) {
            asrEngine.warmUp(sourceLanguage.code)
            return
        }
        if (asrEngine.prepareError != null) {
            showStatus(asrEngine.statusText())
            return
        }
        asrEngine.prepare { percent ->
            runOnUiThread {
                if (asrEngine.prepared) {
                    hideStatus()
                    asrEngine.warmUp(sourceLanguage.code)
                } else {
                    showStatus(getString(R.string.conversation_asr_preparing, percent))
                }
            }
        }
    }

    private fun showStatus(message: String) {
        engineStatusText.text = message
        engineStatusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        engineStatusText.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // 朗读：内置离线优先，系统 TTS 兜底
    // ------------------------------------------------------------------

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                applyTtsLanguage()
            }
        }
    }

    private fun applyTtsLanguage(): Boolean {
        if (!ttsReady) return false
        val tts = textToSpeech ?: return false
        val status = runCatching { tts.setLanguage(localeFor(targetLanguage.code)) }.getOrNull()
            ?: return false
        return status != TextToSpeech.LANG_MISSING_DATA && status != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun speak(text: String) {
        val content = text.trim()
        if (content.isBlank()) return

        val code = targetLanguage.code
        if (OfflineTtsEngine.supports(code)) {
            if (offlineTts.speak(content, code)) return
            if (offlineTts.isPreparing) {
                toast(getString(R.string.conversation_tts_preparing, offlineTts.prepareProgress))
                return
            }
        }
        speakWithSystemTts(content)
    }

    private fun speakWithSystemTts(content: String) {
        if (!applyTtsLanguage()) {
            toast(getString(R.string.conversation_tts_unsupported))
            return
        }
        textToSpeech?.speak(
            content,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "conversation-${System.currentTimeMillis()}"
        )
    }

    private fun localeFor(code: String): Locale = when (code) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "zh-Hant" -> Locale.TRADITIONAL_CHINESE
        "en" -> Locale.US
        "ja" -> Locale.JAPAN
        "ko" -> Locale.KOREA
        else -> Locale.forLanguageTag(code)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SOURCE_LANGUAGE_CODE = "extra_conversation_source_language_code"
        const val EXTRA_TARGET_LANGUAGE_CODE = "extra_conversation_target_language_code"
    }
}
