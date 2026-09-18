package com.bulat.whis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DeepSeekEditor {
    private const val API_URL = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-flash"
    private const val MAX_CHARS_PER_CHUNK = 18_000

    suspend fun editTranscript(
        apiKey: String,
        text: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "DeepSeek API ключ не задан" }
        require(text.isNotBlank()) { "Нет текста для редактуры" }

        val chunks = splitIntoChunks(text)
        val edited = ArrayList<String>(chunks.size)

        chunks.forEachIndexed { index, chunk ->
            onProgress(index + 1, chunks.size)
            edited += editChunk(apiKey.trim(), chunk)
        }

        edited.joinToString("\n\n").trim()
    }

    private fun editChunk(apiKey: String, chunk: String): String {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer \$apiKey")
        }

        val systemPrompt = """
            Ты редактор автоматических расшифровок.
            Исправь ошибки распознавания, пунктуацию, регистр, разбиение на абзацы и очевидно искажённые слова по контексту.
            Сохраняй исходный язык, смысл, имена, числа и факты.
            Не переводи, не пересказывай, не сокращай и ничего не добавляй от себя.
            Верни только чистовой текст без комментариев, заголовков и Markdown.
        """.trimIndent()

        val body = JSONObject()
            .put("model", MODEL)
            .put("stream", false)
            .put("max_tokens", 8192)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", chunk)),
            )
            .toString()

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                val apiMessage = runCatching {
                    JSONObject(response)
                        .optJSONObject("error")
                        ?.optString("message")
                        .orEmpty()
                }.getOrDefault("")
                error(
                    if (apiMessage.isNotBlank()) {
                        "DeepSeek HTTP \$code: \$apiMessage"
                    } else {
                        "DeepSeek HTTP \$code"
                    },
                )
            }

            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()

            check(content.isNotBlank()) { "DeepSeek вернул пустой ответ" }
            return content
        } finally {
            connection.disconnect()
        }
    }

    private fun splitIntoChunks(text: String): List<String> {
        val paragraphs = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) return listOf(text.trim())

        val result = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) {
                result += current.toString().trim()
                current.clear()
            }
        }

        for (paragraph in paragraphs) {
            if (paragraph.length > MAX_CHARS_PER_CHUNK) {
                flush()
                var start = 0
                while (start < paragraph.length) {
                    var end = (start + MAX_CHARS_PER_CHUNK).coerceAtMost(paragraph.length)
                    if (end < paragraph.length) {
                        val preferred = paragraph.lastIndexOfAny(
                            charArrayOf('。', '！', '？', '.', '!', '?', ';', '；', '\n'),
                            startIndex = end - 1,
                        )
                        if (preferred > start + MAX_CHARS_PER_CHUNK / 2) {
                            end = preferred + 1
                        }
                    }
                    result += paragraph.substring(start, end).trim()
                    start = end
                }
                continue
            }

            val extra = if (current.isEmpty()) paragraph.length else paragraph.length + 2
            if (current.length + extra > MAX_CHARS_PER_CHUNK) {
                flush()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }

        flush()
        return result.ifEmpty { listOf(text.trim()) }
    }
}
