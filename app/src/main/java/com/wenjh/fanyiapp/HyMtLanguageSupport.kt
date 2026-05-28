package com.wenjh.fanyiapp

object HyMtLanguageSupport {
    enum class OcrScript {
        LATIN,
        CHINESE,
        JAPANESE,
        KOREAN,
        DEVANAGARI
    }

    data class LanguageOption(
        val code: String,
        val promptName: String,
        val displayName: String,
        val uiLabel: String = displayName,
        val ocrScript: OcrScript = OcrScript.LATIN
    )

    val supportedLanguages: List<LanguageOption> = listOf(
        LanguageOption("ar", "Arabic", "Arabic", "阿拉伯语"),
        LanguageOption("bn", "Bengali", "Bengali", "孟加拉语"),
        LanguageOption("my", "Burmese", "Burmese", "缅甸语"),
        LanguageOption("yue", "Cantonese", "Cantonese", "粤语", OcrScript.CHINESE),
        LanguageOption("zh", "Chinese", "Chinese", "中文", OcrScript.CHINESE),
        LanguageOption("cs", "Czech", "Czech", "捷克语"),
        LanguageOption("nl", "Dutch", "Dutch", "荷兰语"),
        LanguageOption("en", "English", "English", "英语"),
        LanguageOption("tl", "Filipino", "Filipino", "菲律宾语"),
        LanguageOption("fr", "French", "French", "法语"),
        LanguageOption("de", "German", "German", "德语"),
        LanguageOption("gu", "Gujarati", "Gujarati", "古吉拉特语"),
        LanguageOption("he", "Hebrew", "Hebrew", "希伯来语"),
        LanguageOption("hi", "Hindi", "Hindi", "印地语", OcrScript.DEVANAGARI),
        LanguageOption("id", "Indonesian", "Indonesian", "印尼语"),
        LanguageOption("it", "Italian", "Italian", "意大利语"),
        LanguageOption("ja", "Japanese", "Japanese", "日语", OcrScript.JAPANESE),
        LanguageOption("kk", "Kazakh", "Kazakh", "哈萨克语"),
        LanguageOption("km", "Khmer", "Khmer", "高棉语"),
        LanguageOption("ko", "Korean", "Korean", "韩语", OcrScript.KOREAN),
        LanguageOption("ms", "Malay", "Malay", "马来语"),
        LanguageOption("mr", "Marathi", "Marathi", "马拉地语", OcrScript.DEVANAGARI),
        LanguageOption("mn", "Mongolian", "Mongolian", "蒙古语"),
        LanguageOption("fa", "Persian", "Persian", "波斯语"),
        LanguageOption("pl", "Polish", "Polish", "波兰语"),
        LanguageOption("pt", "Portuguese", "Portuguese", "葡萄牙语"),
        LanguageOption("ru", "Russian", "Russian", "俄语"),
        LanguageOption("es", "Spanish", "Spanish", "西班牙语"),
        LanguageOption("ta", "Tamil", "Tamil", "泰米尔语"),
        LanguageOption("te", "Telugu", "Telugu", "泰卢固语"),
        LanguageOption("bo", "Tibetan", "Tibetan", "藏语"),
        LanguageOption("zh-Hant", "Traditional Chinese", "Traditional Chinese", "繁体中文", OcrScript.CHINESE),
        LanguageOption("uk", "Ukrainian", "Ukrainian", "乌克兰语"),
        LanguageOption("ur", "Urdu", "Urdu", "乌尔都语"),
        LanguageOption("ug", "Uyghur", "Uyghur", "维吾尔语"),
        LanguageOption("vi", "Vietnamese", "Vietnamese", "越南语")
    )

    val defaultSource: LanguageOption = findByCode("en") ?: supportedLanguages.first()
    val defaultTarget: LanguageOption = findByCode("zh") ?: supportedLanguages.first()

    fun findByCode(code: String): LanguageOption? = supportedLanguages.firstOrNull { it.code == code }
}
