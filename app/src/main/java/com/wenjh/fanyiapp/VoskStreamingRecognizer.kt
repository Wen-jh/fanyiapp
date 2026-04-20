package com.wenjh.fanyiapp

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File

sealed class AsrEvent {
    data class Partial(val text: String) : AsrEvent()
    data class Final(val text: String) : AsrEvent()
}

class VoskStreamingRecognizer(modelDir: File, sampleRate: Float) : Closeable {
    private val model = Model(modelDir.absolutePath)
    private val recognizer = Recognizer(model, sampleRate)
    private var lastPartial: String = ""
    private var lastFinal: String = ""

    companion object {
        fun parsePartial(json: String): String {
            return runCatching {
                JSONObject(json).optString("partial").trim()
            }.getOrDefault("")
        }

        fun parseFinal(json: String): String {
            return runCatching {
                JSONObject(json).optString("text").trim()
            }.getOrDefault("")
        }

        fun nextPartialEvent(
            json: String,
            lastPartial: String,
            lastFinal: String
        ): AsrEvent.Partial? {
            val text = parsePartial(json)
            return if (text.isBlank() || text == lastPartial || text == lastFinal) {
                null
            } else {
                AsrEvent.Partial(text)
            }
        }

        fun nextFinalEvent(json: String, lastFinal: String): AsrEvent.Final? {
            val text = parseFinal(json)
            return if (text.isBlank() || text == lastFinal) {
                null
            } else {
                AsrEvent.Final(text)
            }
        }
    }

    fun accept(samples: ShortArray, count: Int): AsrEvent? {
        if (count <= 0) return null
        val done = recognizer.acceptWaveForm(samples, count)
        return if (done) {
            nextFinalEvent(recognizer.result, lastFinal)?.also { event ->
                lastFinal = event.text
                lastPartial = ""
            }
        } else {
            nextPartialEvent(recognizer.partialResult, lastPartial, lastFinal)?.also { event ->
                lastPartial = event.text
            }
        }
    }

    fun flushFinal(): AsrEvent.Final? {
        return nextFinalEvent(recognizer.finalResult, lastFinal)?.also { event ->
            lastFinal = event.text
            lastPartial = ""
        }
    }

    override fun close() {
        recognizer.close()
        model.close()
    }
}
