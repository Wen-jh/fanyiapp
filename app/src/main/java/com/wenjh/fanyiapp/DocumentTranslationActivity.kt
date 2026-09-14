package com.wenjh.fanyiapp

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文档翻译：选择 .docx / .pptx → 抽取文字 → 分段送 Hy-MT 离线模型翻译 → 本地保存最近记录。
 *
 * 只做了「抽取纯文本 + 翻译」，不改动原文件排版；翻译结果按段落块展示，便于对照。
 */
class DocumentTranslationActivity : AppCompatActivity() {

    private lateinit var recentContainer: LinearLayout
    private lateinit var emptyContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var clearButton: TextView
    private lateinit var pickButton: TextView
    private lateinit var translationEngine: PhotoTranslationEngine

    private var busy = false

    private val sourceLanguage = HyMtLanguageSupport.defaultSource
    private val targetLanguage = HyMtLanguageSupport.defaultTarget

    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) translateDocument(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_translation)

        recentContainer = findViewById(R.id.documentRecentContainer)
        emptyContainer = findViewById(R.id.documentEmptyContainer)
        statusText = findViewById(R.id.documentStatusText)
        clearButton = findViewById(R.id.documentClearButton)
        pickButton = findViewById(R.id.documentPickButton)
        translationEngine = HyMtTranslationEngine(applicationContext)

        findViewById<ImageButton>(R.id.documentBackButton).setOnClickListener { finish() }
        pickButton.setOnClickListener { launchPicker() }
        clearButton.setOnClickListener {
            DocumentTranslationStore.clear(this)
            renderHistory()
            Toast.makeText(this, R.string.document_history_cleared, Toast.LENGTH_SHORT).show()
        }

        renderHistory()
        prepareEngine()
    }

    override fun onDestroy() {
        translationEngine.release()
        super.onDestroy()
    }

    private fun launchPicker() {
        if (busy) return
        runCatching {
            pickDocument.launch(
                arrayOf(
                    DocumentTextExtractor.MIME_DOCX,
                    DocumentTextExtractor.MIME_PPTX,
                    "application/octet-stream"
                )
            )
        }.onFailure {
            Toast.makeText(this, R.string.document_pick_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun prepareEngine() {
        lifecycleScope.launch {
            runCatching {
                translationEngine.prepareIfNeeded { progress ->
                    runOnUiThread { setStatus(progress.message, true) }
                }
            }
            setStatus("", false)
        }
    }

    private fun translateDocument(uri: Uri) {
        if (busy) return
        busy = true
        pickButton.alpha = 0.5f
        setStatus(getString(R.string.document_parsing), true)

        lifecycleScope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) {
                    DocumentTextExtractor.queryDisplayName(this@DocumentTranslationActivity, uri)
                }
                val extracted = withContext(Dispatchers.IO) {
                    DocumentTextExtractor.extract(this@DocumentTranslationActivity, uri, fileName)
                }

                val paragraphs = when (extracted) {
                    is DocumentTextExtractor.Result.Success -> extracted.paragraphs
                    DocumentTextExtractor.Result.UnsupportedFormat -> {
                        failWith(getString(R.string.document_unsupported))
                        return@launch
                    }
                    DocumentTextExtractor.Result.Empty -> {
                        failWith(getString(R.string.document_parse_empty))
                        return@launch
                    }
                    is DocumentTextExtractor.Result.Failure -> {
                        failWith(getString(R.string.document_parse_failed))
                        return@launch
                    }
                }

                val chunks = DocumentTextExtractor.chunkParagraphs(paragraphs)
                if (chunks.isEmpty()) {
                    failWith(getString(R.string.document_parse_empty))
                    return@launch
                }

                if (translationEngine.currentState() !is EngineState.Ready) {
                    setStatus(getString(R.string.document_engine_preparing), true)
                    val prepared = runCatching {
                        translationEngine.prepareIfNeeded { progress ->
                            runOnUiThread { setStatus(progress.message, true) }
                        }
                    }.getOrElse { PreparationResult(false, it.message ?: it.javaClass.simpleName) }
                    if (!prepared.ready) {
                        failWith(prepared.message)
                        return@launch
                    }
                }

                val translations = ArrayList<String>(chunks.size)
                chunks.forEachIndexed { index, chunk ->
                    setStatus(getString(R.string.document_translating, index + 1, chunks.size), true)
                    val result = translationEngine.translate(
                        text = chunk,
                        sourceLanguage = sourceLanguage.promptName,
                        targetLanguage = targetLanguage.promptName
                    )
                    translations.add(result.text.ifBlank { getString(R.string.conversation_no_result) })
                }

                val record = DocumentTranslationRecord(
                    id = System.currentTimeMillis().toString(),
                    fileName = fileName,
                    languagePair = "${sourceLanguage.displayName} → ${targetLanguage.displayName}",
                    timestamp = System.currentTimeMillis(),
                    paragraphs = chunks,
                    translations = translations
                )
                DocumentTranslationStore.save(this@DocumentTranslationActivity, record)
                renderHistory()
                setStatus(getString(R.string.document_translate_done, chunks.size), true)
                showResultDialog(record)
            } catch (error: Throwable) {
                failWith(getString(R.string.document_translate_failed, error.message ?: error.javaClass.simpleName))
            } finally {
                busy = false
                pickButton.alpha = 1f
            }
        }
    }

    private fun failWith(message: String) {
        setStatus(message, true)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setStatus(message: String, visible: Boolean) {
        statusText.text = message
        statusText.visibility = if (visible && message.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun renderHistory() {
        val records = DocumentTranslationStore.load(this)
        recentContainer.removeAllViews()
        if (records.isEmpty()) {
            emptyContainer.visibility = View.VISIBLE
            clearButton.visibility = View.GONE
            return
        }
        emptyContainer.visibility = View.GONE
        clearButton.visibility = View.VISIBLE
        records.forEach { record ->
            val item = layoutInflater.inflate(R.layout.item_document_recent, recentContainer, false)
            item.findViewById<TextView>(R.id.recentDocumentName).text = record.fileName
            item.findViewById<TextView>(R.id.recentDocumentMeta).text = buildString {
                append(record.languagePair)
                append(" · ")
                append(getString(R.string.document_paragraph_count, record.paragraphs.size))
                append(" · ")
                append(DateFormat.format("MM-dd HH:mm", record.timestamp))
            }
            item.findViewById<TextView>(R.id.recentDocumentPreview).text = record.previewText()
            item.setOnClickListener { showResultDialog(record) }
            recentContainer.addView(item)
        }
    }

    private fun showResultDialog(record: DocumentTranslationRecord) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_doc_card)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        root.addView(TextView(this).apply {
            text = record.fileName
            setTextColor(Color.parseColor("#F2F2F4"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })

        root.addView(TextView(this).apply {
            text = "${record.languagePair} · ${getString(R.string.document_paragraph_count, record.paragraphs.size)}"
            setTextColor(Color.parseColor("#8A8A8E"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        })

        val blocks = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        record.paragraphs.forEachIndexed { index, original ->
            blocks.addView(TextView(this).apply {
                text = original
                setTextColor(Color.parseColor("#80808A"))
                textSize = 13f
                setPadding(0, dp(12), 0, dp(4))
            })
            blocks.addView(TextView(this).apply {
                text = record.translations.getOrNull(index).orEmpty()
                setTextColor(Color.parseColor("#F2F2F4"))
                textSize = 15f
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        }

        root.addView(
            ScrollView(this).apply { addView(blocks) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.document_close)
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_doc_button)
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(dp(160), dp(46)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
        )

        val wrapper = FrameLayout(this).apply {
            setPadding(dp(16), dp(24), dp(16), dp(24))
            addView(
                root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        dialog.setContentView(wrapper)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.82f).toInt()
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
