package com.wenjh.fanyiapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.textTranslateButton).setOnClickListener {
            startActivity(Intent(this, TextTranslationActivity::class.java))
        }
        findViewById<Button>(R.id.imageTranslateButton).setOnClickListener {
            startActivity(Intent(this, ImageTranslationActivity::class.java))
        }
        findViewById<Button>(R.id.subtitleTranslateButton).setOnClickListener {
            startActivity(Intent(this, SubtitleControlActivity::class.java))
        }

        showStatus("准备就绪：当前支持 3 个功能：直接输入翻译、拍照翻译、实时翻译字幕。")
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }
}
