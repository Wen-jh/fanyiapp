package com.wenjh.fanyiapp

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

enum class AudioDumpFormat {
    PCM,
    WAV
}

class AudioDebugDumpWriter private constructor(
    private val file: File,
    private val format: AudioDumpFormat,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitsPerSample: Int,
    private val output: FileOutputStream
) : Closeable {
    private var dataBytesWritten: Long = 0

    companion object {
        fun create(
            baseDir: File,
            enabled: Boolean,
            preferWav: Boolean,
            sampleRate: Int,
            channelCount: Int,
            maxFiles: Int = 3
        ): AudioDebugDumpWriter? {
            if (!enabled) return null
            baseDir.mkdirs()
            rotateFiles(baseDir, maxFiles)
            val format = if (preferWav) AudioDumpFormat.WAV else AudioDumpFormat.PCM
            val extension = if (format == AudioDumpFormat.WAV) "wav" else "pcm"
            val file = File(baseDir, buildFileName(System.currentTimeMillis(), extension))
            val output = FileOutputStream(file)
            val writer = AudioDebugDumpWriter(file, format, sampleRate, channelCount, 16, output)
            if (format == AudioDumpFormat.WAV) {
                output.write(createWavHeader(sampleRate, channelCount, 16, 0))
            }
            return writer
        }

        fun buildFileName(timestampMillis: Long, extension: String): String {
            return "capture-$timestampMillis.$extension"
        }

        fun rotateFiles(baseDir: File, maxFiles: Int) {
            if (maxFiles <= 0) return
            val files = baseDir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
            files.drop(maxFiles - 1).forEach { it.delete() }
        }

        fun createWavHeader(
            sampleRate: Int,
            channelCount: Int,
            bitsPerSample: Int,
            pcmDataSize: Long
        ): ByteArray {
            val byteRate = sampleRate * channelCount * bitsPerSample / 8
            val blockAlign = channelCount * bitsPerSample / 8
            val chunkSize = 36 + pcmDataSize.toInt()
            val header = ByteArray(44)
            fun writeIntLE(offset: Int, value: Int) {
                header[offset] = (value and 0xff).toByte()
                header[offset + 1] = ((value shr 8) and 0xff).toByte()
                header[offset + 2] = ((value shr 16) and 0xff).toByte()
                header[offset + 3] = ((value shr 24) and 0xff).toByte()
            }
            fun writeShortLE(offset: Int, value: Int) {
                header[offset] = (value and 0xff).toByte()
                header[offset + 1] = ((value shr 8) and 0xff).toByte()
            }
            "RIFF".toByteArray().copyInto(header, 0)
            writeIntLE(4, chunkSize)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            writeIntLE(16, 16)
            writeShortLE(20, 1)
            writeShortLE(22, channelCount)
            writeIntLE(24, sampleRate)
            writeIntLE(28, byteRate)
            writeShortLE(32, blockAlign)
            writeShortLE(34, bitsPerSample)
            "data".toByteArray().copyInto(header, 36)
            writeIntLE(40, pcmDataSize.toInt())
            return header
        }
    }

    fun write(samples: ShortArray, count: Int) {
        if (count <= 0) return
        val bytes = ByteArray(count * 2)
        var byteIndex = 0
        for (i in 0 until count) {
            val value = samples[i].toInt()
            bytes[byteIndex++] = (value and 0xff).toByte()
            bytes[byteIndex++] = ((value shr 8) and 0xff).toByte()
        }
        output.write(bytes)
        dataBytesWritten += bytes.size
    }

    fun outputFile(): File = file

    override fun close() {
        output.flush()
        output.close()
        if (format == AudioDumpFormat.WAV) {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(createWavHeader(sampleRate, channelCount, bitsPerSample, dataBytesWritten))
            }
        }
    }
}
