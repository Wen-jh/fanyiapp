package com.wenjh.fanyiapp

import android.content.Context
import android.util.Log

/**
 * 繁体 -> 简体转换。
 *
 * Whisper 多语言模型识别中文时默认输出繁体（"很高興認識你"），
 * 这里用 OpenCC 的 TSCharacters 表做逐字映射，保证界面显示简体。
 *
 * 映射表放在 assets/text/ts_characters.txt，格式为「繁简繁简…」连续字符，
 * 每两个字符构成一组映射，全表约 3000 组、18KB。
 */
object ChineseTextNormalizer {

    private const val TAG = "ChineseTextNormalizer"
    private const val ASSET_PATH = "text/ts_characters.txt"

    @Volatile
    private var table: Map<Char, Char>? = null

    /**
     * 把文本中的繁体字转成简体。转换表加载失败时原样返回，不影响主流程。
     */
    fun toSimplified(context: Context, text: String): String {
        if (text.isEmpty()) return text
        val map = table ?: load(context) ?: return text
        if (map.isEmpty()) return text
        var changed = false
        val builder = StringBuilder(text.length)
        for (ch in text) {
            val simplified = map[ch]
            if (simplified != null && simplified != ch) {
                builder.append(simplified)
                changed = true
            } else {
                builder.append(ch)
            }
        }
        return if (changed) builder.toString() else text
    }

    private fun load(context: Context): Map<Char, Char>? {
        table?.let { return it }
        synchronized(this) {
            table?.let { return it }
            val loaded = runCatching {
                val raw = context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
                val map = HashMap<Char, Char>(raw.length / 2)
                var index = 0
                while (index + 1 < raw.length) {
                    map[raw[index]] = raw[index + 1]
                    index += 2
                }
                map
            }.onFailure { Log.e(TAG, "繁简映射表加载失败", it) }.getOrNull()
            table = loaded ?: emptyMap()
            return table
        }
    }
}
