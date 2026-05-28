package com.wenjh.fanyiapp

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TextTranslationActivity : AppCompatActivity() {
    private lateinit var sourceLanguageSpinner: Spinner
    private lateinit var targetLanguageSpinner: Spinner
    private lateinit var inputEditText: EditText
    private lateinit var translateButton: Button
    private lateinit var swapButton: Button
    private lateinit var resultTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var modelStatusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var translationEngine: PhotoTranslationEngine

    private val languageOptions = HyMtLanguageSupport.supportedLanguages
    private var suppressLanguageSelectionCallback = false
    private var selectedSourceLanguageCode: String = HyMtLanguageSupport.defaultSource.code
    private var selectedTargetLanguageCode: String = HyMtLanguageSupport.defaultTarget.code
    private var currentStatus: String = "准备就绪"
    private var currentModelStatus: String = "模型状态：等待初始化"
    private var currentModelProgress: Int? = null
    private var currentInputText: String = ""
    private var currentResultText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_translation)

        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner)
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner)
        inputEditText = findViewById(R.id.inputEditText)
        translateButton = findViewById(R.id.translateButton)
        swapButton = findViewById(R.id.swapLanguageButton)
        resultTextView = findViewById(R.id.resultText)
        statusTextView = findViewById(R.id.statusText)
        modelStatusTextView = findViewById(R.id.modelStatusText)
        progressBar = findViewById(R.id.modelProgressBar)
        translationEngine = HyMtTranslationEngine(applicationContext)

        restoreState(savedInstanceState)
        setupLanguageSpinners()
        inputEditText.setText(currentInputText)
        resultTextView.text = currentResultText
        renderStatus()
        renderModelState(currentModelStatus, currentModelProgress)
        prepareTranslationEngine()

        swapButton.setOnClickListener {
            val originalSource = selectedSourceLanguageCode
            selectedSourceLanguageCode = selectedTargetLanguageCode
            selectedTargetLanguageCode = originalSource
            suppressLanguageSelectionCallback = true
            sourceLanguageSpinner.setSelection(indexOfLanguage(selectedSourceLanguageCode), false)
            targetLanguageSpinner.setSelection(indexOfLanguage(selectedTargetLanguageCode), false)
            suppressLanguageSelectionCallback = false
            currentStatus = "已切换翻译方向：${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}"
            renderStatus()
        }
        translateButton.setOnClickListener {
            translateCurrentInput()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SOURCE_LANGUAGE_CODE, selectedSourceLanguageCode)
        outState.putString(STATE_TARGET_LANGUAGE_CODE, selectedTargetLanguageCode)
        outState.putString(STATE_INPUT_TEXT, inputEditText.text?.toString().orEmpty())
        outState.putString(STATE_RESULT_TEXT, resultTextView.text?.toString().orEmpty())
        outState.putString(STATE_STATUS_TEXT, currentStatus)
        outState.putString(STATE_MODEL_STATUS_TEXT, currentModelStatus)
        outState.putInt(STATE_MODEL_PROGRESS, currentModelProgress ?: -1)
    }

    override fun onDestroy() {
        translationEngine.release()
        super.onDestroy()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        selectedSourceLanguageCode = savedInstanceState.getString(STATE_SOURCE_LANGUAGE_CODE).orEmpty()
            .ifBlank { HyMtLanguageSupport.defaultSource.code }
        selectedTargetLanguageCode = savedInstanceState.getString(STATE_TARGET_LANGUAGE_CODE).orEmpty()
            .ifBlank { HyMtLanguageSupport.defaultTarget.code }
        currentInputText = savedInstanceState.getString(STATE_INPUT_TEXT).orEmpty()
        currentResultText = savedInstanceState.getString(STATE_RESULT_TEXT).orEmpty()
        currentStatus = savedInstanceState.getString(STATE_STATUS_TEXT).orEmpty().ifBlank { "准备就绪" }
        currentModelStatus = savedInstanceState.getString(STATE_MODEL_STATUS_TEXT).orEmpty().ifBlank { "模型状态：等待初始化" }
        currentModelProgress = savedInstanceState.getInt(STATE_MODEL_PROGRESS, -1).takeIf { it >= 0 }
    }

    private fun setupLanguageSpinners() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languageOptions.map { it.displayName }
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sourceLanguageSpinner.adapter = adapter
        targetLanguageSpinner.adapter = adapter

        suppressLanguageSelectionCallback = true
        sourceLanguageSpinner.setSelection(indexOfLanguage(selectedSourceLanguageCode), false)
        targetLanguageSpinner.setSelection(indexOfLanguage(selectedTargetLanguageCode), false)
        suppressLanguageSelectionCallback = false

        sourceLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressLanguageSelectionCallback) return
                selectedSourceLanguageCode = languageOptions[position].code
                currentStatus = "已切换源语言：${selectedSourceLanguage().promptName}"
                renderStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        targetLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressLanguageSelectionCallback) return
                selectedTargetLanguageCode = languageOptions[position].code
                currentStatus = "已切换目标语言：${selectedTargetLanguage().promptName}"
                renderStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun translateCurrentInput() {
        val input = ImageTranslationFormatter.normalizeRecognizedText(inputEditText.text?.toString().orEmpty())
        if (input.isBlank()) {
            Toast.makeText(this, "请先输入需要翻译的内容", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val engineState = translationEngine.currentState()
            if (engineState !is EngineState.Ready) {
                currentStatus = "Hy-MT 模型尚未就绪，请稍候"
                renderStatus()
                prepareTranslationEngine(forceToast = true)
                return@launch
            }
            currentStatus = "正在翻译（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
            renderStatus()
            try {
                val result = translationEngine.translate(
                    text = input,
                    sourceLanguage = selectedSourceLanguage().promptName,
                    targetLanguage = selectedTargetLanguage().promptName
                )
                currentInputText = input
                currentResultText = result.text.ifBlank { "（暂无翻译结果）" }
                resultTextView.text = currentResultText
                currentStatus = if (result.backend == "builtin-fallback") {
                    "翻译完成，当前显示内置词典结果（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                } else {
                    "翻译完成（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                }
                renderStatus()
            } catch (error: Throwable) {
                currentStatus = "翻译失败：${error.message ?: error.javaClass.simpleName}"
                renderStatus()
                Toast.makeText(this@TextTranslationActivity, currentStatus, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun prepareTranslationEngine(forceToast: Boolean = false) {
        lifecycleScope.launch {
            currentModelStatus = "模型状态：正在检查 Hy-MT 离线模型"
            currentModelProgress = null
            renderModelState(currentModelStatus, currentModelProgress)
            val result = runCatching {
                translationEngine.prepareIfNeeded { progress ->
                    currentModelStatus = "模型状态：${progress.message}"
                    currentModelProgress = progress.percent
                    runOnUiThread {
                        renderModelState(currentModelStatus, currentModelProgress)
                    }
                }
            }.getOrElse { error ->
                PreparationResult(false, "Hy-MT 初始化失败：${error.message ?: error.javaClass.simpleName}")
            }
            currentModelStatus = if (result.ready) {
                "模型状态：Hy-MT 离线模型已就绪"
            } else {
                "模型状态：${result.message}"
            }
            currentModelProgress = if (result.ready) 100 else currentModelProgress
            renderModelState(currentModelStatus, currentModelProgress)
            if (forceToast || !result.ready) {
                Toast.makeText(this@TextTranslationActivity, currentModelStatus, Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun selectedSourceLanguage(): HyMtLanguageSupport.LanguageOption {
        return HyMtLanguageSupport.findByCode(selectedSourceLanguageCode) ?: HyMtLanguageSupport.defaultSource
    }

    private fun selectedTargetLanguage(): HyMtLanguageSupport.LanguageOption {
        return HyMtLanguageSupport.findByCode(selectedTargetLanguageCode) ?: HyMtLanguageSupport.defaultTarget
    }

    private fun indexOfLanguage(code: String): Int {
        val index = languageOptions.indexOfFirst { it.code == code }
        return if (index >= 0) index else 0
    }

    companion object {
        private const val STATE_SOURCE_LANGUAGE_CODE = "state_source_language_code"
        private const val STATE_TARGET_LANGUAGE_CODE = "state_target_language_code"
        private const val STATE_INPUT_TEXT = "state_input_text"
        private const val STATE_RESULT_TEXT = "state_result_text"
        private const val STATE_STATUS_TEXT = "state_status_text"
        private const val STATE_MODEL_STATUS_TEXT = "state_model_status_text"
        private const val STATE_MODEL_PROGRESS = "state_model_progress"
    }
}
