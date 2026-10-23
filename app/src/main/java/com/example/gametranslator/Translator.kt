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

/** Both functions block, so call them from a background thread. */
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

    /** AI translation: all strings in one request to keep context and save quota. */
    fun gemini(texts: List<String>, apiKey: String, model: String): List<String> {
        val prompt = "Translate these strings from a video game (dialogue and UI) from English " +
            "to natural, fluent Persian (Farsi). Keep character and place names transliterated " +
            "consistently. Keep numbers and symbols. Return ONLY a JSON array of strings with " +
            "exactly the same length and order.\n" + JSONArray(texts).toString()

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", prompt)))))
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
