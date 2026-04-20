package com.wenjh.fanyiapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var projectionManager: MediaProjectionManager
    private var projectionDataIntent: Intent? = null
    private var projectionResultCode: Int? = null

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestProjection()
        } else {
            showStatus("未授予录音权限，无法启动本地识别链路")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            projectionResultCode = result.resultCode
            projectionDataIntent = result.data
            startSubtitleService()
        } else {
            projectionResultCode = null
            projectionDataIntent = null
            showStatus("未授予投屏/播放捕获权限，将退回麦克风本地识别")
            startSubtitleService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        val overlayButton = findViewById<Button>(R.id.overlayButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        startButton.setOnClickListener { ensurePermissionsAndStart() }
        overlayButton.setOnClickListener { requestOverlayPermission() }
        stopButton.setOnClickListener {
            stopService(Intent(this, SubtitleOverlayService::class.java))
            showStatus("服务已停止")
        }

        showStatus("准备就绪：先授权悬浮窗，再点击开始。当前版本将优先尝试播放捕获+本地日语识别，失败时自动回退到麦克风本地识别。")
    }

    private fun ensurePermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            showStatus("请先授予悬浮窗权限")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        requestProjection()
    }

    private fun requestProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startSubtitleService() {
        val serviceIntent = Intent(this, SubtitleOverlayService::class.java).apply {
            action = SubtitleOverlayService.ACTION_START
            projectionResultCode?.let { putExtra(SubtitleOverlayService.EXTRA_RESULT_CODE, it) }
            projectionDataIntent?.let { putExtra(SubtitleOverlayService.EXTRA_DATA_INTENT, it) }
            putExtra(SubtitleOverlayService.EXTRA_ENABLE_AUDIO_DUMP, BuildConfig.DEFAULT_AUDIO_DUMP_ENABLED)
            putExtra(SubtitleOverlayService.EXTRA_AUDIO_DUMP_WAV, BuildConfig.DEFAULT_AUDIO_DUMP_WAV)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        showStatus("服务启动中：悬浮窗将展示播放捕获、本地识别、翻译和音量链路状态")
    }

    private fun showStatus(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
