package com.pcontrol.loopbrowser

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Loopback-only authenticated bridge for the existing Termux MCP server.
 * The Activity supplies an action handler; this class never exposes shell execution.
 */
class BrowserBridge(
    private val actionHandler: (String, JSONObject) -> JSONObject,
    private val statusHandler: (String) -> Unit = {}
) {
    @Volatile private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool()
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        workers.execute {
            try {
                val socketServer = ServerSocket(8789, 12, java.net.InetAddress.getByName("127.0.0.1"))
                socketServer.soTimeout = 1000
                server = socketServer
                running = true
                statusHandler("Listening on 127.0.0.1:8789")
                android.util.Log.i("LoopBrowserBridge", "Listening on 127.0.0.1:8789")
                while (running && !socketServer.isClosed) {
                    try {
                        val client = socketServer.accept()
                        workers.execute { handle(client) }
                    } catch (_: SocketTimeoutException) { }
                }
            } catch (e: Exception) {
                running = false
                val message = "Bridge listener failed: " + (e.message ?: e.javaClass.simpleName)
                statusHandler(message)
                android.util.Log.e("LoopBrowserBridge", "Listener failed to bind or accept on 127.0.0.1:8789", e)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 4000
                val input = BufferedInputStream(client.getInputStream())
                val head = java.io.ByteArrayOutputStream()
                var matched = 0
                while (head.size() < 8192 && matched < 4) {
                    val b = input.read()
                    if (b < 0) return
                    head.write(b)
                    matched = when {
                        matched == 0 && b == 13 -> 1
                        matched == 1 && b == 10 -> 2
                        matched == 2 && b == 13 -> 3
                        matched == 3 && b == 10 -> 4
                        b == 13 -> 1
                        else -> 0
                    }
                }
                val lines = head.toString("UTF-8").split("\r\n")
                val first = lines.firstOrNull().orEmpty().split(" ")
                if (first.size < 2) return respond(client, 400, JSONObject().put("error", "bad request"))
                val headers = mutableMapOf<String, String>()
                lines.drop(1).forEach { line ->
                    val at = line.indexOf(':')
                    if (at > 0) headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 0..8192) return respond(client, 413, JSONObject().put("error", "request too large"))
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(body, offset, length - offset)
                    if (count < 0) break
                    offset += count
                }
                // No pairing token: this listener is bound strictly to 127.0.0.1.
                val payload = try { if (body.isNotEmpty()) JSONObject(String(body, Charsets.UTF_8)) else JSONObject() }
                    catch (_: Exception) { return respond(client, 400, JSONObject().put("error", "invalid JSON")) }
                val route = first[1].substringBefore("?")
                val key = first[0] + " " + route
                val allowed = setOf(
                    "GET /health", "GET /v1/browser/state",
                    "POST /v1/browser/navigate", "POST /v1/browser/back",
                    "POST /v1/browser/reload", "POST /v1/browser/capture",
                    "POST /v1/browser/start", "POST /v1/browser/stop"
                )
                if (key !in allowed) return respond(client, 404, JSONObject().put("error", "not found"))
                // Health is deliberately independent of the Activity/UI thread.
                // This lets Termux diagnose the listener even when the Activity is backgrounded.
                val result = if (key == "GET /health") {
                    JSONObject().put("ok", true).put("bridge", "loop-browser")
                        .put("version", 1).put("running", running)
                } else {
                    actionHandler(key, payload)
                }
                respond(client, if (result.optBoolean("ok", true)) 200 else 400, result)
            } catch (e: Exception) {
                android.util.Log.e("LoopBrowserBridge", "Request handling failed", e)
                try { respond(client, 500, JSONObject().put("error", "bridge request failed")) } catch (_: Exception) { }
            }
        }
    }

    private fun respond(socket: Socket, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 " + code + " " + reason +
            "\r\nContent-Type: application/json; charset=utf-8" +
            "\r\nContent-Length: " + bytes.size +
            "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        workers.shutdownNow()
    }
}
