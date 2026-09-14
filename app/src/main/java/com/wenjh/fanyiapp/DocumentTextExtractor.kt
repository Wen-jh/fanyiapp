package com.wenjh.fanyiapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

/**
 * 从 .docx / .pptx 中抽取纯文本。
 *
 * 这两种格式本质上都是 OOXML（zip + xml），因此这里直接用 [ZipInputStream] 解开，
 * 再用正则把 `<w:t>` / `<a:t>` 中的文本节点取出来，不需要引入额外的解析库。
 */
object DocumentTextExtractor {

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    /** 单个文档最多保留的段落数，避免超大文档把内存撑爆。 */
    private const val MAX_PARAGRAPHS = 400

    /** 单段最大字符数。 */
    private const val MAX_PARAGRAPH_CHARS = 3000

    /** 一个文档最多读取的 XML 字符数，超出部分直接丢弃。 */
    private const val MAX_TOTAL_CHARS = 2_000_000

    private val WORD_TEXT = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
    private val WORD_PARAGRAPH_BREAK = Regex("</w:p>")
    private val PPT_TEXT = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
    private val PPT_PARAGRAPH_BREAK = Regex("</a:p>")
    private val PPT_SLIDE_ENTRY = Regex("ppt/slides/slide(\\d+)\\.xml")

    sealed class Result {
        data class Success(val paragraphs: List<String>) : Result()
        data object UnsupportedFormat : Result()
        data object Empty : Result()
        data class Failure(val message: String) : Result()
    }

    fun extract(context: Context, uri: Uri, displayName: String): Result {
        val lowerName = displayName.lowercase()
        val isDocx = lowerName.endsWith(".docx")
        val isPptx = lowerName.endsWith(".pptx")
        if (!isDocx && !isPptx) return Result.UnsupportedFormat

        val entries = runCatching { readRelevantEntries(context, uri, isPptx) }
            .getOrElse { return Result.Failure(it.message ?: it.javaClass.simpleName) }

        val paragraphs = if (isDocx) {
            parseWord(entries["word/document.xml"].orEmpty())
        } else {
            parsePowerPoint(entries)
        }

        return if (paragraphs.isEmpty()) Result.Empty else Result.Success(paragraphs)
    }

    private fun readRelevantEntries(
        context: Context,
        uri: Uri,
        isPptx: Boolean
    ): Map<String, String> {
        val wanted = LinkedHashMap<String, String>()
        var totalChars = 0

        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val interesting = if (isPptx) {
                        name != null && PPT_SLIDE_ENTRY.matches(name)
                    } else {
                        name == "word/document.xml"
                    }
                    if (interesting && name != null) {
                        val content = zip.readBytes().toString(Charsets.UTF_8)
                        totalChars += content.length
                        wanted[name] = content
                        if (totalChars > MAX_TOTAL_CHARS) break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return wanted
    }

    private fun parseWord(xml: String): List<String> {
        if (xml.isEmpty()) return emptyList()
        val paragraphs = ArrayList<String>()
        for (rawParagraph in WORD_PARAGRAPH_BREAK.split(xml)) {
            val text = WORD_TEXT.findAll(rawParagraph)
                .joinToString(separator = "") { unescape(it.groupValues[1]) }
                .trim()
            if (text.isNotEmpty()) {
                paragraphs.add(text.take(MAX_PARAGRAPH_CHARS))
            }
            if (paragraphs.size >= MAX_PARAGRAPHS) break
        }
        return paragraphs
    }

    private fun parsePowerPoint(entries: Map<String, String>): List<String> {
        val ordered = entries.entries
            .mapNotNull { entity ->
                val index = PPT_SLIDE_ENTRY.find(entity.key)?.groupValues?.get(1)?.toIntOrNull()
                if (index == null) null else index to entity.value
            }
            .sortedBy { it.first }

        val paragraphs = ArrayList<String>()
        for ((_, xml) in ordered) {
            for (rawParagraph in PPT_PARAGRAPH_BREAK.split(xml)) {
                val text = PPT_TEXT.findAll(rawParagraph)
                    .joinToString(separator = "") { unescape(it.groupValues[1]) }
                    .trim()
                if (text.isNotEmpty()) {
                    paragraphs.add(text.take(MAX_PARAGRAPH_CHARS))
                }
                if (paragraphs.size >= MAX_PARAGRAPHS) return paragraphs
            }
        }
        return paragraphs
    }

    private fun unescape(value: String): String {
        if (value.indexOf('&') < 0) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /** 把文档段落按字符预算合并成若干翻译块，尽量不切断段落。 */
    fun chunkParagraphs(paragraphs: List<String>, budget: Int = 480): List<String> {
        if (paragraphs.isEmpty()) return emptyList()
        val chunks = ArrayList<String>()
        val builder = StringBuilder()
        for (paragraph in paragraphs) {
            val candidate = if (builder.isEmpty()) paragraph else builder.toString() + "\n" + paragraph
            if (candidate.length > budget && builder.isNotEmpty()) {
                chunks.add(builder.toString())
                builder.setLength(0)
                builder.append(paragraph)
            } else {
                builder.setLength(0)
                builder.append(candidate)
            }
        }
        if (builder.isNotEmpty()) chunks.add(builder.toString())
        return chunks
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文档"
    }
}
