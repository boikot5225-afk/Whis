# Whis

Whis — локальное Android-приложение для расшифровки подкастов через `whisper.cpp`. Терминал на телефоне не нужен: выбираешь аудио, модель и язык, нажимаешь «Распознать».

## Что уже заложено в v0.1

- обычный Android-интерфейс;
- импорт MP3/M4A/AAC/OGG/FLAC/WAV через системный выбор файла (поддержка конкретного кодека зависит от Android);
- локальное декодирование через Android `MediaCodec`;
- приведение звука к mono 16 kHz;
- обработка длинного аудио пятиминутными кусками, чтобы не держать весь подкаст в RAM;
- локальный `whisper.cpp` без отправки аудио в облако;
- языки Auto/RU/EN/ZH/FR/ES/JA/DE;
- модели `small-q5_1`, `medium-q5_0`, `large-v3-turbo-q5_0`;
- скачивание модели один раз с официального хранилища whisper.cpp;
- TXT и SRT экспорт;
- сборка только `arm64-v8a` — приоритет Samsung Galaxy S24 Ultra.

## Движок

Сборка закреплена на `ggml-org/whisper.cpp` **v1.9.4**. Исходники движка не коммитятся в этот репозиторий: workflow и `scripts/fetch-whisper.sh` скачивают закреплённую версию перед сборкой.

Лицензия whisper.cpp сохранена в `licenses/whisper.cpp-LICENSE`.

## Получить APK без Android Studio

GitHub Actions собирает debug APK при push в `main`/`android-mvp` и вручную через `workflow_dispatch`.

После успешной сборки: **Actions → Android APK → нужный run → Artifacts → Whis-debug**.

## Локальная сборка

```bash
bash scripts/fetch-whisper.sh
gradle :app:assembleDebug
```

Нужны Android SDK 34, NDK `25.2.9519653`, CMake `3.22.1`, JDK 17 и Gradle 8.2.

## Ближайшие улучшения

- foreground service для многочасовых подкастов при погашенном экране;
- настоящий нативный progress callback из whisper.cpp;
- контроль температуры/числа потоков;
- сохранение истории расшифровок;
- проверка SHA-256 моделей;
- более аккуратный overlap между аудиочанками, чтобы не резать фразы на границе пяти минут.
