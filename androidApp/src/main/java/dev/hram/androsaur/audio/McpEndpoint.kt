package dev.hram.androsaur.audio

import org.json.JSONArray
import org.json.JSONObject

/** MCP over Streamable HTTP (one JSON reply per POST, no sessions), same conventions as the camera. */
class McpEndpoint(private val audio: AudioService) {
    fun handle(request: JSONObject): JSONObject? {
        val method = request.optString("method")
        if (method.isEmpty() || !request.has("id")) return null
        val id = request.get("id")
        fun reply(result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        return when (method) {
            "initialize" -> reply(JSONObject().put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "androsaur-audio").put("version", BuildConfig.VERSION_NAME))
                .put("instructions", "Phone sound service: it listens continuously and raises a wake_word event when the wake phrase is heard, and it speaks Russian aloud (speak, offline voices; mouth level in speech_status for lip sync). " +
                    "Read events with recent_events (pass the last id you saw). Only this service records; do not expect audio to be returned."))
            "ping" -> reply(JSONObject())
            "tools/list" -> reply(JSONObject().put("tools", tools()))
            "tools/call" -> {
                val params = request.optJSONObject("params") ?: JSONObject()
                reply(call(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject()))
            }
            else -> JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32601).put("message", "Method not found"))
        }
    }

    private fun tools() = JSONArray()
        .put(tool("status", "Service state: whether the microphone is being read, the wake-word engine (id, state loading/ready/failed, load time, cost per audio frame in ms against the frame length), input level in dBFS, whether the microphone is silent (another app may hold it) and the last error.",
            schema(), readOnly = true))
        .put(tool("recent_events", "Events after the given id (service_started, engine_ready, engine_failed, wake_word, speech_started, speech_finished, speech_failed, speaker_ready, speaker_failed, microphone_silent, microphone_recovered, error), oldest first, at most 100.",
            schema(props = mapOf("after" to prop("integer", "Return events with an id greater than this. Default 0."))), readOnly = true))
        .put(tool("set_engine", "Switch the wake-word engine and/or change the wake phrase; both are remembered. Pass engine, phrase or both. Loading happens in the background: poll status until engine.state is ready (or failed). engine.available in status lists the valid ids; none detects nothing, vosk recognizes the phrase with a small Russian speech model. The phrase applies to vosk and vosk-grammar (microWakeWord models have a fixed phrase); status.phrase shows the current one. Errors: unknown_engine, engine_not_available, invalid_phrase.",
            schema(props = mapOf(
                "engine" to prop("string", "Engine id from status engine.available. Omit to keep the current engine."),
                "phrase" to prop("string", "Wake phrase in Russian letters, 1-4 words, e.g. \"привет петя\". A word may list alternatives with | (\"привет|здравствуй\") and each alternative may carry a tolerance of 0-3 edits after ~ (\"петя~1\": same first letter, up to 1 edit; vosk only). Default \"привет~3 петя~1\". Omit to keep the current phrase.")))))
        .put(tool("speak", "Say a phrase aloud in Russian with the current speech engine; it replaces what is being said now. The call returns at once (speaking is in the background): watch speech_started and speech_finished in recent_events, or speaking in speech_status. The microphone is deaf while the assistant speaks and for a moment after, so it does not wake itself. At most 600 characters. Errors: empty_text, text_too_long, invalid_speed, speaker_not_ready (the voice is still loading: poll speech_status).",
            schema(props = mapOf("text" to prop("string", "What to say."), "speed" to prop("number", "Speed 0.5-2, 1 is normal. Default: the saved speed.")), required = listOf("text"))))
        .put(tool("stop_speaking", "Cut the current speech short.", schema()))
        .put(tool("speech_status", "The speech engine: speaker id, state (loading/ready/failed), load time, saved speed and threads, the engines to choose from (available), whether it speaks now, the mouth opening now (0 closed .. 1 open; approximateMouth true means only a pulse, the engine does not show its sound), whether the microphone is muted and the timing of the last phrase (firstAudioMs is the wait before sound).",
            schema(), readOnly = true))
        .put(tool("set_speaker", "Choose the speech engine, the speed, the number of threads, or all; remembered. Engines: piper:denis, piper:dmitri, piper:irina, piper:ruslan (Piper voices on the phone), system (the system speech service), system:<package> (a speech service by package, e.g. RHVoice); speech_status.available lists what is installed. The voice loads in the background: poll speech_status until state is ready. Threads (1-4) apply to Piper. Errors: unknown_speaker, speaker_not_available, invalid_speed, invalid_threads.",
            schema(props = mapOf("engine" to prop("string", "Engine id. Omit to keep the current one."), "speed" to prop("number", "Speed 0.5-2."), "threads" to prop("integer", "Threads for Piper, 1-4.")))))
        .put(tool("speech_bench", "Compare speech engines on the same ten Russian phrases. start=true begins a run in the background over the given engines (default: all available), cancel=true stops it; without either it returns the progress and results. Per engine: load time, warm-up, rtf (time to make the sound over the length of the sound: below 1 is faster than real time), the worst phrase and a row per phrase. play=true also says every phrase aloud (to compare by ear) and adds firstAudioMs, the wait before the first sound. Uses the saved speed and threads. Errors: bench_running, unknown_speaker, speaker_not_available.",
            schema(props = mapOf("start" to prop("boolean", "Begin a run."), "cancel" to prop("boolean", "Stop the run."),
                "engines" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Engine ids to measure. Default: all available."),
                "play" to prop("boolean", "Also say the phrases aloud. Default false.")))))
        .put(tool("simulate_wake", "Raise a wake_word event as if the phrase was heard. For testing listeners before a real engine is wired in.",
            schema()))

    private fun call(name: String, args: JSONObject): JSONObject = when (name) {
        "status" -> result(audio.status())
        "recent_events" -> result(JSONObject().put("events", JSONArray(audio.eventsSince(args.optLong("after", 0)).map {
            JSONObject().put("id", it.id).put("atMs", it.atMs).put("type", it.type).put("data", JSONObject(it.data))
        })))
        "set_engine" -> {
            val engine = args.optString("engine").ifEmpty { null }
            val phrase = args.optString("phrase").ifEmpty { null }
            val problem = if (engine == null && phrase == null) "nothing_to_change" else audio.configure(engine, phrase)
            result(if (problem == null) JSONObject().put("ok", true).put("phrase", audio.phrase) else JSONObject().put("error", problem), error = problem != null)
        }
        "speak" -> {
            val problem = audio.speech.speak(args.optString("text"), if (args.has("speed")) args.optDouble("speed").toFloat() else null)
            result(if (problem == null) JSONObject().put("ok", true) else JSONObject().put("error", problem), error = problem != null)
        }
        "stop_speaking" -> { audio.speech.stop(); result(JSONObject().put("ok", true)) }
        "speech_status" -> result(audio.speech.status())
        "set_speaker" -> {
            val engine = args.optString("engine").ifEmpty { null }
            val speed = if (args.has("speed")) args.optDouble("speed").toFloat() else null
            val threads = if (args.has("threads")) args.optInt("threads") else null
            val problem = if (engine == null && speed == null && threads == null) "nothing_to_change" else audio.speech.configure(engine, speed, threads)
            result(if (problem == null) JSONObject().put("ok", true) else JSONObject().put("error", problem), error = problem != null)
        }
        "speech_bench" -> when {
            args.optBoolean("cancel") -> { audio.speech.benchCancel(); result(JSONObject().put("ok", true)) }
            args.optBoolean("start") -> {
                val problem = audio.speech.benchStart(args.optJSONArray("engines")?.let { a -> List(a.length()) { a.getString(it) } }, args.optBoolean("play"))
                if (problem == null) result(JSONObject().put("ok", true)) else result(JSONObject().put("error", problem), error = true)
            }
            else -> result(audio.speech.benchJson())
        }
        "simulate_wake" -> { audio.simulateWake(); result(JSONObject().put("ok", true)) }
        else -> result(JSONObject().put("error", "unknown_tool"), error = true)
    }

    private fun result(data: JSONObject, error: Boolean = false) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString()))).put("isError", error)

    private fun tool(name: String, description: String, inputSchema: JSONObject, readOnly: Boolean = false) =
        JSONObject().put("name", name).put("description", description).put("inputSchema", inputSchema)
            .put("annotations", JSONObject().put("readOnlyHint", readOnly).put("destructiveHint", false)
                .put("idempotentHint", readOnly).put("openWorldHint", false))

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    private fun schema(props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        props.forEach { (key, value) -> properties.put(key, value) }
        return JSONObject().put("type", "object").put("properties", properties).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
    }
}
