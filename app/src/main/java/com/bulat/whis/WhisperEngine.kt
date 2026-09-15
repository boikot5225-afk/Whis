package com.bulat.whis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** One timestamped Whisper segment. */
data class WhisperSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

class WhisperEngine(modelFile: File) : Closeable {
    private var contextPtr: Long = NativeWhisper.initContext(modelFile.absolutePath)

    val threadCount: Int = run {
        val cores = Runtime.getRuntime().availableProcessors()
        (cores - 2).coerceIn(2, 8)
    }

    init {
        check(contextPtr != 0L) { "Не удалось загрузить модель ${modelFile.name}" }
    }

    suspend fun transcribe(
        samples: FloatArray,
        language: String,
        offsetMs: Long = 0L,
        onProgress: (Int) -> Unit = {},
    ): List<WhisperSegment> = withContext(Dispatchers.Default) {
        check(contextPtr != 0L) { "Whisper уже закрыт" }

        val callback = NativeWhisper.ProgressCallback { progress ->
            onProgress(progress.coerceIn(0, 100))
        }
        val rc = NativeWhisper.fullTranscribe(
            contextPtr,
            threadCount,
            samples,
            language,
            false,
            callback,
        )
        check(rc == 0) { "Whisper завершился с кодом $rc" }

        val count = NativeWhisper.getSegmentCount(contextPtr)
        buildList(count) {
            for (index in 0 until count) {
                // whisper.cpp timestamps are in 10 ms units.
                val start = offsetMs + NativeWhisper.getSegmentT0(contextPtr, index) * 10L
                val end = offsetMs + NativeWhisper.getSegmentT1(contextPtr, index) * 10L
                val text = NativeWhisper.getSegmentText(contextPtr, index).trim()
                if (text.isNotEmpty()) {
                    add(WhisperSegment(start, end, text))
                }
            }
        }
    }

    fun timingInfo(): String {
        check(contextPtr != 0L) { "Whisper уже закрыт" }
        return NativeWhisper.getTimings(contextPtr)
    }

    fun modelInfo(): String {
        check(contextPtr != 0L) { "Whisper уже закрыт" }
        return NativeWhisper.getModelInfo(contextPtr)
    }

    fun systemInfo(): String = NativeWhisper.getSystemInfo()

    override fun close() {
        if (contextPtr != 0L) {
            NativeWhisper.freeContext(contextPtr)
            contextPtr = 0L
        }
    }
}
