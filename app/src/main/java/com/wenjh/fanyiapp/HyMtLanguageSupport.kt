package com.wenjh.fanyiapp

object HyMtLanguageSupport {
    enum class OcrScript {
        LATIN,
        CHINESE,
        JAPANESE,
        KOREAN,
        CYRILLIC,
        ARABIC,
        HEBREW,
        DEVANAGARI,
        OTHER
    }

    data class LanguageOption(
        val code: String,
        val promptName: String,
        val displayName: String,
        val uiLabel: String = displayName,
        val ocrScript: OcrScript = OcrScript.LATIN
    )

    val supportedLanguages: List<LanguageOption> = listOf(
        LanguageOption("ar", "Arabic", "Arabic", "阿拉伯语", OcrScript.ARABIC),
        LanguageOption("bn", "Bengali", "Bengali", "孟加拉语", OcrScript.OTHER),
        LanguageOption("my", "Burmese", "Burmese", "缅甸语", OcrScript.OTHER),
        LanguageOption("yue", "Cantonese", "Cantonese", "粤语", OcrScript.CHINESE),
        LanguageOption("zh", "Chinese", "Chinese", "中文", OcrScript.CHINESE),
        LanguageOption("cs", "Czech", "Czech", "捷克语"),
        LanguageOption("nl", "Dutch", "Dutch", "荷兰语"),
        LanguageOption("en", "English", "English", "英语"),
        LanguageOption("tl", "Filipino", "Filipino", "菲律宾语"),
        LanguageOption("fr", "French", "French", "法语"),
        LanguageOption("de", "German", "German", "德语"),
        LanguageOption("gu", "Gujarati", "Gujarati", "古吉拉特语", OcrScript.OTHER),
        LanguageOption("he", "Hebrew", "Hebrew", "希伯来语", OcrScript.HEBREW),
        LanguageOption("hi", "Hindi", "Hindi", "印地语", OcrScript.DEVANAGARI),
        LanguageOption("id", "Indonesian", "Indonesian", "印尼语"),
        LanguageOption("it", "Italian", "Italian", "意大利语"),
        LanguageOption("ja", "Japanese", "Japanese", "日语", OcrScript.JAPANESE),
        LanguageOption("kk", "Kazakh", "Kazakh", "哈萨克语", OcrScript.CYRILLIC),
        LanguageOption("km", "Khmer", "Khmer", "高棉语", OcrScript.OTHER),
        LanguageOption("ko", "Korean", "Korean", "韩语", OcrScript.KOREAN),
        LanguageOption("ms", "Malay", "Malay", "马来语"),
        LanguageOption("mr", "Marathi", "Marathi", "马拉地语", OcrScript.DEVANAGARI),
        LanguageOption("mn", "Mongolian", "Mongolian", "蒙古语", OcrScript.CYRILLIC),
        LanguageOption("fa", "Persian", "Persian", "波斯语", OcrScript.ARABIC),
        LanguageOption("pl", "Polish", "Polish", "波兰语"),
        LanguageOption("pt", "Portuguese", "Portuguese", "葡萄牙语"),
        LanguageOption("ru", "Russian", "Russian", "俄语", OcrScript.CYRILLIC),
        LanguageOption("es", "Spanish", "Spanish", "西班牙语"),
        LanguageOption("ta", "Tamil", "Tamil", "泰米尔语", OcrScript.OTHER),
        LanguageOption("te", "Telugu", "Telugu", "泰卢固语", OcrScript.OTHER),
        LanguageOption("bo", "Tibetan", "Tibetan", "藏语", OcrScript.OTHER),
        LanguageOption("zh-Hant", "Traditional Chinese", "Traditional Chinese", "繁体中文", OcrScript.CHINESE),
        LanguageOption("uk", "Ukrainian", "Ukrainian", "乌克兰语", OcrScript.CYRILLIC),
        LanguageOption("ur", "Urdu", "Urdu", "乌尔都语", OcrScript.ARABIC),
        LanguageOption("ug", "Uyghur", "Uyghur", "维吾尔语", OcrScript.ARABIC),
        LanguageOption("vi", "Vietnamese", "Vietnamese", "越南语")
    )

    /** 源语言可选「自动检测」，目标语言不需要 */
    val autoDetect: LanguageOption = LanguageOption("auto", "Auto", "Auto", "自动检测")

    val sourceLanguages: List<LanguageOption> = listOf(autoDetect) + supportedLanguages

    val targetLanguages: List<LanguageOption> = supportedLanguages

    val defaultSource: LanguageOption = findByCode("en") ?: supportedLanguages.first()
    val defaultTarget: LanguageOption = findByCode("zh") ?: supportedLanguages.first()

    fun findByCode(code: String): LanguageOption? = supportedLanguages.firstOrNull { it.code == code }

    /** 用 promptName（如 "English"）反查语言，找不到返回 null */
    fun findByPromptName(promptName: String?): LanguageOption? {
        val name = promptName?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (name.equals(autoDetect.promptName, ignoreCase = true)) return autoDetect
        return supportedLanguages.firstOrNull { it.promptName.equals(name, ignoreCase = true) }
    }

    /** 用界面标签（如 "英语"）反查语言 */
    fun findByUiLabel(label: String?): LanguageOption? {
        val name = label?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (name == autoDetect.uiLabel) return autoDetect
        return supportedLanguages.firstOrNull { it.uiLabel == name }
    }

    /** 判断一段文本属于哪种语言（按文字系统判定，拉丁语系无法细判时返回 null） */
    fun detectLanguageByScript(text: String): LanguageOption? {
        val counts = ScriptCounts.of(text)
        if (counts.letters == 0) return null
        return when {
            counts.kana > 0 -> findByCode("ja")
            counts.hangul > 0 -> findByCode("ko")
            counts.han > 0 -> findByCode("zh")
            counts.cyrillic > 0 -> findByCode("ru")
            counts.arabic > 0 -> findByCode("ar")
            counts.hebrew > 0 -> findByCode("he")
            counts.devanagari > 0 -> findByCode("hi")
            counts.bengali > 0 -> findByCode("bn")
            counts.gujarati > 0 -> findByCode("gu")
            counts.tamil > 0 -> findByCode("ta")
            counts.telugu > 0 -> findByCode("te")
            counts.tibetan > 0 -> findByCode("bo")
            counts.myanmar > 0 -> findByCode("my")
            counts.khmer > 0 -> findByCode("km")
            else -> null
        }
    }

    /**
     * 校验模型输出是否真的落在目标语言上。
     *
     * 1.25bit 量化的 Hy-MT 很容易无视指令、直接吐中文，
     * 所以每次翻译后都做一次文字系统检查，不通过就让引擎用强指令重试。
     */
    fun outputMatchesTarget(text: String, targetCode: String, sourceText: String = ""): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        val counts = ScriptCounts.of(body)
        if (counts.letters == 0) return true // 纯数字/符号/表情，不做判断

        return when (targetCode) {
            "zh", "zh-Hant", "yue" ->
                counts.han > 0 && counts.kana == 0 && counts.hangul == 0

            // 日语只用汉字也能成句（"電話"），所以：有假名就一定算对；
            // 全是汉字时，只有和原文不同才认（避免把中文原文当成日语）
            "ja" ->
                counts.kana > 0 ||
                    (counts.han > 0 && counts.hangul == 0 && !sameAsSource(body, sourceText))

            "ko" -> counts.hangul > 0

            "ru", "uk", "kk", "mn" -> counts.cyrillic > 0

            "ar", "fa", "ur", "ug" -> counts.arabic > 0

            "he" -> counts.hebrew > 0

            "hi", "mr" -> counts.devanagari > 0
            "bn" -> counts.bengali > 0
            "gu" -> counts.gujarati > 0
            "ta" -> counts.tamil > 0
            "te" -> counts.telugu > 0
            "bo" -> counts.tibetan > 0
            "my" -> counts.myanmar > 0
            "km" -> counts.khmer > 0

            // 拉丁语系（英/法/德/西/葡/意/荷/波/捷/印尼/马来/菲/越…）：
            // 必须有拉丁字母，且不能被汉字占据主导（那就是没翻译直接吐中文了）
            else -> counts.latin > 0 && counts.han * 2 < counts.letters
        }
    }

    private fun sameAsSource(output: String, sourceText: String): Boolean {
        if (sourceText.isBlank()) return false
        return output.replace(Regex("\\s+"), "") == sourceText.replace(Regex("\\s+"), "")
    }

    /** 按 Unicode 码点统计文本中各文字系统的字符数 */
    internal class ScriptCounts(
        var latin: Int = 0,
        var han: Int = 0,
        var kana: Int = 0,
        var hangul: Int = 0,
        var cyrillic: Int = 0,
        var arabic: Int = 0,
        var hebrew: Int = 0,
        var devanagari: Int = 0,
        var bengali: Int = 0,
        var gujarati: Int = 0,
        var tamil: Int = 0,
        var telugu: Int = 0,
        var tibetan: Int = 0,
        var myanmar: Int = 0,
        var khmer: Int = 0
    ) {
        val letters: Int
            get() = latin + han + kana + hangul + cyrillic + arabic + hebrew +
                devanagari + bengali + gujarati + tamil + telugu + tibetan + myanmar + khmer

        companion object {
            fun of(text: String): ScriptCounts {
                val counts = ScriptCounts()
                var index = 0
                while (index < text.length) {
                    val code = text.codePointAt(index)
                    index += Character.charCount(code)
                    when {
                        inRange(code, 0x3040, 0x30FF) || inRange(code, 0x31F0, 0x31FF) -> counts.kana++
                        inRange(code, 0x1100, 0x11FF) || inRange(code, 0x3130, 0x318F) ||
                            inRange(code, 0xAC00, 0xD7AF) -> counts.hangul++
                        inRange(code, 0x3400, 0x4DBF) || inRange(code, 0x4E00, 0x9FFF) ||
                            inRange(code, 0xF900, 0xFAFF) || inRange(code, 0x20000, 0x2FA1F) -> counts.han++
                        inRange(code, 0x0400, 0x052F) -> counts.cyrillic++
                        inRange(code, 0x0600, 0x06FF) || inRange(code, 0x0750, 0x077F) ||
                            inRange(code, 0xFB50, 0xFDFF) || inRange(code, 0xFE70, 0xFEFF) -> counts.arabic++
                        inRange(code, 0x0590, 0x05FF) -> counts.hebrew++
                        inRange(code, 0x0900, 0x097F) -> counts.devanagari++
                        inRange(code, 0x0980, 0x09FF) -> counts.bengali++
                        inRange(code, 0x0A80, 0x0AFF) -> counts.gujarati++
                        inRange(code, 0x0B80, 0x0BFF) -> counts.tamil++
                        inRange(code, 0x0C00, 0x0C7F) -> counts.telugu++
                        inRange(code, 0x0F00, 0x0FFF) -> counts.tibetan++
                        inRange(code, 0x1000, 0x109F) -> counts.myanmar++
                        inRange(code, 0x1780, 0x17FF) -> counts.khmer++
                        inRange(code, 0x0041, 0x005A) || inRange(code, 0x0061, 0x007A) ||
                            inRange(code, 0x00C0, 0x024F) || inRange(code, 0x1E00, 0x1EFF) -> counts.latin++
                    }
                }
                return counts
            }

            private fun inRange(code: Int, start: Int, end: Int): Boolean = code in start..end
        }
    }
}
