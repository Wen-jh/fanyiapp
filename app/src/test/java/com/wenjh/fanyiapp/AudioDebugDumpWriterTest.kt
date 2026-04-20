package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioDebugDumpWriterTest {
    @Test
    fun wavHeaderSizing_isAlways44Bytes() {
        val header = AudioDebugDumpWriter.createWavHeader(
            sampleRate = 16000,
            channelCount = 1,
            bitsPerSample = 16,
            pcmDataSize = 3200
        )

        assertEquals(44, header.size)
        assertEquals('R'.code.toByte(), header[0])
        assertEquals('W'.code.toByte(), header[8])
    }

    @Test
    fun pcmFilenameRotation_keepsNewestFilesUnderCap() {
        val dir = createTempDir(prefix = "dump-writer-")
        repeat(5) { index ->
            File(dir, "old-$index.pcm").apply {
                writeText("x")
                setLastModified(1000L + index)
            }
        }

        AudioDebugDumpWriter.rotateFiles(dir, maxFiles = 3)

        assertTrue(dir.listFiles().orEmpty().size <= 2)
        dir.deleteRecursively()
    }

    @Test
    fun disabledModeProducesNoWriter() {
        val dir = createTempDir(prefix = "dump-writer-")

        val writer = AudioDebugDumpWriter.create(
            baseDir = dir,
            enabled = false,
            preferWav = true,
            sampleRate = 16000,
            channelCount = 1
        )

        assertNull(writer)
        dir.deleteRecursively()
    }
}
