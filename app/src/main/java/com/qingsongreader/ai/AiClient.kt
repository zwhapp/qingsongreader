package com.qingsongreader.ai

import com.qingsongreader.data.prefs.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI helper for translation, summarisation and free-form questions.
 *
 * It talks to whatever OpenAI-compatible endpoint the user configured in
 * Settings (official API, a self-hosted gateway, Ollama, OpenRouter, ...).
 * No third-party paid service is proxied by the app and no quota is enforced:
 * usage is billed to the user's own key.
 */
class AiClient(private val settings: ReaderSettings) {

    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun translate(text: String, targetLanguage: String): Result<String> {
        val system = "You are a precise translation engine. Translate the user's text into $targetLanguage. " +
            "Keep the original formatting and return only the translated text."
        return chat(system, text)
    }

    suspend fun summarize(text: String): Result<String> {
        val system = "You are a reading assistant. Summarise the following excerpt concisely, " +
            "in the same language as the excerpt."
        return chat(system, "Summarise this excerpt:\n\n$text")
    }

    suspend fun ask(question: String, context: String): Result<String> {
        val system = "You are a reading assistant inside an ebook reader. Answer briefly using the provided context when relevant."
        return chat(system, "Context:\n$context\n\nQuestion: $question")
    }

    /**
     * Dictionary fallback: used when neither the offline pack nor the free
     * online dictionary knows the word (typically Chinese words or slang).
     */
    suspend fun explain(word: String): Result<String> {
        val system = "You are a concise bilingual dictionary. For the given word or phrase return: " +
            "1) part of speech, 2) a short definition (Chinese if the word is English, English+Chinese if it is Chinese), " +
            "3) one short example sentence. Keep it under 120 words, no preamble."
        return chat(system, "Word: $word")
    }

    private suspend fun chat(system: String, user: String): Result<String> = withContext(Dispatchers.IO) {
        if (settings.aiApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("请先在设置里填写自己的 API Key"))
        }
        val body = JSONObject().apply {
            put("model", settings.aiModel)
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", system)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", user.take(8000))
                })
            })
        }
        val request = Request.Builder()
            .url(settings.aiBaseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.aiApiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("AI 请求失败 (${response.code}): ${payload.take(200)}")
                }
                val json = JSONObject(payload)
                json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        }
    }
}
