package com.wenjh.fanyiapp

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageTranslationActivity : AppCompatActivity() {
    private lateinit var previewImage: ImageView
    private lateinit var recognizedLabelTextView: TextView
    private lateinit var translatedLabelTextView: TextView
    private lateinit var recognizedTextView: TextView
    private lateinit var translatedTextView: TextView
    private lateinit var resultStatusTextView: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var modelProgressBar: ProgressBar
    private lateinit var retryModelInitButton: Button
    private lateinit var takePhotoButton: Button
    private lateinit var choosePhotoButton: Button
    private lateinit var sourceLanguageTextView: TextView
    private lateinit var targetLanguageTextView: TextView
    private lateinit var hyMtEngine: PhotoTranslationEngine
    private lateinit var mlKitEngine: MlKitOnDeviceTranslationEngine

    private var textRecognizer: TextRecognizer? = null
    private var activeOcrScript: HyMtLanguageSupport.OcrScript? = null
    private var analyzeJob: Job? = null
    private val languageOptions = HyMtLanguageSupport.supportedLanguages

    private var lastRecognizedText: String = ""
    private var lastTranslatedText: String = ""
    private var selectedSourceLanguageCode: String = HyMtLanguageSupport.defaultSource.code
    private var selectedTargetLanguageCode: String = HyMtLanguageSupport.defaultTarget.code
    private var currentStatus: String = defaultReadyStatus()
    private var currentModelStatus: String = "模型状态：等待初始化"
    private var currentModelProgress: Int? = null
    private var currentPhotoUri: Uri? = null
    private var currentPhotoFilePath: String? = null
    private var lastPreviewUri: String? = null
    private var analyzeRequestToken: Long = 0L

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val photoUri = currentPhotoUri
        if (!success || photoUri == null) {
            showResult(status = "未拍到图片，请重试", showToast = true)
            cleanupPendingPhotoIfNeeded(keepCurrent = false)
            return@registerForActivityResult
        }
        lastPreviewUri = photoUri.toString()
        previewImage.setImageURI(photoUri)
        analyzeUri(photoUri)
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            showResult(status = "未选择图片", showToast = true)
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        cleanupPendingPhotoIfNeeded(keepCurrent = false)
        currentPhotoUri = null
        currentPhotoFilePath = null
        lastPreviewUri = uri.toString()
        previewImage.setImageURI(uri)
        analyzeUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_translation)

        previewImage = findViewById(R.id.previewImage)
        recognizedLabelTextView = findViewById(R.id.recognizedLabelText)
        translatedLabelTextView = findViewById(R.id.translatedLabelText)
        recognizedTextView = findViewById(R.id.recognizedText)
        translatedTextView = findViewById(R.id.translatedText)
        resultStatusTextView = findViewById(R.id.resultStatusText)
        modelStatusText = findViewById(R.id.modelStatusText)
        modelProgressBar = findViewById(R.id.modelProgressBar)
        retryModelInitButton = findViewById(R.id.retryModelInitButton)
        takePhotoButton = findViewById(R.id.takePhotoButton)
        choosePhotoButton = findViewById(R.id.choosePhotoButton)
        sourceLanguageTextView = findViewById(R.id.sourceLanguageText)
        targetLanguageTextView = findViewById(R.id.targetLanguageText)
        hyMtEngine = HyMtTranslationEngine(applicationContext)
        mlKitEngine = MlKitOnDeviceTranslationEngine()

        restoreState(savedInstanceState)
        updateLanguageSelectors()
        updateLanguageLabels()

        findViewById<View>(R.id.sourceLanguageButton).setOnClickListener {
            showLanguagePicker(selectingSource = true)
        }
        findViewById<View>(R.id.targetLanguageButton).setOnClickListener {
            showLanguagePicker(selectingSource = false)
        }
        findViewById<View>(R.id.swapLanguageButton).setOnClickListener {
            val source = selectedSourceLanguageCode
            selectedSourceLanguageCode = selectedTargetLanguageCode
            selectedTargetLanguageCode = source
            onLanguageSelectionChanged()
        }
        takePhotoButton.setOnClickListener {
            launchHighResolutionCamera()
        }
        choosePhotoButton.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }
        retryModelInitButton.setOnClickListener {
            prepareTranslationEngine(forceToast = true)
        }

        showResult(status = currentStatus)
        renderModelState(currentModelStatus, currentModelProgress)
        restorePreviewIfPossible()
        prepareTranslationEngine()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_RECOGNIZED_TEXT, lastRecognizedText)
        outState.putString(STATE_TRANSLATED_TEXT, lastTranslatedText)
        outState.putString(STATE_STATUS_TEXT, currentStatus)
        outState.putString(STATE_MODEL_STATUS_TEXT, currentModelStatus)
        outState.putInt(STATE_MODEL_PROGRESS, currentModelProgress ?: -1)
        outState.putString(STATE_CURRENT_PHOTO_URI, currentPhotoUri?.toString())
        outState.putString(STATE_CURRENT_PHOTO_PATH, currentPhotoFilePath)
        outState.putString(STATE_LAST_PREVIEW_URI, lastPreviewUri)
        outState.putString(STATE_SOURCE_LANGUAGE_CODE, selectedSourceLanguageCode)
        outState.putString(STATE_TARGET_LANGUAGE_CODE, selectedTargetLanguageCode)
    }

    override fun onDestroy() {
        analyzeJob?.cancel()
        hyMtEngine.release()
        mlKitEngine.release()
        runCatching { textRecognizer?.close() }
        textRecognizer = null
        activeOcrScript = null
        super.onDestroy()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        lastRecognizedText = savedInstanceState.getString(STATE_RECOGNIZED_TEXT).orEmpty()
        lastTranslatedText = savedInstanceState.getString(STATE_TRANSLATED_TEXT).orEmpty()
        selectedSourceLanguageCode = savedInstanceState.getString(STATE_SOURCE_LANGUAGE_CODE).orEmpty()
            .ifBlank { HyMtLanguageSupport.defaultSource.code }
        selectedTargetLanguageCode = savedInstanceState.getString(STATE_TARGET_LANGUAGE_CODE).orEmpty()
            .ifBlank { HyMtLanguageSupport.defaultTarget.code }
        currentStatus = savedInstanceState.getString(STATE_STATUS_TEXT).orEmpty()
            .ifBlank { defaultReadyStatus() }
        currentModelStatus = savedInstanceState.getString(STATE_MODEL_STATUS_TEXT).orEmpty()
            .ifBlank { "模型状态：等待初始化" }
        currentModelProgress = savedInstanceState.getInt(STATE_MODEL_PROGRESS, -1).takeIf { it >= 0 }
        currentPhotoUri = savedInstanceState.getString(STATE_CURRENT_PHOTO_URI)?.let(Uri::parse)
        currentPhotoFilePath = savedInstanceState.getString(STATE_CURRENT_PHOTO_PATH)
        lastPreviewUri = savedInstanceState.getString(STATE_LAST_PREVIEW_URI)
    }

    private fun restorePreviewIfPossible() {
        val previewUri = lastPreviewUri?.let(Uri::parse) ?: return
        runCatching {
            previewImage.setImageURI(previewUri)
        }
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

            val text = TextView(this@ImageTranslationActivity).apply {
                text = label
                setTextColor(if (selected) Color.parseColor("#2F8CFF") else Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(88)
                maxLines = 1
            }
            val chevron = ImageView(this@ImageTranslationActivity).apply {
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

    private fun onLanguageSelectionChanged() {
        updateLanguageSelectors()
        updateLanguageLabels()
        lastTranslatedText = ""
        currentStatus = "已切换翻译方向：${selectedSourceLanguage().uiLabel} → ${selectedTargetLanguage().uiLabel}"
        showResult(status = currentStatus)
        prepareTranslationEngine()
    }

    private fun updateLanguageSelectors() {
        sourceLanguageTextView.text = selectedSourceLanguage().uiLabel
        targetLanguageTextView.text = selectedTargetLanguage().uiLabel
    }

    private fun updateLanguageLabels() {
        recognizedLabelTextView.text = "识别出的原文（${selectedSourceLanguage().uiLabel}）"
        translatedLabelTextView.text = "翻译结果（${selectedTargetLanguage().uiLabel}）"
    }

    private fun selectedSourceLanguage(): HyMtLanguageSupport.LanguageOption {
        return HyMtLanguageSupport.findByCode(selectedSourceLanguageCode) ?: HyMtLanguageSupport.defaultSource
    }

    private fun selectedTargetLanguage(): HyMtLanguageSupport.LanguageOption {
        return HyMtLanguageSupport.findByCode(selectedTargetLanguageCode) ?: HyMtLanguageSupport.defaultTarget
    }

    private fun defaultReadyStatus(): String {
        return "准备就绪：可拍照或从相册选择图片，${selectedSourceLanguage().uiLabel} → ${selectedTargetLanguage().uiLabel}"
    }

    private fun getOrCreateTextRecognizer(): TextRecognizer {
        val nextScript = selectedSourceLanguage().ocrScript
        val currentRecognizer = textRecognizer
        if (currentRecognizer != null && activeOcrScript == nextScript) {
            return currentRecognizer
        }

        runCatching { currentRecognizer?.close() }
        val recognizer = createTextRecognizer(nextScript)
        textRecognizer = recognizer
        activeOcrScript = nextScript
        return recognizer
    }

    private fun createTextRecognizer(script: HyMtLanguageSupport.OcrScript): TextRecognizer {
        return when (script) {
            HyMtLanguageSupport.OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            HyMtLanguageSupport.OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            HyMtLanguageSupport.OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            HyMtLanguageSupport.OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            HyMtLanguageSupport.OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private fun launchHighResolutionCamera() {
        val imageFile = createCameraImageFile()
        if (imageFile == null) {
            showResult(status = "创建拍照文件失败，请稍后重试", showToast = true)
            return
        }
        cleanupPendingPhotoIfNeeded(keepCurrent = false)
        val photoUri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            imageFile
        )
        currentPhotoFilePath = imageFile.absolutePath
        currentPhotoUri = photoUri
        lastPreviewUri = photoUri.toString()
        takePictureLauncher.launch(photoUri)
    }

    private fun createCameraImageFile(): File? {
        val cacheDirectory = File(cacheDir, "camera").apply { mkdirs() }
        if (!cacheDirectory.exists()) return null
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(cacheDirectory, "ocr_${timeStamp}.jpg")
    }

    private fun cleanupPendingPhotoIfNeeded(keepCurrent: Boolean) {
        val path = currentPhotoFilePath ?: return
        if (keepCurrent) return
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
        currentPhotoFilePath = null
        currentPhotoUri = null
    }

    private fun analyzeUri(uri: Uri) {
        analyzeJob?.cancel()
        val requestToken = SystemClock.elapsedRealtime()
        analyzeRequestToken = requestToken
        analyzeJob = lifecycleScope.launch {
            runCatching {
                currentStatus = "正在识别图片中的文字"
                showResult(status = currentStatus, clearTranslation = true)
                val image = InputImage.fromFilePath(this@ImageTranslationActivity, uri)
                val recognized = getOrCreateTextRecognizer().process(image).await().text
                handleRecognizedText(recognized, requestToken)
            }.onFailure { error ->
                if (analyzeRequestToken != requestToken) {
                    return@onFailure
                }
                currentStatus = "图片识别失败：${error.message ?: error.javaClass.simpleName}"
                showResult(status = currentStatus, clearTranslation = true)
            }
        }
    }

    private suspend fun handleRecognizedText(rawText: String, requestToken: Long) {
        if (analyzeRequestToken != requestToken) {
            return
        }
        val normalized = ImageTranslationFormatter.normalizeRecognizedText(rawText)
        if (normalized.isBlank()) {
            lastRecognizedText = ""
            lastTranslatedText = ""
            currentStatus = "未识别到文字，请拍清晰一点的内容"
            showResult(status = currentStatus, clearTranslation = true)
            return
        }

        translateRecognizedText(normalized, requestToken)
    }

    private suspend fun translateRecognizedText(recognizedText: String, requestToken: Long) {
        if (analyzeRequestToken != requestToken) {
            return
        }
        lastRecognizedText = recognizedText
        lastTranslatedText = ""
        currentStatus = "正在翻译：${selectedSourceLanguage().uiLabel} → ${selectedTargetLanguage().uiLabel}"
        showResult(status = currentStatus, clearTranslation = true)
        try {
            val translatedResult = translateWithBestAvailableEngine(recognizedText)
            if (analyzeRequestToken != requestToken) {
                return
            }
            lastTranslatedText = translatedResult.text
            currentStatus = when {
                translatedResult.text.isBlank() -> "翻译完成，但结果为空"
                translatedResult.backend == "hy-mt-native" -> "Hy-MT 离线翻译完成"
                translatedResult.backend == "ml-kit-fallback" -> "Hy-MT 当前不可用，已切换到 ML Kit 翻译"
                translatedResult.backend == "builtin-fallback" -> "当前显示内置词典结果"
                else -> "翻译完成"
            }
            showResult(status = currentStatus)
        } catch (error: Throwable) {
            lastTranslatedText = ""
            currentStatus = "翻译失败：${error.message ?: error.javaClass.simpleName}"
            showResult(status = currentStatus, clearTranslation = true)
        }
    }

    private suspend fun translateWithBestAvailableEngine(recognizedText: String): TranslationResult {
        val hyMtState = hyMtEngine.currentState()
        if (hyMtState is EngineState.Ready) {
            val hyMtResult = hyMtEngine.translate(
                text = recognizedText,
                sourceLanguage = selectedSourceLanguage().promptName,
                targetLanguage = selectedTargetLanguage().promptName
            )
            if (hyMtResult.backend == "hy-mt-native" && hyMtResult.text.isNotBlank()) {
                return hyMtResult
            }
        }

        return mlKitEngine.translate(
            text = recognizedText,
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
                hyMtEngine.prepareIfNeeded { progress ->
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
                    Toast.makeText(this@ImageTranslationActivity, currentModelStatus, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            currentModelStatus = "模型状态：${hyMtResult.message}，正在准备 ML Kit 翻译"
            currentModelProgress = null
            renderModelState(currentModelStatus, currentModelProgress)

            val mlKitResult = mlKitEngine.prepareIfNeeded(
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
                Toast.makeText(this@ImageTranslationActivity, currentModelStatus, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderModelState(status: String, progress: Int?) {
        modelStatusText.text = status
        if (progress == null) {
            modelProgressBar.isIndeterminate = true
        } else {
            modelProgressBar.isIndeterminate = false
            modelProgressBar.progress = progress.coerceIn(0, 100)
        }
        retryModelInitButton.isEnabled = true
    }

    private fun showResult(
        status: String,
        clearTranslation: Boolean = false,
        showToast: Boolean = false
    ) {
        currentStatus = status
        if (clearTranslation) {
            lastTranslatedText = ""
        }
        updateLanguageSelectors()
        updateLanguageLabels()
        val uiContent = ImageTranslationFormatter.buildUiContent(
            recognizedText = lastRecognizedText,
            translatedText = lastTranslatedText,
            status = currentStatus
        )
        recognizedTextView.text = uiContent.recognizedText
        translatedTextView.text = uiContent.translatedText
        resultStatusTextView.text = uiContent.statusText
        if (showToast || status.contains("失败") || status.contains("未选择") || status.contains("未拍到")) {
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val STATE_RECOGNIZED_TEXT = "state_recognized_text"
        private const val STATE_TRANSLATED_TEXT = "state_translated_text"
        private const val STATE_STATUS_TEXT = "state_status_text"
        private const val STATE_MODEL_STATUS_TEXT = "state_model_status_text"
        private const val STATE_MODEL_PROGRESS = "state_model_progress"
        private const val STATE_CURRENT_PHOTO_URI = "state_current_photo_uri"
        private const val STATE_CURRENT_PHOTO_PATH = "state_current_photo_path"
        private const val STATE_LAST_PREVIEW_URI = "state_last_preview_uri"
        private const val STATE_SOURCE_LANGUAGE_CODE = "state_source_language_code"
        private const val STATE_TARGET_LANGUAGE_CODE = "state_target_language_code"
    }
}
