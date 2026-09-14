package com.wenjh.fanyiapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条文档翻译记录。 */
data class DocumentTranslationRecord(
    val id: String,
    val fileName: String,
    val languagePair: String,
    val timestamp: Long,
    val paragraphs: List<String>,
    val translations: List<String>
) {
    fun previewText(): String = translations.firstOrNull().orEmpty()
}

/** 最近翻译的本地存储（SharedPreferences + JSON，量小、无需数据库）。 */
object DocumentTranslationStore {

    private const val PREF_NAME = "document_translation_history"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 6

    fun load(context: Context): List<DocumentTranslationRecord> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                DocumentTranslationRecord(
                    id = item.optString("id"),
                    fileName = item.optString("fileName"),
                    languagePair = item.optString("languagePair"),
                    timestamp = item.optLong("timestamp"),
                    paragraphs = item.optJSONArray("paragraphs").toStringList(),
                    translations = item.optJSONArray("translations").toStringList()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, record: DocumentTranslationRecord) {
        val records = load(context)
            .filterNot { it.id == record.id }
            .toMutableList()
        records.add(0, record)
        persist(context, records.take(MAX_RECORDS))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RECORDS)
            .apply()
    }

    private fun persist(context: Context, records: List<DocumentTranslationRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("id", record.id)
                    put("fileName", record.fileName)
                    put("languagePair", record.languagePair)
                    put("timestamp", record.timestamp)
                    put("paragraphs", JSONArray(record.paragraphs))
                    put("translations", JSONArray(record.translations))
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, array.toString())
            .apply()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }
}
