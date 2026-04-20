package com.wenjh.fanyiapp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import kotlin.math.sqrt

enum class AudioInputMode(val label: String) {
    PLAYBACK_CAPTURE("播放捕获+本地识别"),
    MICROPHONE("麦克风+本地识别")
}

class PlaybackCaptureAudioSource private constructor(
    val mode: AudioInputMode,
    private val audioRecord: AudioRecord,
    val sampleRate: Int,
    val channelCount: Int,
    val bufferSizeInBytes: Int
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val CHANNEL_COUNT_MONO = 1

        fun create(mediaProjection: MediaProjection?): PlaybackCaptureAudioSource {
            return createPlayback(mediaProjection) ?: createMicrophone()
                ?: throw IllegalStateException("无法初始化播放捕获或麦克风音频源")
        }

        fun canAttemptPlaybackCapture(mediaProjection: MediaProjection?): Boolean {
            return createPlayback(mediaProjection)?.also { it.release() } != null
        }

        fun microphoneFallbackAvailable(): Boolean {
            return createMicrophone()?.also { it.release() } != null
        }

        private fun createPlayback(mediaProjection: MediaProjection?): PlaybackCaptureAudioSource? {
            if (mediaProjection == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return runCatching {
                val minBuffer = AudioRecord.getMinBufferSize(
                    DEFAULT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) return null
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                val record = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(DEFAULT_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer.coerceAtLeast(DEFAULT_SAMPLE_RATE * 2))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return null
                }
                PlaybackCaptureAudioSource(
                    mode = AudioInputMode.PLAYBACK_CAPTURE,
                    audioRecord = record,
                    sampleRate = DEFAULT_SAMPLE_RATE,
                    channelCount = CHANNEL_COUNT_MONO,
                    bufferSizeInBytes = minBuffer.coerceAtLeast(DEFAULT_SAMPLE_RATE * 2)
                )
            }.getOrNull()
        }

        private fun createMicrophone(): PlaybackCaptureAudioSource? {
            return runCatching {
                val minBuffer = AudioRecord.getMinBufferSize(
                    DEFAULT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) return null
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    DEFAULT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer.coerceAtLeast(DEFAULT_SAMPLE_RATE * 2)
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return null
                }
                PlaybackCaptureAudioSource(
                    mode = AudioInputMode.MICROPHONE,
                    audioRecord = record,
                    sampleRate = DEFAULT_SAMPLE_RATE,
                    channelCount = CHANNEL_COUNT_MONO,
                    bufferSizeInBytes = minBuffer.coerceAtLeast(DEFAULT_SAMPLE_RATE * 2)
                )
            }.getOrNull()
        }

        fun normalizePcmLevel(buffer: ShortArray, read: Int): Float {
            if (read <= 0) return 0f
            var sum = 0.0
            for (i in 0 until read) {
                val sample = buffer[i].toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / read)
            return ((rms / 2000.0) * 100.0).coerceIn(0.0, 100.0).toFloat()
        }
    }

    fun start() {
        audioRecord.startRecording()
    }

    fun read(buffer: ShortArray): Int {
        return audioRecord.read(buffer, 0, buffer.size)
    }

    fun stop() {
        runCatching { audioRecord.stop() }
    }

    fun release() {
        audioRecord.release()
    }
}
