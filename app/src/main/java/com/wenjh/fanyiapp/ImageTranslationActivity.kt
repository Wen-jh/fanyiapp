package com.wenjh.fanyiapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
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
    private lateinit var sourceLanguageSpinner: Spinner
    private lateinit var targetLanguageSpinner: Spinner
    private lateinit var translationEngine: PhotoTranslationEngine

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var analyzeJob: Job? = null
    private val languageOptions = HyMtLanguageSupport.supportedLanguages
    private var suppressLanguageSelectionCallback = false

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
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner)
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner)
        translationEngine = HyMtTranslationEngine(applicationContext)

        restoreState(savedInstanceState)
        setupLanguageSpinners()
        updateLanguageLabels()

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
        translationEngine.release()
        runCatching { textRecognizer.close() }
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
        currentModelProgress = savedInstanceState.getInt(STATE_MODEL_PROGRESS, -1)
            .takeIf { it >= 0 }
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
                onLanguageSelectionChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        targetLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressLanguageSelectionCallback) return
                selectedTargetLanguageCode = languageOptions[position].code
                onLanguageSelectionChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun onLanguageSelectionChanged() {
        updateLanguageLabels()
        lastTranslatedText = ""
        currentStatus = "已切换翻译方向：${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}"
        showResult(status = currentStatus)
    }

    private fun updateLanguageLabels() {
        recognizedLabelTextView.text = "识别出的原文（${selectedSourceLanguage().promptName}）"
        translatedLabelTextView.text = "翻译结果（${selectedTargetLanguage().promptName}）"
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

    private fun defaultReadyStatus(): String {
        return "准备就绪：可拍照或从相册选择图片（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
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
                val engineState = translationEngine.currentState()
                if (engineState !is EngineState.Ready) {
                    currentStatus = "Hy-MT 模型尚未就绪，请稍候"
                    showResult(status = currentStatus, clearTranslation = true)
                    prepareTranslationEngine(forceToast = true)
                    return@launch
                }
                currentStatus = "正在识别图片中的文字"
                showResult(status = currentStatus, clearTranslation = true)
                val image = InputImage.fromFilePath(this@ImageTranslationActivity, uri)
                val recognized = textRecognizer.process(image).await().text
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
        currentStatus = "正在进行 Hy-MT 离线翻译（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
        showResult(status = currentStatus, clearTranslation = true)
        try {
            val translatedResult = translationEngine.translate(
                text = recognizedText,
                sourceLanguage = selectedSourceLanguage().promptName,
                targetLanguage = selectedTargetLanguage().promptName
            )
            if (analyzeRequestToken != requestToken) {
                return
            }
            lastTranslatedText = translatedResult.text
            currentStatus = when {
                translatedResult.text.isBlank() -> "Hy-MT 离线翻译完成，但结果为空（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                translatedResult.backend == "builtin-fallback" -> "Hy-MT 未接通，当前显示内置词典结果（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
                else -> "Hy-MT 离线翻译完成（${selectedSourceLanguage().promptName} → ${selectedTargetLanguage().promptName}）"
            }
            showResult(status = currentStatus)
        } catch (error: Throwable) {
            lastTranslatedText = ""
            currentStatus = "Hy-MT 离线翻译失败：${error.message ?: error.javaClass.simpleName}"
            showResult(status = currentStatus, clearTranslation = true)
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
