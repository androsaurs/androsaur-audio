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
                .put("instructions", "Phone microphone service. It listens continuously and raises a wake_word event when the wake phrase is heard. " +
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
        .put(tool("status", "Service state: whether the microphone is being read, the wake-word engine, input level in dBFS, whether the microphone is silent (another app may hold it) and the last error.",
            schema(), readOnly = true))
        .put(tool("recent_events", "Events after the given id (service_started, wake_word, microphone_silent, microphone_recovered, error), oldest first, at most 100.",
            schema(props = mapOf("after" to prop("integer", "Return events with an id greater than this. Default 0."))), readOnly = true))
        .put(tool("simulate_wake", "Raise a wake_word event as if the phrase was heard. For testing listeners before a real engine is wired in.",
            schema()))

    private fun call(name: String, args: JSONObject): JSONObject = when (name) {
        "status" -> result(audio.status())
        "recent_events" -> result(JSONObject().put("events", JSONArray(audio.eventsSince(args.optLong("after", 0)).map {
            JSONObject().put("id", it.id).put("atMs", it.atMs).put("type", it.type).put("data", JSONObject(it.data))
        })))
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

    private fun schema(props: Map<String, JSONObject> = emptyMap()): JSONObject {
        val properties = JSONObject()
        props.forEach { (key, value) -> properties.put(key, value) }
        return JSONObject().put("type", "object").put("properties", properties)
    }
}
