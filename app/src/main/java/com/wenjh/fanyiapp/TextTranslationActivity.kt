package com.wenjh.fanyiapp

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TextTranslationActivity : AppCompatActivity() {
    private lateinit var sourceLanguageChip: View
    private lateinit var targetLanguageChip: View
    private lateinit var sourceLanguageTextView: TextView
    private lateinit var targetLanguageTextView: TextView
    private lateinit var inputEditText: EditText
    private lateinit var translateButton: Button
    private lateinit var swapButton: ImageButton
    private lateinit var resultTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var modelStatusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var translationEngine: PhotoTranslationEngine
    private lateinit var mlKitTranslationEngine: MlKitOnDeviceTranslationEngine

    private val languageOptions = HyMtLanguageSupport.supportedLanguages
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

        sourceLanguageChip = findViewById(R.id.sourceLanguageChip)
        targetLanguageChip = findViewById(R.id.targetLanguageChip)
        sourceLanguageTextView = findViewById(R.id.sourceLanguageText)
        targetLanguageTextView = findViewById(R.id.targetLanguageText)
        inputEditText = findViewById(R.id.inputEditText)
        translateButton = findViewById(R.id.translateButton)
        swapButton = findViewById(R.id.swapLanguageButton)
        resultTextView = findViewById(R.id.resultText)
        statusTextView = findViewById(R.id.statusText)
        modelStatusTextView = findViewById(R.id.modelStatusText)
        progressBar = findViewById(R.id.modelProgressBar)
        translationEngine = HyMtTranslationEngine(applicationContext)
        mlKitTranslationEngine = MlKitOnDeviceTranslationEngine()

        restoreState(savedInstanceState)
        updateLanguageSelectors()
        inputEditText.setText(currentInputText)
        resultTextView.text = currentResultText
        renderStatus()
        renderModelState(currentModelStatus, currentModelProgress)
        prepareTranslationEngine()

        sourceLanguageChip.setOnClickListener { showLanguagePicker(selectingSource = true) }
        targetLanguageChip.setOnClickListener { showLanguagePicker(selectingSource = false) }
        swapButton.setOnClickListener { swapLanguages() }
        translateButton.setOnClickListener { translateCurrentInput() }
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
        mlKitTranslationEngine.release()
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

    private fun showLanguagePicker(selectingSource: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var pendingSource = selectedSourceLanguage()
        var pendingTarget = selectedTargetLanguage()
        var activeSource = selectingSource

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_language_picker)
            setPadding(dp(18), dp(18), dp(18), 0)
        }

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val cancel = TextView(this).apply {
            text = getString(R.string.main_language_picker_cancel)
            setTextColor(Color.parseColor("#5EA1FF"))
            textSize = 18f
            setOnClickListener { dialog.dismiss() }
        }
        val title = TextView(this).apply {
            text = getString(R.string.main_language_picker_title)
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val done = TextView(this).apply {
            text = getString(R.string.main_language_picker_done)
            setTextColor(Color.parseColor("#5EA1FF"))
            textSize = 18f
            gravity = Gravity.END
            setOnClickListener {
                selectedSourceLanguageCode = pendingSource.code
                selectedTargetLanguageCode = pendingTarget.code
                onLanguageSelectionChanged()
                dialog.dismiss()
            }
        }
        titleRow.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))
        titleRow.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        titleRow.addView(done, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(titleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        val switchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(26), 0, dp(24))
        }
        root.addView(
            switchRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val sideLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 16f
            setPadding(dp(22), 0, 0, dp(12))
        }
        root.addView(
            sideLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_language_option)
            setPadding(dp(18), 0, dp(18), 0)
        }
        val scroll = ScrollView(this).apply {
            addView(listContainer)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        fun render() {
            switchRow.removeAllViews()
            switchRow.addView(
                createDialogLanguagePill(pendingSource.uiLabel, activeSource) {
                    activeSource = true
                    render()
                },
                LinearLayout.LayoutParams(dp(126), dp(52))
            )
            switchRow.addView(createDialogSwapButton {
                val oldSource = pendingSource
                pendingSource = pendingTarget
                pendingTarget = oldSource
                render()
            })
            switchRow.addView(
                createDialogLanguagePill(pendingTarget.uiLabel, !activeSource) {
                    activeSource = false
                    render()
                },
                LinearLayout.LayoutParams(dp(126), dp(52))
            )

            sideLabel.text = if (activeSource) {
                getString(R.string.main_language_source_label)
            } else {
                getString(R.string.main_language_target_label)
            }

            val checkedCode = if (activeSource) pendingSource.code else pendingTarget.code
            listContainer.removeAllViews()
            languageOptions.forEachIndexed { index, language ->
                listContainer.addView(
                    createLanguageRow(
                        label = language.uiLabel,
                        checked = language.code == checkedCode,
                        showDivider = index < languageOptions.lastIndex
                    ) {
                        if (activeSource) {
                            pendingSource = language
                        } else {
                            pendingTarget = language
                        }
                        render()
                    }
                )
            }
        }

        render()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun createDialogLanguagePill(
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_home_chip)
            setOnClickListener { onClick() }

            val text = TextView(this@TextTranslationActivity).apply {
                text = label
                setTextColor(if (selected) Color.parseColor("#2F8CFF") else Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(88)
                maxLines = 1
            }
            val chevron = ImageView(this@TextTranslationActivity).apply {
                setImageResource(R.drawable.ic_home_chevron_down)
                rotation = if (selected) 180f else 0f
            }
            addView(text)
            addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginStart = dp(4)
            })
        }
    }

    private fun createDialogSwapButton(onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(R.drawable.ic_home_swap)
            background = null
            contentDescription = getString(R.string.text_translation_swap)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(52)).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            }
        }
    }

    private fun createLanguageRow(
        label: String,
        checked: Boolean,
        showDivider: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val textView = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#F4F4F5"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        val radio = View(this).apply {
            setBackgroundResource(if (checked) R.drawable.bg_radio_checked else R.drawable.bg_radio_unchecked)
        }
        row.addView(textView, LinearLayout.LayoutParams(0, dp(72), 1f))
        row.addView(radio, LinearLayout.LayoutParams(dp(28), dp(28)))
        wrapper.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, dp(72))
        if (showDivider) {
            wrapper.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#454545"))
            }, LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        return wrapper
    }

    private fun swapLanguages() {
        val originalSource = selectedSourceLanguageCode
        selectedSourceLanguageCode = selectedTargetLanguageCode
        selectedTargetLanguageCode = originalSource
        currentResultText = ""
        resultTextView.text = currentResultText
        currentStatus = "已切换翻译方向：${selectedSourceLanguage().uiLabel} → ${selectedTargetLanguage().uiLabel}"
        renderLanguageSelection()
        renderStatus()
        prepareTranslationEngine()
    }

    private fun onLanguageSelectionChanged() {
        renderLanguageSelection()
        currentResultText = ""
        resultTextView.text = currentResultText
        currentStatus = "已切换翻译方向：${selectedSourceLanguage().uiLabel} → ${selectedTargetLanguage().uiLabel}"
        renderStatus()
        prepareTranslationEngine()
    }

    private fun updateLanguageSelectors() {
        renderLanguageSelection()
    }

    private fun renderLanguageSelection() {
        sourceLanguageTextView.text = selectedSourceLanguage().uiLabel
        targetLanguageTextView.text = selectedTargetLanguage().uiLabel
    }

    private fun translateCurrentInput() {
        val input = ImageTranslationFormatter.normalizeRecognizedText(inputEditText.text?.toString().orEmpty())
        if (input.isBlank()) {
            Toast.makeText(this, "请先输入需要翻译的内容", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            currentStatus = "正在翻译（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
            renderStatus()
            try {
                val result = translateWithBestAvailableEngine(input)
                currentInputText = input
                currentResultText = result.text.ifBlank { "（暂无翻译结果）" }
                resultTextView.text = currentResultText
                currentStatus = when {
                    result.text.isBlank() -> "翻译完成，但结果为空（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                    result.backend == "hy-mt-native" -> "翻译完成（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                    result.backend == "ml-kit-fallback" -> "Hy-MT 当前不可用，已切换到 ML Kit 翻译（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                    result.backend == "builtin-fallback" -> "翻译完成，当前显示内置词典结果（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                    else -> "翻译完成（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                }
                renderStatus()
            } catch (error: Throwable) {
                currentStatus = "翻译失败：${error.message ?: error.javaClass.simpleName}"
                renderStatus()
                Toast.makeText(this@TextTranslationActivity, currentStatus, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun translateWithBestAvailableEngine(text: String): TranslationResult {
        val hyMtState = translationEngine.currentState()
        if (hyMtState is EngineState.Ready) {
            val hyMtResult = translationEngine.translate(
                text = text,
                sourceLanguage = selectedSourceLanguage().promptName,
                targetLanguage = selectedTargetLanguage().promptName
            )
            if (hyMtResult.backend == "hy-mt-native" && hyMtResult.text.isNotBlank()) {
                return hyMtResult
            }
            if (hyMtResult.backend != "builtin-fallback" && hyMtResult.text.isNotBlank()) {
                return hyMtResult
            }
        }

        return mlKitTranslationEngine.translate(
            text = text,
            sourceCode = selectedSourceLanguageCode,
            targetCode = selectedTargetLanguageCode
        )
    }

    private fun prepareTranslationEngine(forceToast: Boolean = false) {
        lifecycleScope.launch {
            currentModelStatus = "模型状态：正在检查 Hy-MT 离线模型"
            currentModelProgress = null
            renderModelState(currentModelStatus, currentModelProgress)

            val hyMtResult = runCatching {
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

            if (hyMtResult.ready) {
                currentModelStatus = "模型状态：Hy-MT 离线模型已就绪"
                currentModelProgress = 100
                renderModelState(currentModelStatus, currentModelProgress)
                if (forceToast) {
                    Toast.makeText(this@TextTranslationActivity, currentModelStatus, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            currentModelStatus = "模型状态：${hyMtResult.message}，正在准备 ML Kit 翻译"
            currentModelProgress = null
            renderModelState(currentModelStatus, currentModelProgress)

            val mlKitResult = mlKitTranslationEngine.prepareIfNeeded(
                sourceCode = selectedSourceLanguageCode,
                targetCode = selectedTargetLanguageCode
            )
            currentModelStatus = if (mlKitResult.ready) {
                "模型状态：Hy-MT 未接通，已切换到 ML Kit 翻译"
            } else {
                "模型状态：Hy-MT 不可用，ML Kit 也未就绪：${mlKitResult.message}"
            }
            currentModelProgress = if (mlKitResult.ready) 100 else null
            renderModelState(currentModelStatus, currentModelProgress)
            if (forceToast || !mlKitResult.ready) {
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
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
