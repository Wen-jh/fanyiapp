package com.wenjh.fanyiapp

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.ImageView
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
    private lateinit var resultText: TextView
    private lateinit var takePhotoButton: Button
    private lateinit var choosePhotoButton: Button

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var analyzeJob: Job? = null

    private var lastRecognizedText: String = ""
    private var lastTranslatedText: String = ""
    private var currentStatus: String = "准备就绪：可拍照或从相册选择英文图片（内置离线翻译）"
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
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            showResult(status = "未选择图片", showToast = true)
            return@registerForActivityResult
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
        resultText = findViewById(R.id.resultText)
        takePhotoButton = findViewById(R.id.takePhotoButton)
        choosePhotoButton = findViewById(R.id.choosePhotoButton)

        restoreState(savedInstanceState)

        takePhotoButton.setOnClickListener {
            launchHighResolutionCamera()
        }
        choosePhotoButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        showResult(status = currentStatus)
        restorePreviewIfPossible()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_RECOGNIZED_TEXT, lastRecognizedText)
        outState.putString(STATE_TRANSLATED_TEXT, lastTranslatedText)
        outState.putString(STATE_STATUS_TEXT, currentStatus)
        outState.putString(STATE_CURRENT_PHOTO_URI, currentPhotoUri?.toString())
        outState.putString(STATE_CURRENT_PHOTO_PATH, currentPhotoFilePath)
        outState.putString(STATE_LAST_PREVIEW_URI, lastPreviewUri)
    }

    override fun onDestroy() {
        analyzeJob?.cancel()
        runCatching { textRecognizer.close() }
        super.onDestroy()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        lastRecognizedText = savedInstanceState.getString(STATE_RECOGNIZED_TEXT).orEmpty()
        lastTranslatedText = savedInstanceState.getString(STATE_TRANSLATED_TEXT).orEmpty()
        currentStatus = savedInstanceState.getString(STATE_STATUS_TEXT).orEmpty()
            .ifBlank { "准备就绪：可拍照或从相册选择英文图片（内置离线翻译）" }
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
                currentStatus = "正在识别图片中的英文"
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
            currentStatus = "未识别到英文，请拍清晰一点的英文内容"
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
        currentStatus = "正在进行内置离线翻译"
        showResult(status = currentStatus, clearTranslation = true)
        try {
            val translatedResult = OfflineEnglishChineseTranslator.translate(recognizedText)
            if (analyzeRequestToken != requestToken) {
                return
            }
            lastTranslatedText = translatedResult.text
            currentStatus = when {
                translatedResult.text.isBlank() -> "离线翻译完成，但结果为空"
                translatedResult.usedBuiltinPhrase -> "离线翻译完成（内置短语）"
                translatedResult.usedWordFallback -> "离线翻译完成（内置词典）"
                else -> "离线翻译完成"
            }
            showResult(status = currentStatus)
        } catch (error: Throwable) {
            lastTranslatedText = ""
            currentStatus = "离线翻译失败：${error.message ?: error.javaClass.simpleName}"
            showResult(status = currentStatus, clearTranslation = true)
        }
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
        resultText.text = ImageTranslationFormatter.composeResult(
            recognizedText = lastRecognizedText,
            translatedText = lastTranslatedText,
            status = currentStatus
        )
        if (showToast || status.contains("失败") || status.contains("未选择") || status.contains("未拍到")) {
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val STATE_RECOGNIZED_TEXT = "state_recognized_text"
        private const val STATE_TRANSLATED_TEXT = "state_translated_text"
        private const val STATE_STATUS_TEXT = "state_status_text"
        private const val STATE_CURRENT_PHOTO_URI = "state_current_photo_uri"
        private const val STATE_CURRENT_PHOTO_PATH = "state_current_photo_path"
        private const val STATE_LAST_PREVIEW_URI = "state_last_preview_uri"
    }
}
