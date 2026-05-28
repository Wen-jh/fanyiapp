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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SubtitleControlActivity : AppCompatActivity() {
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
        setContentView(R.layout.activity_subtitle_control)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        findViewById<Button>(R.id.overlayButton).setOnClickListener { requestOverlayPermission() }
        startButton.setOnClickListener { ensurePermissionsAndStart() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, SubtitleOverlayService::class.java))
            showStatus("服务已停止")
        }

        showStatus("准备就绪：先授权悬浮窗，再点击开始实时字幕翻译。悬浮窗仅显示识别与翻译结果，控制和链路状态保留在本页。")
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
        showStatus("服务启动中：悬浮窗将只显示识别字幕和翻译字幕，权限与运行状态请在本页查看。")
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }
}
