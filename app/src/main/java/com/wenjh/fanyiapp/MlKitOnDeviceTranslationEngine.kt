package com.wenjh.fanyiapp

import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class MlKitOnDeviceTranslationEngine {
    private var translator: Translator? = null
    private var preparedSourceCode: String? = null
    private var preparedTargetCode: String? = null
    private var isReady: Boolean = false

    suspend fun prepareIfNeeded(sourceCode: String, targetCode: String): PreparationResult {
        val mlKitSource = toMlKitLanguageCode(sourceCode)
            ?: return PreparationResult(false, "ML Kit 暂不支持源语言：$sourceCode")
        val mlKitTarget = toMlKitLanguageCode(targetCode)
            ?: return PreparationResult(false, "ML Kit 暂不支持目标语言：$targetCode")

        if (isReady && preparedSourceCode == mlKitSource && preparedTargetCode == mlKitTarget && translator != null) {
            return PreparationResult(true, "ML Kit 翻译模型已就绪")
        }

        translator?.close()
        translator = com.google.mlkit.nl.translate.Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(mlKitSource)
                .setTargetLanguage(mlKitTarget)
                .build()
        )

        return try {
            translator?.downloadModelIfNeeded()?.await()
            preparedSourceCode = mlKitSource
            preparedTargetCode = mlKitTarget
            isReady = true
            PreparationResult(true, "ML Kit 翻译模型已就绪")
        } catch (error: Throwable) {
            isReady = false
            PreparationResult(false, "ML Kit 模型准备失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    suspend fun translate(text: String, sourceCode: String, targetCode: String): TranslationResult {
        val preparation = prepareIfNeeded(sourceCode, targetCode)
        if (!preparation.ready) {
            error(preparation.message)
        }
        val translated = translator?.translate(text)?.await()?.trim().orEmpty()
        return TranslationResult(
            text = translated,
            backend = "ml-kit-fallback",
            statusMessage = "Used ML Kit on-device translation"
        )
    }

    fun isPreparedFor(sourceCode: String, targetCode: String): Boolean {
        return isReady &&
            preparedSourceCode == toMlKitLanguageCode(sourceCode) &&
            preparedTargetCode == toMlKitLanguageCode(targetCode)
    }

    fun release() {
        translator?.close()
        translator = null
        preparedSourceCode = null
        preparedTargetCode = null
        isReady = false
    }

    private fun toMlKitLanguageCode(code: String): String? {
        return when (code.lowercase()) {
            "zh", "zh-hant", "yue" -> "zh"
            "en" -> "en"
            "ja" -> "ja"
            "ko" -> "ko"
            "fr" -> "fr"
            "de" -> "de"
            "it" -> "it"
            "es", "es-mx" -> "es"
            "pt", "pt-pt", "pt-br" -> "pt"
            "ru" -> "ru"
            "ar" -> "ar"
            "hi" -> "hi"
            "bn" -> "bn"
            "ta" -> "ta"
            "te" -> "te"
            "nl" -> "nl"
            "pl" -> "pl"
            "cs" -> "cs"
            "uk" -> "uk"
            "vi" -> "vi"
            "id" -> "id"
            "ms" -> "ms"
            else -> null
        }
    }
}
