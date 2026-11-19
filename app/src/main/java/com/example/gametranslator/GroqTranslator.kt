package com.example.gametranslator

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/** Groq fallback for game localization using its OpenAI-compatible API. */
object GroqTranslator {
    const val DEFAULT_MODEL = "openai/gpt-oss-20b"

    fun translate(
        texts: List<String>,
        apiKey: String,
        game: String = "",
        glossary: List<Pair<String, String>> = emptyList(),
        previous: List<String>? = null,
        context: List<String> = emptyList(),
        model: String = DEFAULT_MODEL
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (apiKey.isBlank()) throw IOException("Groq API key is empty")

        val replacements = mutableListOf<Pair<String, String>>()
        val protectedTexts = texts.map { original ->
            var value = original
            glossary.forEachIndexed { index, entry ->
                if (entry.first.isBlank() || entry.second.isBlank()) return@forEachIndexed
                val token = "ZXQGROQ${index}QXZ"
                val left = if (entry.first.firstOrNull()?.isLetterOrDigit() == true) "(?<![A-Za-z0-9])" else ""
                val right = if (entry.first.lastOrNull()?.isLetterOrDigit() == true) "(?![A-Za-z0-9])" else ""
                val regex = Regex(left + Pattern.quote(entry.first) + right, RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(value)) {
                    value = regex.replace(value, token)
                    replacements.add(token to entry.second)
                }
            }
            value
        }

        val systemPrompt = """
You are a professional Persian video-game localization translator.
Translate game text from English into fluent, natural, idiomatic Persian (fa-IR).
Understand meaning and context; never translate idioms word-for-word.
Use natural spoken Persian for dialogue and concise Persian for menus/UI.
Preserve intent, emotion, humor, urgency, politeness, and logical meaning.
Do not add, omit, summarize, merge, or invent information.
Keep every input item separate and preserve exact order and item count.
Keep character/place/item/skill names consistent.
Preserve all numbers, tags, variables, placeholders, and protected ZXQGROQ...QXZ tokens exactly.
Return ONLY a valid JSON array of strings, with no markdown or explanation.
""".trimIndent()

        val userPrompt = buildString {
            if (game.isNotBlank()) append("Game: ").append(game).append("\n")
            if (context.isNotEmpty()) append("Nearby screen context (do not translate separately): ")
                .append(JSONArray(context)).append("\n")
            if (glossary.isNotEmpty()) {
                append("Mandatory glossary: ")
                append(glossary.joinToString("; ") { "${it.first} = ${it.second}" })
                append("\n")
            }
            if (previous != null) append("Previous poor translation to improve: ")
                .append(JSONArray(previous)).append("\n")
            append("Translate these strings in order:\n").append(JSONArray(protectedTexts))
        }

        val raw = request(userPrompt, systemPrompt, apiKey, model)
        val parsed = parseArray(raw, protectedTexts.size)
            ?: throw IOException("Groq returned an invalid translation list")
        if (parsed.any { it.isBlank() }) throw IOException("Groq returned an empty translation")
        val missingToken = replacements.firstOrNull { (token, _) ->
            parsed.none { it.contains(token) }
        }
        if (missingToken != null) throw IOException("Groq omitted a protected glossary term")

        return parsed.map { translated ->
            var restored = translated
            replacements.distinctBy { it.first }.forEach { (token, target) ->
                restored = restored.replace(token, target, ignoreCase = true)
            }
            restored
        }
    }

    private fun request(prompt: String, system: String, apiKey: String, model: String): String {
        val body = JSONObject()
            .put("model", model.trim().ifEmpty { DEFAULT_MODEL })
            .put("temperature", 0.2)
            .put("max_completion_tokens", 4096)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt)))

        val conn = URL("https://api.groq.com/openai/v1/chat/completions")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15000
            conn.readTimeout = 50000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Groq HTTP $code")
            val choices = JSONObject(response).optJSONArray("choices")
                ?: throw IOException("Groq returned no choices")
            if (choices.length() == 0) throw IOException("Groq returned no choices")
            return choices.getJSONObject(0).optJSONObject("message")
                ?.optString("content").orEmpty().trim()
                .ifEmpty { throw IOException("Groq returned no text") }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseArray(raw: String, expected: Int): List<String>? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return runCatching {
            val array = JSONArray(cleaned.substring(start, end + 1))
            if (array.length() != expected) return null
            List(array.length()) { i ->
                val value = array.get(i)
                if (value is String) value.trim() else throw IOException("Non-string output")
            }
        }.getOrNull()
    }
}
