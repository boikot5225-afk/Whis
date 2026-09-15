package com.bulat.whis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: ModelManager

    private lateinit var modelSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var downloadModelButton: Button
    private lateinit var importModelButton: Button
    private lateinit var modelDownloadProgress: ProgressBar
    private lateinit var modelStatus: TextView
    private lateinit var pickAudioButton: Button
    private lateinit var audioName: TextView
    private lateinit var transcribeButton: Button
    private lateinit var transcriptionProgress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var exportTxtButton: Button
    private lateinit var exportSrtButton: Button

    private var audioUri: Uri? = null
    private var audioDisplayName: String = "podcast"
    private var isWorking = false
    private var isTranscribing = false
    private var segments: List<WhisperSegment> = emptyList()
    private var pendingExport: String = ""

    private val languages = listOf(
        LanguageOption("Автоопределение", "auto"),
        LanguageOption("Русский", "ru"),
        LanguageOption("English", "en"),
        LanguageOption("中文", "zh"),
        LanguageOption("Français", "fr"),
        LanguageOption("Español", "es"),
        LanguageOption("日本語", "ja"),
        LanguageOption("Deutsch", "de"),
    )

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            audioDisplayName = queryDisplayName(uri) ?: "audio"
            audioName.text = audioDisplayName
            statusText.text = "Аудио выбрано."
            updateControls()
        }
    }

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val displayName = queryDisplayName(uri) ?: "model.bin"
            val model = inferModelFromName(displayName)
            val modelIndex = WhisperModel.values().indexOf(model)
            if (modelIndex >= 0) modelSpinner.setSelection(modelIndex)

            lifecycleScope.launch {
                setWorking(true)
                statusText.text = "Импортирую $displayName…"
                try {
                    val file = modelManager.importModel(uri, model)
                    statusText.text = "Модель импортирована: ${humanBytes(file.length())}. Скачивать заново не нужно."
                } catch (t: Throwable) {
                    statusText.text = "Не удалось импортировать модель: ${t.message ?: t.javaClass.simpleName}"
                } finally {
                    setWorking(false)
                    updateModelUi()
                }
            }
        }
    }

    private val saveTxt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        writeExport(uri)
    }

    private val saveSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        writeExport(uri)
    }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelManager = ModelManager(this)
        bindViews()
        setupSpinners()
        setupActions()
        observeBackgroundTranscription()
        requestNotificationPermissionIfNeeded()
        updateModelUi()
        updateControls()
    }

    private fun bindViews() {
        modelSpinner = findViewById(R.id.modelSpinner)
        languageSpinner = findViewById(R.id.languageSpinner)
        downloadModelButton = findViewById(R.id.downloadModelButton)
        importModelButton = findViewById(R.id.importModelButton)
        modelDownloadProgress = findViewById(R.id.modelDownloadProgress)
        modelStatus = findViewById(R.id.modelStatus)
        pickAudioButton = findViewById(R.id.pickAudioButton)
        audioName = findViewById(R.id.audioName)
        transcribeButton = findViewById(R.id.transcribeButton)
        transcriptionProgress = findViewById(R.id.transcriptionProgress)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        exportTxtButton = findViewById(R.id.exportTxtButton)
        exportSrtButton = findViewById(R.id.exportSrtButton)
    }

    private fun setupSpinners() {
        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            WhisperModel.values(),
        )
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages,
        )

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateModelUi()
                updateControls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupActions() {
        pickAudioButton.setOnClickListener {
            openAudio.launch(arrayOf("audio/*", "video/*"))
        }

        downloadModelButton.setOnClickListener {
            downloadSelectedModel()
        }

        importModelButton.setOnClickListener {
            openModel.launch(arrayOf("application/octet-stream", "*/*"))
        }

        transcribeButton.setOnClickListener {
            startBackgroundTranscription()
        }

        exportTxtButton.setOnClickListener {
            pendingExport = segments.joinToString("\n") { it.text }
            saveTxt.launch(baseExportName() + ".txt")
        }

        exportSrtButton.setOnClickListener {
            pendingExport = buildSrt(segments)
            saveSrt.launch(baseExportName() + ".srt")
        }
    }

    private fun observeBackgroundTranscription() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TranscriptionStore.state.collect { state ->
                    isTranscribing = state.running
                    transcriptionProgress.progress = state.progress

                    if (state.audioName.isNotBlank()) {
                        audioDisplayName = state.audioName
                        audioName.text = state.audioName
                    }
                    if (state.status.isNotBlank()) {
                        statusText.text = state.status
                    }

                    segments = state.segments
                    if (segments.isNotEmpty()) {
                        resultText.text = segments.joinToString("\n\n") { it.text }
                    } else if (state.running) {
                        resultText.text = ""
                    }

                    updateControls()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun downloadSelectedModel() {
        val model = selectedModel()
        if (modelManager.isDownloaded(model)) {
            updateModelUi()
            return
        }

        lifecycleScope.launch {
            setWorking(true)
            modelDownloadProgress.visibility = View.VISIBLE
            modelDownloadProgress.progress = 0
            statusText.text = "Скачиваю ${model.title}…"

            try {
                val file = modelManager.download(model) { percent ->
                    modelDownloadProgress.progress = percent
                    statusText.text = "Скачиваю модель: $percent%"
                }
                modelDownloadProgress.progress = 100
                statusText.text = "Модель готова: ${humanBytes(file.length())}. Дальше всё работает локально."
            } catch (t: Throwable) {
                statusText.text = "Не удалось скачать модель: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                setWorking(false)
                updateModelUi()
            }
        }
    }

    private fun startBackgroundTranscription() {
        val uri = audioUri ?: return
        val model = selectedModel()
        if (!modelManager.isDownloaded(model)) {
            statusText.text = "Выбери или импортируй модель."
            return
        }

        val serviceIntent = Intent(this, TranscriptionService::class.java).apply {
            putExtra(TranscriptionService.EXTRA_AUDIO_URI, uri.toString())
            putExtra(TranscriptionService.EXTRA_AUDIO_NAME, audioDisplayName)
            putExtra(TranscriptionService.EXTRA_MODEL, model.name)
            putExtra(TranscriptionService.EXTRA_LANGUAGE, selectedLanguage().code)
        }

        isTranscribing = true
        segments = emptyList()
        resultText.text = ""
        transcriptionProgress.progress = 0
        statusText.text = "Запускаю фоновое распознавание…"
        updateControls()

        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun setWorking(working: Boolean) {
        isWorking = working
        updateControls()
    }

    private fun updateControls() {
        val busy = isWorking || isTranscribing
        val modelReady = modelManager.isDownloaded(selectedModel())

        modelSpinner.isEnabled = !busy
        languageSpinner.isEnabled = !busy
        pickAudioButton.isEnabled = !busy
        importModelButton.isEnabled = !busy
        downloadModelButton.isEnabled = !busy && !modelReady
        transcribeButton.isEnabled = !busy && modelReady && audioUri != null
        exportTxtButton.isEnabled = !busy && segments.isNotEmpty()
        exportSrtButton.isEnabled = !busy && segments.isNotEmpty()
    }

    private fun updateModelUi() {
        val model = selectedModel()
        val file = modelManager.fileFor(model)
        if (modelManager.isDownloaded(model)) {
            modelStatus.text = "На телефоне · ${humanBytes(file.length())}"
            downloadModelButton.text = "Модель уже есть"
        } else {
            modelStatus.text = when (model) {
                WhisperModel.MEDIUM_Q5_0 -> "Рекомендуется для телефона: меньше памяти и заметно быстрее Full."
                WhisperModel.MEDIUM_FULL -> "Полный Medium точнее, но на телефоне очень тяжёлый."
                else -> "Можно скачать или выбрать уже имеющийся .bin файл."
            }
            downloadModelButton.text = "Скачать модель"
        }
    }

    private fun inferModelFromName(name: String): WhisperModel {
        val lower = name.lowercase(Locale.US)
        return when {
            "large-v3-turbo-q5_0" in lower || "large_v3_turbo_q5_0" in lower -> WhisperModel.LARGE_V3_TURBO_Q5_0
            "medium-q5_0" in lower || "medium_q5_0" in lower -> WhisperModel.MEDIUM_Q5_0
            "medium" in lower -> WhisperModel.MEDIUM_FULL
            "small-q5_1" in lower || "small_q5_1" in lower -> WhisperModel.SMALL_Q5_1
            "small" in lower -> WhisperModel.SMALL_Q5_1
            else -> selectedModel()
        }
    }

    private fun selectedModel(): WhisperModel {
        return (modelSpinner.selectedItem as? WhisperModel) ?: WhisperModel.MEDIUM_Q5_0
    }

    private fun selectedLanguage(): LanguageOption {
        return (languageSpinner.selectedItem as? LanguageOption) ?: languages.first()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun writeExport(uri: Uri?) {
        if (uri == null || pendingExport.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(pendingExport)
                } ?: error("Не удалось открыть файл для записи")
            }.onSuccess {
                withContext(Dispatchers.Main) { statusText.text = "Файл сохранён." }
            }.onFailure { error ->
                withContext(Dispatchers.Main) { statusText.text = "Не удалось сохранить: ${error.message}" }
            }
        }
    }

    private fun buildSrt(items: List<WhisperSegment>): String = buildString {
        items.forEachIndexed { index, segment ->
            append(index + 1).append('\n')
            append(formatSrtTime(segment.startMs))
                .append(" --> ")
                .append(formatSrtTime(segment.endMs))
                .append('\n')
            append(segment.text).append("\n\n")
        }
    }

    private fun formatSrtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe / 60_000L) % 60L
        val seconds = (safe / 1_000L) % 60L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun humanBytes(bytes: Long): String {
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1024.0) {
            String.format(Locale.US, "%.2f ГБ", mib / 1024.0)
        } else {
            String.format(Locale.US, "%.0f МБ", mib)
        }
    }

    private fun baseExportName(): String {
        return audioDisplayName.substringBeforeLast('.', audioDisplayName).ifBlank { "transcript" }
    }

    private data class LanguageOption(val title: String, val code: String) {
        override fun toString(): String = title
    }
}
