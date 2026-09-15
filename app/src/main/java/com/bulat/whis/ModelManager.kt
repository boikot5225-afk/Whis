package com.bulat.whis

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class WhisperModel(
    val id: String,
    val title: String,
) {
    SMALL_Q5_1("small-q5_1", "Small — быстро"),
    MEDIUM_Q5_0("medium-q5_0", "Medium — точнее"),
    LARGE_V3_TURBO_Q5_0("large-v3-turbo-q5_0", "Large v3 Turbo — максимум"),
    ;

    val fileName: String
        get() = "ggml-$id.bin"

    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    override fun toString(): String = title
}

class ModelManager(private val context: Context) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(model: WhisperModel): File = File(modelsDir, model.fileName)

    fun isDownloaded(model: WhisperModel): Boolean {
        val file = fileFor(model)
        return file.isFile && file.length() > 1_000_000L
    }

    suspend fun importModel(uri: Uri, model: WhisperModel): File = withContext(Dispatchers.IO) {
        val target = fileFor(model)
        val partial = File(modelsDir, "${model.fileName}.import")
        partial.delete()

        try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                partial.outputStream().buffered().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
                }
            } ?: error("Не удалось открыть выбранный файл модели")

            check(partial.length() > 1_000_000L) { "Файл модели подозрительно маленький" }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Не удалось сохранить импортированную модель" }
            target
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }

    suspend fun download(
        model: WhisperModel,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = fileFor(model)
        if (isDownloaded(model)) return@withContext target

        val partial = File(modelsDir, "${model.fileName}.part")
        partial.delete()

        val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Whis/0.1 Android")
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Сервер модели ответил HTTP ${connection.responseCode}")
            }

            val total = connection.contentLengthLong
            var copied = 0L
            var lastPercent = -1

            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read

                        if (total > 0) {
                            val percent = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                }
            }

            check(partial.length() > 1_000_000L) { "Скачанный файл модели подозрительно маленький" }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Не удалось сохранить модель" }
            withContext(Dispatchers.Main) { onProgress(100) }
            target
        } catch (t: Throwable) {
            partial.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }
}
