package com.wenjh.fanyiapp

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.LayoutInflater
import java.util.Locale

/**
 * 对话翻译：麦克风语音输入 -> 离线翻译 -> 语音朗读。
 * 与实时字幕翻译（SubtitleControlActivity / SubtitleOverlayService）完全独立，互不影响。
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

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private var emptyHintView: TextView? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            toast(getString(R.string.conversation_permission_needed))
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            micHintText.text = getString(R.string.conversation_listening)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            micHintText.text = getString(R.string.conversation_recognizing)
        }

        override fun onError(error: Int) {
            isListening = false
            updateMicVisual(false)
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.conversation_no_result)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.conversation_permission_needed)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后再试"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常"
                else -> "语音识别失败（错误码 $error）"
            }
            micHintText.text = getString(R.string.conversation_mic_hint)
            toast(message)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            updateMicVisual(false)
            micHintText.text = getString(R.string.conversation_mic_hint)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isBlank()) {
                toast(getString(R.string.conversation_no_result))
                return
            }
            handleRecognizedText(text)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
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
        sourceLanguageText.setOnClickListener { showLanguagePicker(selectingSource = true) }
        targetLanguageText.setOnClickListener { showLanguagePicker(selectingSource = false) }
        findViewById<ImageButton>(R.id.swapLanguageButton).setOnClickListener { swapLanguages() }
        micButton.setOnClickListener { onMicClicked() }

        updateLanguageLabels()
        showEmptyHint()
        initTextToSpeech()
        prepareEngine()
    }

    override fun onDestroy() {
        speechRecognizer?.let { recognizer ->
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
        speechRecognizer = null
        textToSpeech?.let { tts ->
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        textToSpeech = null
        translationEngine.release()
        super.onDestroy()
    }

    private fun onMicClicked() {
        if (isListening) {
            stopListening()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(getString(R.string.conversation_speech_unavailable))
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        val languageTag = speechTagFor(sourceLanguage.code)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        runCatching { speechRecognizer?.startListening(intent) }
            .onFailure { toast("无法启动语音识别：${it.message ?: "未知错误"}") }
        isListening = true
        updateMicVisual(true)
        micHintText.text = getString(R.string.conversation_listening)
    }

    private fun stopListening() {
        runCatching { speechRecognizer?.stopListening() }
        isListening = false
        updateMicVisual(false)
        micHintText.text = getString(R.string.conversation_mic_hint)
    }

    private fun updateMicVisual(listening: Boolean) {
        micButton.alpha = if (listening) 0.55f else 1f
    }

    private fun handleRecognizedText(sourceText: String) {
        val bubble = appendBubble(sourceText, getString(R.string.conversation_translating))
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
                bubble.translatedView.text = "${getString(R.string.conversation_translate_failed)}：${error.message ?: "未知错误"}"
                return@launch
            }
            val translated = result.text.trim()
            if (translated.isBlank()) {
                bubble.translatedView.text = getString(R.string.conversation_translate_failed)
                return@launch
            }
            bubble.translatedView.text = translated
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
        playButton.alpha = 0.5f
        playButton.isEnabled = false
        conversationContainer.addView(bubble)
        scrollToBottom()
        return BubbleHolder(bubble, translatedView, playButton)
    }

    private fun showEmptyHint() {
        if (emptyHintView != null) return
        val hint = TextView(this).apply {
            text = getString(R.string.conversation_empty_hint)
            setTextColor(Color.parseColor("#77777F"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
        }
        conversationContainer.addView(hint)
        emptyHintView = hint
    }

    private fun removeEmptyHint() {
        emptyHintView?.let { conversationContainer.removeView(it) }
        emptyHintView = null
    }

    private fun clearConversation() {
        conversationContainer.removeAllViews()
        emptyHintView = null
        showEmptyHint()
        toast(getString(R.string.conversation_cleared))
    }

    private fun scrollToBottom() {
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun swapLanguages() {
        val previousSource = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = previousSource
        updateLanguageLabels()
        onLanguageChanged()
    }

    private fun updateLanguageLabels() {
        sourceLanguageText.text = sourceLanguage.displayName
        targetLanguageText.text = targetLanguage.displayName
    }

    private fun onLanguageChanged() {
        applyTtsLanguage()
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
                text = language.displayName
                setTextColor(if (selected) Color.parseColor("#2F8CFF") else Color.parseColor("#F4F4F5"))
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
            listContainer.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
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

    private fun prepareEngine() {
        lifecycleScope.launch {
            engineStatusText.text = getString(R.string.conversation_engine_preparing)
            val result = runCatching { translationEngine.prepareIfNeeded() }.getOrElse { error ->
                PreparationResult(false, error.message ?: "初始化异常")
            }
            engineStatusText.text = if (result.ready) {
                getString(R.string.conversation_engine_ready)
            } else {
                "${getString(R.string.conversation_engine_failed)}：${result.message}"
            }
        }
    }

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
        if (!applyTtsLanguage()) {
            toast(getString(R.string.conversation_tts_unsupported))
            return
        }
        textToSpeech?.speak(content, TextToSpeech.QUEUE_FLUSH, null, "conversation-${System.currentTimeMillis()}")
    }

    private fun speechTagFor(code: String): String = when (code) {
        "zh" -> "zh-CN"
        "zh-Hant" -> "zh-TW"
        "yue" -> "yue-HK"
        "en" -> "en-US"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        else -> code
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
