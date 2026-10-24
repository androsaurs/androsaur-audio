# androsaur-audio

Звук для [Androsaur](https://github.com/androsaurs): приложение слушает микрофон и
сообщает остальным модулям, что прозвучало «Привет, Петя». Полезно и само по себе:
любой клиент (ИИ-агент через MCP, другое приложение) может читать события.

**Статус: каркас с двумя движками.** Микрофон читается, HTTP и MCP отвечают. Wake-word
распознают два сменных движка: `vosk` (русская модель, фраза «привет петя») и
`mww:<имя>` (microWakeWord, пока английские тестовые модели). Выбор — на экране, через
`POST /engine` или MCP `set_engine`; `none` отключает распознавание. Событие можно
вызвать без голоса инструментом `simulate_wake`.

## Запуск

Модель Vosk (46 МБ) в git не лежит: `./scripts/fetch-vosk-model.sh` кладёт её в assets с
проверкой хэша. Для сборки нужны NDK 29 и CMake 3.22.1 (модуль `microwakeword`).

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

MCP-инструменты: `status`, `recent_events`, `set_engine`, `simulate_wake`. Сервис объявляется в сети
как `_androsaur-audio._tcp`.

Устройство и открытые вопросы — [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
