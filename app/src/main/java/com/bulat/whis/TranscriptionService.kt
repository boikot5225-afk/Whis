package com.bulat.whis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** State shared with the Activity while the foreground service keeps the process alive. */
data class TranscriptionUiState(
    val running: Boolean = false,
    val progress: Int = 0,
    val status: String = "",
    val audioName: String = "",
    val segments: List<WhisperSegment> = emptyList(),
    val finished: Boolean = false,
    val error: String? = null,
)

object TranscriptionStore {
    private val _state = MutableStateFlow(TranscriptionUiState())
    val state = _state.asStateFlow()

    fun set(value: TranscriptionUiState) {
        _state.value = value
    }
}

class TranscriptionService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Default)
    private var transcriptionJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedProgress = -1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            transcriptionJob?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        if (transcriptionJob?.isActive == true) return START_NOT_STICKY

        val audioUri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        val modelName = intent?.getStringExtra(EXTRA_MODEL)
        val language = intent?.getStringExtra(EXTRA_LANGUAGE) ?: "auto"
        val audioName = intent?.getStringExtra(EXTRA_AUDIO_NAME) ?: "audio"

        if (audioUri.isNullOrBlank() || modelName.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val model = runCatching { WhisperModel.valueOf(modelName) }.getOrNull()
        if (model == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        TranscriptionStore.set(
            TranscriptionUiState(
                running = true,
                progress = 0,
                status = "Запускаю фоновое распознавание…",
                audioName = audioName,
            )
        )

        startForeground(NOTIFICATION_ID, buildNotification(audioName, "Запуск…", 0, true))
        acquireWakeLock()

        transcriptionJob = scope.launch {
            runTranscription(android.net.Uri.parse(audioUri), model, language, audioName)
        }

        return START_NOT_STICKY
    }

    private suspend fun runTranscription(
        audioUri: android.net.Uri,
        model: WhisperModel,
        language: String,
        audioName: String,
    ) {
        val modelManager = ModelManager(this)
        val modelFile = modelManager.fileFor(model)
        var engine: WhisperEngine? = null

        try {
            check(modelManager.isDownloaded(model)) { "Модель ${model.title} не найдена" }

            updateState(
                progress = 0,
                status = "Загружаю ${model.title} в память…",
                audioName = audioName,
                segments = emptyList(),
            )

            engine = WhisperEngine(modelFile)
            val collected = mutableListOf<WhisperSegment>()

            AudioChunkDecoder.decode(this, audioUri) { samples, offsetMs, totalDurationMs ->
                val chunkDurationMs = samples.size * 1_000L / AudioChunkDecoder.TARGET_SAMPLE_RATE
                var lastOverallProgress = -1

                updateState(
                    progress = currentOverall(offsetMs, totalDurationMs),
                    status = "Распознаю с ${formatClock(offsetMs)}…",
                    audioName = audioName,
                    segments = collected.toList(),
                )

                val chunkSegments = engine.transcribe(samples, language, offsetMs) { chunkProgress ->
                    if (totalDurationMs > 0) {
                        val processedMs = offsetMs + (chunkDurationMs * chunkProgress / 100L)
                        val overall = ((processedMs * 100L) / totalDurationMs).toInt().coerceIn(0, 99)
                        if (overall != lastOverallProgress) {
                            lastOverallProgress = overall
                            updateStateFromAnyThread(
                                progress = overall,
                                status = "Распознаю ${formatClock(processedMs)} / ${formatClock(totalDurationMs)} · $overall%",
                                audioName = audioName,
                                segments = collected.toList(),
                            )
                        }
                    } else {
                        updateStateFromAnyThread(
                            progress = TranscriptionStore.state.value.progress,
                            status = "Распознаю текущий фрагмент · $chunkProgress%",
                            audioName = audioName,
                            segments = collected.toList(),
                        )
                    }
                }

                collected += chunkSegments
                val processedMs = offsetMs + chunkDurationMs
                val overall = currentOverall(processedMs, totalDurationMs)
                updateState(
                    progress = overall,
                    status = if (totalDurationMs > 0) {
                        "Распознано ${formatClock(processedMs)} / ${formatClock(totalDurationMs)} · $overall%"
                    } else {
                        "Распознано ${formatClock(processedMs)}"
                    },
                    audioName = audioName,
                    segments = collected.toList(),
                )
            }

            val finalSegments = collected.toList()
            TranscriptionStore.set(
                TranscriptionUiState(
                    running = false,
                    progress = 100,
                    status = "Готово. ${finalSegments.size} фрагментов.",
                    audioName = audioName,
                    segments = finalSegments,
                    finished = true,
                )
            )
            showFinishedNotification(audioName, finalSegments.size)
        } catch (_: CancellationException) {
            val snapshot = TranscriptionStore.state.value
            TranscriptionStore.set(
                snapshot.copy(
                    running = false,
                    status = "Распознавание остановлено.",
                    finished = false,
                )
            )
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            val snapshot = TranscriptionStore.state.value
            TranscriptionStore.set(
                snapshot.copy(
                    running = false,
                    status = "Ошибка распознавания: $message",
                    error = message,
                    finished = false,
                )
            )
            showErrorNotification(audioName, message)
        } finally {
            withContext(Dispatchers.Default) { engine?.close() }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateState(
        progress: Int,
        status: String,
        audioName: String,
        segments: List<WhisperSegment>,
    ) {
        updateStateFromAnyThread(progress, status, audioName, segments)
    }

    private fun updateStateFromAnyThread(
        progress: Int,
        status: String,
        audioName: String,
        segments: List<WhisperSegment>,
    ) {
        val value = TranscriptionUiState(
            running = true,
            progress = progress.coerceIn(0, 99),
            status = status,
            audioName = audioName,
            segments = segments,
        )
        TranscriptionStore.set(value)

        if (progress != lastNotifiedProgress) {
            lastNotifiedProgress = progress
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(audioName, status, progress.coerceIn(0, 99), true),
            )
        }
    }

    private fun currentOverall(processedMs: Long, totalMs: Long): Int {
        return if (totalMs > 0) {
            ((processedMs * 100L) / totalMs).toInt().coerceIn(0, 99)
        } else {
            0
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:transcription",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновое распознавание",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Показывает ход локального распознавания Whisper"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        audioName: String,
        text: String,
        progress: Int,
        ongoing: Boolean,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, TranscriptionService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Whis · $audioName")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .addAction(android.R.drawable.ic_delete, "Остановить", stopPendingIntent)
            .build()
    }

    private fun showFinishedNotification(audioName: String, segmentCount: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Whis · готово")
            .setContentText("$audioName · $segmentCount фрагментов")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(FINISHED_NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(audioName: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Whis · ошибка")
            .setContentText("$audioName · $message")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(FINISHED_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        transcriptionJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "whis_transcription"
        private const val NOTIFICATION_ID = 1001
        private const val FINISHED_NOTIFICATION_ID = 1002

        const val ACTION_STOP = "com.bulat.whis.STOP_TRANSCRIPTION"
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_AUDIO_NAME = "audio_name"
        const val EXTRA_MODEL = "model"
        const val EXTRA_LANGUAGE = "language"
    }
}

private fun formatClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val minutes = safe / 60_000L
    val seconds = (safe / 1_000L) % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
