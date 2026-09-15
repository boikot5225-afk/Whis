package com.bulat.whis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioChunkDecoder {
    const val TARGET_SAMPLE_RATE = 16_000
    // Short chunks make it obvious very quickly whether whisper.cpp is actually progressing.
    // Medium on a phone can spend a long time inside one whisper_full() call for 60 seconds.
    private const val CHUNK_SECONDS = 15
    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes Android-supported audio (MP3/M4A/AAC/OGG/FLAC/WAV depending on device),
     * downmixes it to mono and resamples to Whisper's 16 kHz input. Audio is delivered
     * in fifteen-second chunks so the UI can advance frequently even with a heavy model.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        onChunk: suspend (samples: FloatArray, offsetMs: Long, totalDurationMs: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, emptyMap())

            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = format
                    break
                }
            }
            check(audioTrack >= 0 && inputFormat != null) { "В файле не найден аудиопоток" }

            extractor.selectTrack(audioTrack)
            val format = inputFormat!!
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Не удалось определить формат аудио")
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1_000L
            } else {
                -1L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = LinearResampler(outputSampleRate)
            val collector = ChunkCollector(TARGET_SAMPLE_RATE * CHUNK_SECONDS)
            var chunkStartSamples = 0L

            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("MediaCodec не выдал входной буфер")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        outputSampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        resampler = LinearResampler(outputSampleRate)
                    }
                    else -> if (outputIndex >= 0) {
                        val completedChunks = mutableListOf<FloatArray>()
                        if (info.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: error("MediaCodec не выдал выходной буфер")
                            val view = outputBuffer.duplicate().apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }.slice().order(ByteOrder.LITTLE_ENDIAN)

                            consumePcm(
                                view,
                                outputChannels,
                                pcmEncoding,
                                resampler,
                            ) { sample ->
                                collector.add(sample)?.let(completedChunks::add)
                            }
                        }

                        codec.releaseOutputBuffer(outputIndex, false)

                        for (chunk in completedChunks) {
                            val offsetMs = chunkStartSamples * 1_000L / TARGET_SAMPLE_RATE
                            onChunk(chunk, offsetMs, durationMs)
                            chunkStartSamples += chunk.size.toLong()
                        }

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            collector.takeRemaining()?.let { tail ->
                val offsetMs = chunkStartSamples * 1_000L / TARGET_SAMPLE_RATE
                onChunk(tail, offsetMs, durationMs)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun consumePcm(
        buffer: ByteBuffer,
        channels: Int,
        pcmEncoding: Int,
        resampler: LinearResampler,
        emit: (Float) -> Unit,
    ) {
        require(channels > 0) { "Некорректное число аудиоканалов: $channels" }

        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val frameBytes = channels * 2
                while (buffer.remaining() >= frameBytes) {
                    var mono = 0f
                    repeat(channels) {
                        mono += buffer.short / 32768f
                    }
                    resampler.consume(mono / channels, emit)
                }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                val frameBytes = channels * 4
                while (buffer.remaining() >= frameBytes) {
                    var mono = 0f
                    repeat(channels) {
                        mono += buffer.float.coerceIn(-1f, 1f)
                    }
                    resampler.consume(mono / channels, emit)
                }
            }

            else -> error("Неподдерживаемый PCM-формат декодера: $pcmEncoding")
        }
    }

    private class ChunkCollector(private val capacity: Int) {
        private var data = FloatArray(capacity)
        private var size = 0

        fun add(value: Float): FloatArray? {
            data[size++] = value
            if (size < capacity) return null

            val completed = data
            data = FloatArray(capacity)
            size = 0
            return completed
        }

        fun takeRemaining(): FloatArray? {
            if (size == 0) return null
            return data.copyOf(size).also { size = 0 }
        }
    }

    /** Stateful linear resampler that survives MediaCodec output-buffer boundaries. */
    private class LinearResampler(sourceRate: Int) {
        private val step = sourceRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        private var previous = 0f
        private var hasPrevious = false
        private var sourceIndex = 0L
        private var nextOutputPosition = 0.0

        fun consume(sample: Float, emit: (Float) -> Unit) {
            if (!hasPrevious) {
                previous = sample
                hasPrevious = true
                sourceIndex = 0L
                nextOutputPosition = 0.0
            }

            val currentIndex = sourceIndex
            if (currentIndex == 0L && nextOutputPosition == 0.0) {
                emit(sample)
                nextOutputPosition += step
                previous = sample
                sourceIndex = 1L
                return
            }

            val rightIndex = sourceIndex.toDouble()
            val leftIndex = rightIndex - 1.0
            while (nextOutputPosition <= rightIndex) {
                val fraction = (nextOutputPosition - leftIndex).coerceIn(0.0, 1.0).toFloat()
                emit(previous + (sample - previous) * fraction)
                nextOutputPosition += step
            }

            previous = sample
            sourceIndex += 1L
        }
    }
}
