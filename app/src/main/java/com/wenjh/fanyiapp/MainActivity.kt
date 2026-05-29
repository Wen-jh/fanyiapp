package com.wenjh.fanyiapp

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var sourceLanguageText: TextView
    private lateinit var targetLanguageText: TextView

    private var sourceLanguage = UiLanguage("zh", "简体中文")
    private var targetLanguage = UiLanguage("en", "英语")

    private val languageOptions = listOf(
        UiLanguage("zh", "简体中文"),
        UiLanguage("zh-Hant", "繁体中文"),
        UiLanguage("en", "英语"),
        UiLanguage("ja", "日语"),
        UiLanguage("ko", "韩语"),
        UiLanguage("it", "意大利语"),
        UiLanguage("fr", "法语"),
        UiLanguage("es", "西班牙语（西班牙）"),
        UiLanguage("es-MX", "西班牙语（墨西哥）"),
        UiLanguage("pt-PT", "葡萄牙语（葡萄牙）"),
        UiLanguage("pt-BR", "葡萄牙语（巴西）"),
        UiLanguage("vi", "越南语"),
        UiLanguage("de", "德语"),
        UiLanguage("ru", "俄语")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sourceLanguageText = findViewById(R.id.sourceLanguageText)
        targetLanguageText = findViewById(R.id.targetLanguageText)
        updateLanguageText()

        findViewById<View>(R.id.sourceLanguageButton).setOnClickListener {
            showLanguagePicker(selectingSource = true)
        }
        findViewById<View>(R.id.targetLanguageButton).setOnClickListener {
            showLanguagePicker(selectingSource = false)
        }
        findViewById<View>(R.id.swapLanguageButton).setOnClickListener {
            swapLanguages()
        }
        findViewById<View>(R.id.textTranslateButton).setOnClickListener {
            startActivity(Intent(this, TextTranslationActivity::class.java))
        }
        findViewById<View>(R.id.imageTranslateButton).setOnClickListener {
            startActivity(Intent(this, ImageTranslationActivity::class.java))
        }
        findViewById<View>(R.id.subtitleTranslateButton).setOnClickListener {
            startActivity(Intent(this, SubtitleControlActivity::class.java))
        }
        findViewById<View>(R.id.voiceTranslateButton).setOnClickListener {
            startActivity(Intent(this, SubtitleControlActivity::class.java))
        }
        findViewById<View>(R.id.documentTranslateButton).setOnClickListener {
            Toast.makeText(this, R.string.main_coming_soon, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.screenTranslateButton).setOnClickListener {
            Toast.makeText(this, R.string.main_coming_soon, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun showLanguagePicker(selectingSource: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var pendingSource = sourceLanguage
        var pendingTarget = targetLanguage
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
            gravity = Gravity.CENTER_VERTICAL
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
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setOnClickListener {
                sourceLanguage = pendingSource
                targetLanguage = pendingTarget
                updateLanguageText()
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
                createDialogLanguagePill(pendingSource.name, activeSource) {
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
                createDialogLanguagePill(pendingTarget.name, !activeSource) {
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

            val checkedLanguage = if (activeSource) pendingSource else pendingTarget
            listContainer.removeAllViews()
            languageOptions.forEachIndexed { index, language ->
                listContainer.addView(
                    createLanguageRow(
                        language = language,
                        checked = language.code == checkedLanguage.code,
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

            val text = TextView(this@MainActivity).apply {
                text = label
                setTextColor(if (selected) Color.parseColor("#2F8CFF") else Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(88)
                maxLines = 1
            }
            val chevron = ImageView(this@MainActivity).apply {
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
        language: UiLanguage,
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
            setPadding(0, 0, 0, 0)
        }
        val label = TextView(this).apply {
            text = language.name
            setTextColor(Color.parseColor("#F4F4F5"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        val radio = View(this).apply {
            setBackgroundResource(if (checked) R.drawable.bg_radio_checked else R.drawable.bg_radio_unchecked)
        }
        row.addView(label, LinearLayout.LayoutParams(0, dp(72), 1f))
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
        val oldSource = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = oldSource
        updateLanguageText()
    }

    private fun updateLanguageText() {
        sourceLanguageText.text = sourceLanguage.name
        targetLanguageText.text = targetLanguage.name
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class UiLanguage(
        val code: String,
        val name: String
    )
}
