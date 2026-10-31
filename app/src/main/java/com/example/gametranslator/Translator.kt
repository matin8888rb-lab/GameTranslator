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
import java.util.regex.Pattern

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
        }.sortedByDescending { it.first.length }

    private fun termPattern(term: String): Regex {
        val left = if (term.firstOrNull()?.isLetterOrDigit() == true) "(?<![A-Za-z0-9])" else ""
        val right = if (term.lastOrNull()?.isLetterOrDigit() == true) "(?![A-Za-z0-9])" else ""
        return Regex(left + Pattern.quote(term) + right, RegexOption.IGNORE_CASE)
    }

    /**
     * Protect glossary terms before translation. This prevents the model from
     * inventing different spellings or translating game-specific names.
     */
    private fun protectGlossary(
        texts: List<String>,
        glossary: List<Pair<String, String>>
    ): Pair<List<String>, List<Pair<String, String>>> {
        if (glossary.isEmpty()) return texts to emptyList()
        val replacements = mutableListOf<Pair<String, String>>()
        val protected = texts.map { original ->
            var value = original
            glossary.forEachIndexed { index, entry ->
                val token = "ZXQTERM${index}QXZ"
                val regex = termPattern(entry.first)
                if (regex.containsMatchIn(value)) {
                    value = regex.replace(value, token)
                    replacements.add(token to entry.second)
                }
            }
            value
        }
        return protected to replacements.distinctBy { it.first }
    }

    private fun restoreGlossary(text: String, replacements: List<Pair<String, String>>): String {
        var result = text
        replacements.forEach { (token, translation) ->
            result = result.replace(token, translation, ignoreCase = true)
        }
        return result
    }

    /** Apply glossary terms inside a sentence too, longest phrase first. */
    fun applyGlossary(
        sources: List<String>,
        translated: List<String>,
        glossary: List<Pair<String, String>>
    ): List<String> {
        if (glossary.isEmpty()) return translated
        return translated.mapIndexed { i, translatedText ->
            var result = translatedText
            glossary.forEach { (source, target) ->
                if (i < sources.size && sources[i].contains(source, ignoreCase = true)) {
                    result = termPattern(source).replace(result) { target }
                }
            }
            result
        }
    }

    // ---------- AI (Gemini) ----------

    /**
     * Game-focused localization. All strings are sent together so nearby dialogue
     * can clarify pronouns, tone and references without merging their outputs.
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
        if (texts.isEmpty()) return emptyList()
        if (apiKey.isBlank()) throw IOException("API key is empty")

        val (protectedTexts, tokens) = protectGlossary(texts, glossary)
        val prompt = buildString {
            append(
                """
You are a professional Persian video-game LOCALIZATION translator, not a literal translator.
Translate the English game text below into fluent, natural, idiomatic Persian (fa-IR).

QUALITY RULES:
- Understand the complete meaning before translating. Translate idioms by meaning, never word-for-word.
- Use natural spoken Persian for character dialogue. Use concise, clear Persian for menus and UI.
- Preserve the speaker's intent, emotion, attitude, humor, urgency and level of politeness.
- Keep the meaning and logical links between sentences exactly. Never add, omit, merge, summarize or invent information.
- Resolve pronouns and references using the surrounding screen context when possible.
- Keep each input item as a separate output item. Do not combine neighboring items.
- Keep character, location, item, skill and faction names consistent. Do not randomly translate proper names.
- Preserve numbers, punctuation that carries meaning, tags, variables and placeholders.
- Do not add explanations, quotation marks, notes, alternatives or translator comments.
- The result must read like dialogue originally written in Persian, not machine-translated text.
- Return Persian script. Do not return English except for protected tokens or names that should remain in English.
""".trimIndent()
            )
            if (game.isNotBlank()) append("\nGame title: ").append(game)
            if (glossary.isNotEmpty()) {
                append("\nThe following glossary entries are mandatory. Their protected tokens must be copied exactly:")
                glossary.forEach { append("\n").append(it.first).append(" = ").append(it.second) }
            }
            if (context.isNotEmpty()) {
                append("\nNearby screen text for context only (DO NOT include it in the output): ")
                append(JSONArray(context).toString())
            }
            if (previous != null) {
                append("\nPrevious translation to improve (do not copy its mistakes): ")
                append(JSONArray(previous).toString())
            }
            append(
                """

Return ONLY a valid JSON array of strings.
The array must contain exactly ${protectedTexts.size} strings, in exactly the same order as the input.
Each output string must translate only the matching input string.
Input strings:
"""
            )
            append(JSONArray(protectedTexts).toString())
        }

        val first = requestGemini(prompt, apiKey, model)
        val parsed = parseTranslationArray(first, protectedTexts.size)
        val valid = parsed != null && tokens.all { (token, _) -> parsed.any { it.contains(token) } }

        val finalItems = if (valid) {
            parsed!!
        } else {
            val retryPrompt = prompt +
                "\n\nIMPORTANT CORRECTION: Your previous response was invalid or omitted protected glossary tokens. " +
                "Return a complete valid JSON array with exactly ${protectedTexts.size} non-empty strings. " +
                "Copy every ZXQTERM...QXZ token exactly once wherever it appears in its corresponding input."
            val retry = requestGemini(retryPrompt, apiKey, model)
            parseTranslationArray(retry, protectedTexts.size)
                ?: throw IOException("Gemini returned an invalid translation list")
        }

        if (finalItems.any { it.isBlank() }) throw IOException("Gemini returned an empty translation")
        return finalItems.map { restoreGlossary(it, tokens) }
    }

    private fun requestGemini(prompt: String, apiKey: String, model: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("maxOutputTokens", 8192)
                .put("responseMimeType", "application/json"))

        val safeModel = model.trim().ifEmpty { "gemini-2.5-flash" }
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 15000
            conn.readTimeout = 50000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optJSONArray("error")?.toString()
                }.getOrNull()
                throw IOException("Gemini HTTP $code${if (detail.isNullOrBlank()) "" else ": $detail"}")
            }
            val root = JSONObject(response)
            val candidates = root.optJSONArray("candidates")
                ?: throw IOException("Gemini returned no candidates")
            if (candidates.length() == 0) throw IOException("Gemini returned no candidates")
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("Gemini returned no text")
            return buildString {
                for (i in 0 until parts.length()) {
                    append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }
            }.trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTranslationArray(raw: String, expectedSize: Int): List<String>? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return runCatching {
            val array = JSONArray(cleaned.substring(start, end + 1))
            if (array.length() != expectedSize) return null
            List(array.length()) { index ->
                array.get(index).let { value ->
                    if (value is String) value.trim()
                    else throw IOException("Translation item is not a string")
                }
            }
        }.getOrNull()
    }
}
