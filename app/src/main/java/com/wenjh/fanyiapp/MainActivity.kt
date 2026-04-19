package com.wenjh.fanyiapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
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
            showStatus("未授予录音权限，无法启动识别链路")
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
            showStatus("未授予投屏/播放捕获权限，仍可只用麦克风识别")
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

        startButton.setOnClickListener {
            ensurePermissionsAndStart()
        }

        overlayButton.setOnClickListener {
            requestOverlayPermission()
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, SubtitleOverlayService::class.java))
            showStatus("服务已停止")
        }

        showStatus("准备就绪：先授权悬浮窗，再点击开始。悬浮窗将显示采集/识别/翻译全过程。")
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
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        showStatus("服务启动中：悬浮窗将展示是否收到音频、识别中、翻译中等过程状态")
    }

    private fun showStatus(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

class AudioCaptureProbe {
    fun canAttemptPlaybackCapture(projection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuffer <= 0) return false

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer.coerceAtLeast(16000))
                .setAudioPlaybackCaptureConfig(config)
                .build()

            val ok = record.state == AudioRecord.STATE_INITIALIZED
            record.release()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun microphoneFallbackAvailable(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer.coerceAtLeast(16000)
        )
        val ok = record.state == AudioRecord.STATE_INITIALIZED
        record.release()
        return ok
    }
}
