# androsaur-audio

Звук для [Androsaur](https://github.com/androsaurs): приложение слушает микрофон и
сообщает остальным модулям, что прозвучало «Привет, Петя». Полезно и само по себе:
любой клиент (ИИ-агент через MCP, другое приложение) может читать события.

**Статус: каркас.** Микрофон читается, уровень и журнал событий работают, HTTP и MCP
отвечают. Распознавания слова пока нет: стоит заглушка, событие можно вызвать
инструментом `simulate_wake`.

## Запуск

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

MCP-инструменты: `status`, `recent_events`, `simulate_wake`. Сервис объявляется в сети
как `_androsaur-audio._tcp`.

Устройство и открытые вопросы — [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
