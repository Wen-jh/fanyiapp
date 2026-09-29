package com.wenjh.fanyiapp

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/**
 * 文本翻译页（一加翻译风格）。
 *
 * 与旧版的区别：
 * - 旧版用亮色 Material 卡片 + 两个 Spinner，在深色主题下 Spinner 文字是白色、
 *   卡片是白底，导致语言选择器完全看不见，用户既看不到也换不了目标语言；
 *   现在改成深色胶囊 + 底部弹层（文字颜色写死，不再跟随主题）。
 * - 目标语言选择会真正参与提示词构建，并且翻译后做输出语言校验，
 *   模型吐中文时会用强指令重试一次（见 HyMtTranslationEngine）。
 */
class TextTranslationActivity : AppCompatActivity() {

    private lateinit var sourceLanguageButton: View
    private lateinit var sourceLanguageText: TextView
    private lateinit var targetLanguageButton: View
    private lateinit var targetLanguageText: TextView
    private lateinit var swapButton: ImageButton
    private lateinit var inputEditText: EditText
    private lateinit var inputCountText: TextView
    private lateinit var translateButton: TextView
    private lateinit var resultTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var modelStatusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var translationEngine: PhotoTranslationEngine

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var selectedSourceLanguageCode: String = HyMtLanguageSupport.autoDetect.code
    private var selectedTargetLanguageCode: String = HyMtLanguageSupport.defaultTarget.code
    private var currentStatus: String = ""
    private var currentModelStatus: String = ""
    private var currentModelProgress: Int? = null
    private var currentResultText: String = ""
    private var lastRawOutput: String = ""
    private var pendingTranslateAfterPrepare = false
    private var ttsPreparing = false
    private var suppressInputWatcher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_translation)

        bindViews()
        restoreState(savedInstanceState)
        renderLanguageLabels()
        renderInputCount()
        renderStatus()
        renderModelState(currentModelStatus, currentModelProgress)

        findViewById<ImageButton>(R.id.textBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.clearAllButton).setOnClickListener { clearAll() }
        findViewById<TextView>(R.id.inputClearButton).setOnClickListener {
            suppressInputWatcher = true
            inputEditText.setText("")
            suppressInputWatcher = false
            renderInputCount()
            Toast.makeText(this, R.string.text_translation_cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        findViewById<TextView>(R.id.copyResultButton).setOnClickListener { copyResult() }
        findViewById<TextView>(R.id.speakResultButton).setOnClickListener { speakResult() }

        sourceLanguageButton.setOnClickListener { showLanguagePicker(isSource = true) }
        targetLanguageButton.setOnClickListener { showLanguagePicker(isSource = false) }
        swapButton.setOnClickListener { swapLanguages() }
        translateButton.setOnClickListener { translateCurrentInput() }
        // 长按状态行可查看模型原始输出，排查「输出语言不对」这类问题很有用
        statusTextView.setOnLongClickListener { showRawOutput() }

        inputEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressInputWatcher) return
                renderInputCount()
            }
        })

        prepareTranslationEngine()
    }

    private fun bindViews() {
        sourceLanguageButton = findViewById(R.id.sourceLanguageButton)
        sourceLanguageText = findViewById(R.id.sourceLanguageText)
        targetLanguageButton = findViewById(R.id.targetLanguageButton)
        targetLanguageText = findViewById(R.id.targetLanguageText)
        swapButton = findViewById(R.id.swapLanguageButton)
        inputEditText = findViewById(R.id.inputEditText)
        inputCountText = findViewById(R.id.inputCountText)
        translateButton = findViewById(R.id.translateButton)
        resultTextView = findViewById(R.id.resultText)
        statusTextView = findViewById(R.id.statusText)
        modelStatusTextView = findViewById(R.id.modelStatusText)
        progressBar = findViewById(R.id.modelProgressBar)
        translationEngine = HyMtTranslationEngine(applicationContext)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SOURCE_LANGUAGE_CODE, selectedSourceLanguageCode)
        outState.putString(STATE_TARGET_LANGUAGE_CODE, selectedTargetLanguageCode)
        outState.putString(STATE_INPUT_TEXT, inputEditText.text?.toString().orEmpty())
        outState.putString(STATE_RESULT_TEXT, currentResultText)
        outState.putString(STATE_STATUS_TEXT, currentStatus)
        outState.putString(STATE_MODEL_STATUS_TEXT, currentModelStatus)
        outState.putInt(STATE_MODEL_PROGRESS, currentModelProgress ?: -1)
    }

    override fun onDestroy() {
        OfflineTtsEngine.get(this).stop()
        translationEngine.release()
        super.onDestroy()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        val restoredSource = savedInstanceState?.getString(STATE_SOURCE_LANGUAGE_CODE)
        val restoredTarget = savedInstanceState?.getString(STATE_TARGET_LANGUAGE_CODE)
        selectedSourceLanguageCode = sanitizeSourceCode(
            restoredSource ?: preferences.getString(KEY_SOURCE_LANGUAGE, null)
        )
        selectedTargetLanguageCode = sanitizeTargetCode(
            restoredTarget ?: preferences.getString(KEY_TARGET_LANGUAGE, null)
        )
        if (savedInstanceState == null) {
            currentStatus = getString(R.string.text_translation_status_idle)
            currentModelStatus = getString(
                R.string.text_translation_model_state,
                getString(R.string.text_translation_model_checking)
            )
            return
        }
        suppressInputWatcher = true
        inputEditText.setText(savedInstanceState.getString(STATE_INPUT_TEXT).orEmpty())
        suppressInputWatcher = false
        currentResultText = savedInstanceState.getString(STATE_RESULT_TEXT).orEmpty()
        resultTextView.text = currentResultText
        currentStatus = savedInstanceState.getString(STATE_STATUS_TEXT).orEmpty()
            .ifBlank { getString(R.string.text_translation_status_idle) }
        currentModelStatus = savedInstanceState.getString(STATE_MODEL_STATUS_TEXT).orEmpty()
            .ifBlank {
                getString(
                    R.string.text_translation_model_state,
                    getString(R.string.text_translation_model_checking)
                )
            }
        currentModelProgress = savedInstanceState.getInt(STATE_MODEL_PROGRESS, -1).takeIf { it >= 0 }
    }

    private fun sanitizeSourceCode(code: String?): String {
        if (code == HyMtLanguageSupport.autoDetect.code) return HyMtLanguageSupport.autoDetect.code
        return HyMtLanguageSupport.findByCode(code.orEmpty())?.code ?: HyMtLanguageSupport.autoDetect.code
    }

    private fun sanitizeTargetCode(code: String?): String {
        return HyMtLanguageSupport.findByCode(code.orEmpty())?.code ?: HyMtLanguageSupport.defaultTarget.code
    }

    private fun persistLanguagePair() {
        preferences.edit()
            .putString(KEY_SOURCE_LANGUAGE, selectedSourceLanguageCode)
            .putString(KEY_TARGET_LANGUAGE, selectedTargetLanguageCode)
            .apply()
    }

    private fun selectedSourceLanguage(): HyMtLanguageSupport.LanguageOption {
        if (selectedSourceLanguageCode == HyMtLanguageSupport.autoDetect.code) {
            return HyMtLanguageSupport.autoDetect
        }
        return HyMtLanguageSupport.findByCode(selectedSourceLanguageCode) ?: HyMtLanguageSupport.autoDetect
    }

    private fun selectedTargetLanguage(): HyMtLanguageSupport.LanguageOption {
        return HyMtLanguageSupport.findByCode(selectedTargetLanguageCode) ?: HyMtLanguageSupport.defaultTarget
    }

    private fun renderLanguageLabels() {
        sourceLanguageText.text = selectedSourceLanguage().uiLabel
        targetLanguageText.text = selectedTargetLanguage().uiLabel
    }

    private fun renderInputCount() {
        inputCountText.text = getString(
            R.string.text_translation_char_count,
            inputEditText.text?.length ?: 0
        )
    }

    private fun renderStatus() {
        statusTextView.text = currentStatus
    }

    private fun renderModelState(status: String, progress: Int?) {
        modelStatusTextView.text = status
        if (progress == null) {
            progressBar.isIndeterminate = true
        } else {
            progressBar.isIndeterminate = false
            progressBar.progress = progress.coerceIn(0, 100)
        }
    }

    private fun clearAll() {
        suppressInputWatcher = true
        inputEditText.setText("")
        suppressInputWatcher = false
        currentResultText = ""
        resultTextView.text = ""
        renderInputCount()
        currentStatus = getString(R.string.text_translation_status_idle)
        renderStatus()
        Toast.makeText(this, R.string.text_translation_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }
        if (text.isBlank()) {
            Toast.makeText(this, R.string.text_translation_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        inputEditText.setText(text)
        inputEditText.setSelection(text.length)
        Toast.makeText(this, R.string.text_translation_pasted, Toast.LENGTH_SHORT).show()
    }

    private fun copyResult() {
        if (currentResultText.isBlank()) {
            Toast.makeText(this, R.string.text_translation_speak_no_result, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fanyiapp-translation", currentResultText))
        Toast.makeText(this, R.string.text_translation_copied, Toast.LENGTH_SHORT).show()
    }

    private fun speakResult() {
        if (currentResultText.isBlank()) {
            Toast.makeText(this, R.string.text_translation_speak_no_result, Toast.LENGTH_SHORT).show()
            return
        }
        val languageCode = selectedTargetLanguage().code
        if (!OfflineTtsEngine.supports(languageCode)) {
            Toast.makeText(this, R.string.text_translation_speak_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val engine = OfflineTtsEngine.get(this)
        if (engine.prepared) {
            startSpeak(engine, currentResultText, languageCode)
            return
        }
        if (ttsPreparing) return
        ttsPreparing = true
        Toast.makeText(this, R.string.text_translation_speak_preparing, Toast.LENGTH_SHORT).show()
        engine.prepare { percent ->
            if (percent >= 100) {
                runOnUiThread {
                    ttsPreparing = false
                    startSpeak(engine, currentResultText, selectedTargetLanguage().code)
                }
            }
        }
    }

    private fun startSpeak(engine: OfflineTtsEngine, text: String, languageCode: String) {
        if (!engine.speak(text, languageCode)) {
            Toast.makeText(this, R.string.text_translation_speak_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRawOutput(): Boolean {
        if (lastRawOutput.isBlank()) return false
        AlertDialog.Builder(this)
            .setTitle(R.string.text_translation_raw_output_title)
            .setMessage(lastRawOutput)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    private fun swapLanguages() {
        val source = selectedSourceLanguage()
        val target = selectedTargetLanguage()
        var newSourceCode = target.code
        var newTargetCode = if (source.code == HyMtLanguageSupport.autoDetect.code) {
            val detected = HyMtLanguageSupport.detectLanguageByScript(inputEditText.text?.toString().orEmpty())
            detected?.code ?: HyMtLanguageSupport.defaultSource.code
        } else {
            source.code
        }
        if (newSourceCode == newTargetCode) {
            newTargetCode = if (newSourceCode == "zh") "en" else "zh"
        }
        selectedSourceLanguageCode = newSourceCode
        selectedTargetLanguageCode = newTargetCode
        persistLanguagePair()
        renderLanguageLabels()
        currentStatus = getString(
            R.string.text_translation_swap_done,
            selectedSourceLanguage().uiLabel,
            selectedTargetLanguage().uiLabel
        )
        renderStatus()
    }

    private fun showLanguagePicker(isSource: Boolean) {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_language_picker, null)
        dialog.setContentView(content)

        content.findViewById<TextView>(R.id.languagePickerSubtitle).text = getString(
            if (isSource) R.string.text_translation_pick_source_hint
            else R.string.text_translation_pick_target_hint
        )

        val items = if (isSource) {
            HyMtLanguageSupport.sourceLanguages
        } else {
            HyMtLanguageSupport.targetLanguages
        }
        val currentCode = if (isSource) selectedSourceLanguageCode else selectedTargetLanguageCode
        val listView = content.findViewById<ListView>(R.id.languageListView)
        listView.adapter = LanguageOptionAdapter(this, items, currentCode)
        listView.setOnItemClickListener { _, _, position, _ ->
            val option = items[position]
            if (isSource) {
                selectedSourceLanguageCode = option.code
            } else {
                selectedTargetLanguageCode = option.code
            }
            persistLanguagePair()
            renderLanguageLabels()
            currentStatus = getString(
                if (isSource) R.string.text_translation_source_selected
                else R.string.text_translation_target_selected,
                option.uiLabel
            )
            renderStatus()
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun translateCurrentInput() {
        val input = inputEditText.text?.toString().orEmpty().trim()
        if (input.isBlank()) {
            Toast.makeText(this, R.string.text_translation_empty_input, Toast.LENGTH_SHORT).show()
            return
        }

        val source = selectedSourceLanguage()
        val target = selectedTargetLanguage()
        if (source.code != HyMtLanguageSupport.autoDetect.code && source.code == target.code) {
            currentResultText = input
            resultTextView.text = input
            currentStatus = getString(
                R.string.text_translation_status_done,
                source.uiLabel,
                target.uiLabel
            )
            renderStatus()
            return
        }

        lifecycleScope.launch {
            if (translationEngine.currentState() !is EngineState.Ready) {
                pendingTranslateAfterPrepare = true
                currentStatus = getString(R.string.text_translation_status_engine_not_ready)
                renderStatus()
                prepareTranslationEngine()
                return@launch
            }
            currentStatus = getString(
                R.string.text_translation_status_translating,
                source.uiLabel,
                target.uiLabel
            )
            renderStatus()
            translateButton.isEnabled = false
            try {
                val result = translationEngine.translate(
                    text = input,
                    sourceLanguage = source.promptName,
                    targetLanguage = target.promptName
                )
                currentResultText = result.text.ifBlank {
                    getString(R.string.text_translation_result_empty)
                }
                lastRawOutput = result.rawOutput.orEmpty()
                resultTextView.text = currentResultText
                val baseStatus = getString(
                    if (result.backend == "builtin-fallback") {
                        R.string.text_translation_status_fallback
                    } else {
                        R.string.text_translation_status_done
                    },
                    source.uiLabel,
                    target.uiLabel
                )
                currentStatus = result.statusMessage?.let { "$baseStatus；$it" } ?: baseStatus
                renderStatus()
            } catch (error: Throwable) {
                currentStatus = getString(
                    R.string.text_translation_status_failed,
                    error.message ?: error.javaClass.simpleName
                )
                renderStatus()
                Toast.makeText(this@TextTranslationActivity, currentStatus, Toast.LENGTH_SHORT).show()
            } finally {
                translateButton.isEnabled = true
            }
        }
    }

    private fun prepareTranslationEngine() {
        lifecycleScope.launch {
            currentModelStatus = getString(
                R.string.text_translation_model_state,
                getString(R.string.text_translation_model_checking)
            )
            currentModelProgress = null
            renderModelState(currentModelStatus, currentModelProgress)
            val result = runCatching {
                translationEngine.prepareIfNeeded { progress ->
                    currentModelStatus = getString(
                        R.string.text_translation_model_state,
                        progress.message
                    )
                    currentModelProgress = progress.percent
                    runOnUiThread {
                        renderModelState(currentModelStatus, currentModelProgress)
                    }
                }
            }.getOrElse { error ->
                PreparationResult(
                    false,
                    "Hy-MT 初始化失败：${error.message ?: error.javaClass.simpleName}"
                )
            }
            currentModelStatus = getString(
                R.string.text_translation_model_state,
                if (result.ready) getString(R.string.text_translation_model_ready) else result.message
            )
            currentModelProgress = if (result.ready) 100 else currentModelProgress
            renderModelState(currentModelStatus, currentModelProgress)
            if (result.ready && pendingTranslateAfterPrepare) {
                pendingTranslateAfterPrepare = false
                translateCurrentInput()
            }
        }
    }

    private class LanguageOptionAdapter(
        private val context: Context,
        private val items: List<HyMtLanguageSupport.LanguageOption>,
        private val selectedCode: String
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_language_option, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.languageOptionName).text = item.uiLabel
            view.findViewById<TextView>(R.id.languageOptionEnglish).text =
                if (item.promptName == item.uiLabel) "" else item.promptName
            view.findViewById<TextView>(R.id.languageOptionCheck).visibility =
                if (item.code == selectedCode) View.VISIBLE else View.INVISIBLE
            return view
        }
    }

    companion object {
        private const val PREFS_NAME = "text_translation_prefs"
        private const val KEY_SOURCE_LANGUAGE = "source_language_code"
        private const val KEY_TARGET_LANGUAGE = "target_language_code"

        private const val STATE_SOURCE_LANGUAGE_CODE = "state_source_language_code"
        private const val STATE_TARGET_LANGUAGE_CODE = "state_target_language_code"
        private const val STATE_INPUT_TEXT = "state_input_text"
        private const val STATE_RESULT_TEXT = "state_result_text"
        private const val STATE_STATUS_TEXT = "state_status_text"
        private const val STATE_MODEL_STATUS_TEXT = "state_model_status_text"
        private const val STATE_MODEL_PROGRESS = "state_model_progress"
    }
}
