package com.example.gametranslator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** All translate functions block, so call them from a background thread. */
object Translator {

    private val mlTranslator by lazy {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.PERSIAN)
            .build()
        Translation.getClient(opts)
    }

    /** Free, offline (model downloads once). */
    fun mlkit(texts: List<String>): List<String> {
        Tasks.await(mlTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        return texts.map { Tasks.await(mlTranslator.translate(it)) }
    }

    // ---------- glossary ----------

    /** One entry per line: `English term = ترجمه`. */
    fun parseGlossary(raw: String): List<Pair<String, String>> =
        raw.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }

    /** If a whole text block equals a glossary term, use the glossary translation as is. */
    fun applyGlossary(
        sources: List<String>,
        translated: List<String>,
        glossary: List<Pair<String, String>>
    ): List<String> {
        if (glossary.isEmpty()) return translated
        return translated.mapIndexed { i, t ->
            val src = sources[i].trim().trim('.', '!', '?', ':').trim()
            glossary.firstOrNull { it.first.equals(src, ignoreCase = true) }?.second ?: t
        }
    }

    // ---------- AI (Gemini) ----------

    /**
     * AI translation: all strings in one request to keep context and save quota.
     * [previous] = an earlier bad translation to improve on, [context] = other texts
     * on the same screen (never translated, only used to understand the scene).
     */
    fun gemini(
        texts: List<String>,
        apiKey: String,
        model: String,
        game: String = "",
        glossary: List<Pair<String, String>> = emptyList(),
        previous: List<String>? = null,
        context: List<String> = emptyList()
    ): List<String> {
        val sb = StringBuilder()
        sb.append("Translate these strings from a video game")
        if (game.isNotBlank()) sb.append(" ($game)")
        sb.append(" (dialogue and UI) from English to natural, fluent Persian (Farsi), ")
        sb.append("the way a good game localizer would write it. ")
        sb.append("Keep character and place names transliterated consistently. ")
        sb.append("Keep numbers, symbols and placeholders unchanged. ")
        if (glossary.isNotEmpty()) {
            sb.append("Always use these exact translations for these terms: ")
            sb.append(glossary.joinToString("; ") { "${it.first} => ${it.second}" })
            sb.append(". ")
        }
        if (previous != null) {
            sb.append("A previous machine translation of these strings was poor or wrong, ")
            sb.append("so write a clearly better one. Previous attempt: ")
            sb.append(JSONArray(previous).toString()).append(". ")
        }
        if (context.isNotEmpty()) {
            sb.append("Other texts on the same screen, for context only (do not translate them): ")
            sb.append(JSONArray(context).toString()).append(". ")
        }
        sb.append("Return ONLY a JSON array of strings with exactly the same length and order ")
        sb.append("as the input.\n")
        sb.append(JSONArray(texts).toString())

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", sb.toString())))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.connectTimeout = 15000
        conn.readTimeout = 40000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader().readText()
        if (code !in 200..299) throw IOException("HTTP $code")

        val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val arr = JSONArray(text)
        if (arr.length() != texts.size) throw IOException("count mismatch")
        return List(arr.length()) { arr.getString(it) }
    }
}
