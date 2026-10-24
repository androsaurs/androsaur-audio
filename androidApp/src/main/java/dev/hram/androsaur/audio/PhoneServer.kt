package dev.hram.androsaur.audio

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Small bounded HTTP/1.1 server; the same shape as the camera's, on its own port.
 * Every request needs the Bearer code. No CORS and no browser origins.
 */
class PhoneServer(private val audio: AudioService, private val token: String) {
    private val listener = ServerSocket()
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val mcp = McpEndpoint(audio)
    @Volatile private var closed = false

    fun start() {
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(PORT))
        Thread({
            while (!closed) {
                try {
                    val client = listener.accept()
                    try { workers.execute { client.use { handle(it) } } }
                    catch (_: RejectedExecutionException) { client.close() }
                } catch (e: Exception) { if (!closed) Log.w(TAG, "HTTP accept failed", e) }
            }
        }, "audio-http").start()
    }

    fun close() { closed = true; listener.close(); workers.shutdownNow() }

    private fun handle(socket: Socket) {
        socket.soTimeout = 5000
        socket.tcpNoDelay = true
        try {
            val input = BufferedInputStream(socket.getInputStream())
            fun line(): String {
                val bytes = ArrayList<Byte>()
                while (true) {
                    val next = input.read()
                    require(next >= 0) { "Unexpected EOF" }
                    if (next == 10) break
                    require(bytes.size < 4096) { "Header too large" }
                    if (next != 13) bytes.add(next.toByte())
                }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            val parts = line().split(' ')
            require(parts.size == 3 && parts[0] in setOf("GET", "POST", "DELETE"))
            val method = parts[0]
            val uri = URI(parts[1])
            val headers = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                require(++count <= 40)
                val colon = header.indexOf(':'); require(colon > 0)
                headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
            }
            val supplied = headers["authorization"]?.removePrefix("Bearer ") ?: ""
            if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
                json(socket, 401, JSONObject().put("error", "unauthorized")); return
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 0..16384 && "transfer-encoding" !in headers)
            val bodyBytes = ByteArray(length)
            var received = 0
            while (received < length) {
                val next = input.read(bodyBytes, received, length - received)
                require(next > 0); received += next
            }
            val body = if (length == 0) JSONObject() else JSONObject(bodyBytes.toString(Charsets.UTF_8))
            val query = (uri.rawQuery ?: "").split('&').filter { '=' in it }.associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            when ("$method ${uri.path}") {
                "GET /status" -> json(socket, 200, audio.status())
                "GET /events" -> {
                    val events = audio.eventsSince(query["after"]?.toLongOrNull() ?: 0L)
                    json(socket, 200, JSONObject().put("events", JSONArray(events.map {
                        JSONObject().put("id", it.id).put("atMs", it.atMs).put("type", it.type).put("data", JSONObject(it.data))
                    })))
                }
                "POST /mcp" -> {
                    // Browsers always send Origin; MCP clients do not. Refusing it blocks DNS-rebinding and cross-site calls.
                    if ("origin" in headers) { json(socket, 403, JSONObject().put("error", "origin_not_allowed")); return }
                    val reply = mcp.handle(body)
                    if (reply == null) respond(socket, 202, ByteArray(0)) else json(socket, 200, reply)
                }
                "GET /mcp", "DELETE /mcp" -> respond(socket, 405, ByteArray(0), mapOf("Allow" to "POST"))
                else -> json(socket, 404, JSONObject().put("error", "not_found"))
            }
        } catch (e: Exception) {
            Log.d(TAG, "HTTP request ended: ${e.javaClass.simpleName}")
            try { json(socket, 400, JSONObject().put("error", "bad_request")) } catch (_: Exception) { }
        }
    }

    private fun json(socket: Socket, code: Int, json: JSONObject) = respond(socket, code, json.toString().toByteArray(), emptyMap(), "application/json")
    private fun respond(socket: Socket, code: Int, bytes: ByteArray, extra: Map<String, String> = emptyMap(), type: String = "application/json") {
        val reason = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed")[code] ?: "Error"
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
            extra.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        BufferedOutputStream(socket.getOutputStream(), 16 * 1024).apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    companion object {
        /** The camera uses 8088; both apps live on one phone. */
        const val PORT = 8089
        private const val TAG = "AndrosaurAudio"
    }
}
