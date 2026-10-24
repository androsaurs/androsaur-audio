# androsaur-audio

Звук для [Androsaur](https://github.com/androsaurs): приложение слушает микрофон и
сообщает остальным модулям, что прозвучало «Привет, Петя». Полезно и само по себе:
любой клиент (ИИ-агент через MCP, другое приложение) может читать события.

**Статус: каркас с двумя движками.** Микрофон читается, HTTP и MCP отвечают. Wake-word
распознают два сменных движка: `vosk` (русская модель, ищет «привет петя» в распознанном тексте; `vosk-grammar` — ограниченная фразой грамматика) и
`mww:<имя>` (microWakeWord, пока английские тестовые модели). Выбор — на экране, через
`POST /engine` или MCP `set_engine`; `none` отключает распознавание. Событие можно
вызвать без голоса инструментом `simulate_wake`.

**Ключевая фраза** задаётся для `vosk` и `vosk-grammar` (у microWakeWord фраза зашита в модель): поле `phrase`
в `POST /engine` (`{"engine": "vosk", "phrase": "привет петя"}`, оба поля необязательны, но нужно хотя бы одно)
или в MCP `set_engine`; текущая фраза — `phrase` в `status`. Русские буквы, 1–4 слова; у слова могут быть
варианты через `|` и допуск `~N` (0–3 правки, та же первая буква): `привет|здравствуй петя~1`. Без `~` слово
должно совпасть точно. По умолчанию `привет~3 петя~1`. Ошибки: `invalid_phrase`, `unknown_engine`,
`engine_not_available`. Фраза хранится в настройках и показывается в уведомлении.

**Речь (синтез, офлайн).** Сервис ещё и говорит по-русски: сменные движки `piper:denis|dmitri|irina|ruslan` (голоса Piper через
sherpa-onnx, на телефоне) и `system` / `system:<пакет>` (системная служба речи Android, в том числе RHVoice, если установлен).
Пока ассистент говорит и 0,6 с после, движок wake-word слышит тишину, чтобы не разбудить себя своим голосом.
Управление: экран «Голос: настройка и проверка» (движок, скорость, потоки, проверка фразы, живой рот, сравнение движков на
десяти одинаковых фразах), `POST /speak {"text","speed"}`, `POST /speak/stop`, `POST /speaker {"engine","speed","threads"}`,
`GET /speech` (идёт ли речь и раскрытие рта 0..1; `?full=1` — всё состояние), `GET|POST /speech/bench`; MCP: `speak`,
`stop_speaking`, `speech_status`, `set_speaker`, `speech_bench`. События: `speech_started`, `speech_finished`, `speech_failed`,
`speaker_ready`, `speaker_failed`. Рот у Piper следует за громкостью звука, который играет в этот момент; у системной речи звука нет,
рот только пульсирует (`approximate`). Библиотеку и голоса скачивает `./scripts/fetch-tts.sh` (в git их нет); лицензии — в
`androidApp/src/main/assets/tts/NOTICE.md`. Нужна именно sherpa-onnx 1.13.x: в 2.0.0 убран встроенный фонемизатор espeak.

## Запуск

Модель Vosk (46 МБ) в git не лежит: `./scripts/fetch-vosk-model.sh` кладёт её в assets с
проверкой хэша; так же `./scripts/fetch-tts.sh` для речи (библиотека и четыре голоса, около 140 МБ). Для сборки нужны NDK 29 и CMake 3.22.1 (модуль `microwakeword`).

```
./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Откройте «Androsaur Звук», разрешите микрофон. На экране адрес и код доступа.

```
curl -H "Authorization: Bearer <код>" http://<адрес>:8089/status
curl -H "Authorization: Bearer <код>" "http://<адрес>:8089/events?after=0"
claude mcp add --transport http androsaur-audio http://<адрес>:8089/mcp --header "Authorization: Bearer <код>"
```

MCP-инструменты: `status`, `recent_events`, `set_engine`, `simulate_wake`, `speak`, `stop_speaking`, `speech_status`, `set_speaker`, `speech_bench`. Сервис объявляется в сети
как `_androsaur-audio._tcp`.

Устройство и открытые вопросы — [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
