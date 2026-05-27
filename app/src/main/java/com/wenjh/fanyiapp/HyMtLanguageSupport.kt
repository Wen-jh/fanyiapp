package com.wenjh.fanyiapp

object HyMtLanguageSupport {
    data class LanguageOption(
        val code: String,
        val promptName: String,
        val displayName: String
    )

    val supportedLanguages: List<LanguageOption> = listOf(
        LanguageOption("ar", "Arabic", "Arabic"),
        LanguageOption("bn", "Bengali", "Bengali"),
        LanguageOption("my", "Burmese", "Burmese"),
        LanguageOption("yue", "Cantonese", "Cantonese"),
        LanguageOption("zh", "Chinese", "Chinese"),
        LanguageOption("cs", "Czech", "Czech"),
        LanguageOption("nl", "Dutch", "Dutch"),
        LanguageOption("en", "English", "English"),
        LanguageOption("tl", "Filipino", "Filipino"),
        LanguageOption("fr", "French", "French"),
        LanguageOption("de", "German", "German"),
        LanguageOption("gu", "Gujarati", "Gujarati"),
        LanguageOption("he", "Hebrew", "Hebrew"),
        LanguageOption("hi", "Hindi", "Hindi"),
        LanguageOption("id", "Indonesian", "Indonesian"),
        LanguageOption("it", "Italian", "Italian"),
        LanguageOption("ja", "Japanese", "Japanese"),
        LanguageOption("kk", "Kazakh", "Kazakh"),
        LanguageOption("km", "Khmer", "Khmer"),
        LanguageOption("ko", "Korean", "Korean"),
        LanguageOption("ms", "Malay", "Malay"),
        LanguageOption("mr", "Marathi", "Marathi"),
        LanguageOption("mn", "Mongolian", "Mongolian"),
        LanguageOption("fa", "Persian", "Persian"),
        LanguageOption("pl", "Polish", "Polish"),
        LanguageOption("pt", "Portuguese", "Portuguese"),
        LanguageOption("ru", "Russian", "Russian"),
        LanguageOption("es", "Spanish", "Spanish"),
        LanguageOption("ta", "Tamil", "Tamil"),
        LanguageOption("te", "Telugu", "Telugu"),
        LanguageOption("bo", "Tibetan", "Tibetan"),
        LanguageOption("zh-Hant", "Traditional Chinese", "Traditional Chinese"),
        LanguageOption("uk", "Ukrainian", "Ukrainian"),
        LanguageOption("ur", "Urdu", "Urdu"),
        LanguageOption("ug", "Uyghur", "Uyghur"),
        LanguageOption("vi", "Vietnamese", "Vietnamese")
    )

    val defaultSource: LanguageOption = findByCode("en") ?: supportedLanguages.first()
    val defaultTarget: LanguageOption = findByCode("zh") ?: supportedLanguages.first()

    fun findByCode(code: String): LanguageOption? = supportedLanguages.firstOrNull { it.code == code }
}
