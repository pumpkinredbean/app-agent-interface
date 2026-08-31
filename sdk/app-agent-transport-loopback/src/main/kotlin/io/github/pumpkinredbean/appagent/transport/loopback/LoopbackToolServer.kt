package io.github.pumpkinredbean.appagent.transport.loopback

import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolJson
import io.github.pumpkinredbean.appagent.ToolProtocol
import io.github.pumpkinredbean.appagent.ToolProtocolException
import io.github.pumpkinredbean.appagent.ToolResult
import io.github.pumpkinredbean.appagent.ToolRuntime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

public interface LoopbackToolServerListener {
    public fun onCallReceived(call: ToolCall) {}
    public fun onCallCompleted(call: ToolCall, result: ToolResult, durationMs: Long) {}
    public fun onServerError(error: Throwable) {}
}

/**
 * Minimal HTTP transport for Android emulators, USB-connected devices and local JVM apps.
 * It always binds to 127.0.0.1 and is not an internet-facing server.
 */
public class LoopbackToolServer @JvmOverloads constructor(
    private val runtime: ToolRuntime,
    private val port: Int = 8765,
    private val runtimeName: String = "android-app",
    private val bearerToken: String? = null,
    private val listener: LoopbackToolServerListener? = null,
    private val maxRequestBytes: Int = 1024 * 1024,
) : Closeable {
    init {
        require(bearerToken == null || bearerToken.isNotBlank()) {
            "bearerToken must be null or a non-empty secret"
        }
    }

    @Volatile
    public var isRunning: Boolean = false
        private set

    @Volatile
    public var boundPort: Int = -1
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptor: ExecutorService? = null
    private var workers: ExecutorService? = null

    @Synchronized
    @Throws(IOException::class)
    public fun start() {
        if (isRunning) return
        require(port in 0..65535) { "port must be between 0 and 65535" }
        require(maxRequestBytes in 1..16 * 1024 * 1024) { "maxRequestBytes is outside the supported range" }

        val socket = ServerSocket(port, 20, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        boundPort = socket.localPort
        acceptor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "toolcall-loopback-acceptor").apply { isDaemon = true }
        }
        workers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "toolcall-loopback-worker").apply { isDaemon = true }
        }
        isRunning = true
        acceptor?.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (isRunning) {
            try {
                val client = serverSocket?.accept() ?: return
                workers?.execute { handle(client) }
            } catch (error: IOException) {
                if (isRunning) listener?.onServerError(error)
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 15_000
                val request = readRequest(client.getInputStream())
                val response = route(request)
                writeResponse(client.getOutputStream(), response)
            }
        } catch (error: Exception) {
            listener?.onServerError(error)
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        if (request.method == "GET" && request.path == "/healthz") {
            return HttpResponse(200, buildJsonObject {
                put("ready", true)
                put("protocol", ToolProtocol.VERSION)
                put("runtime", runtimeName)
            }.toString())
        }

        if (!isAuthorized(request)) {
            return HttpResponse(
                401,
                ToolProtocol.encodeProtocolError("UNAUTHORIZED", "A valid bearer token is required"),
            )
        }

        if (request.method == "GET" && request.path in setOf("/v1/tools", "/v1/capabilities")) {
            return HttpResponse(200, ToolProtocol.encodeManifest(runtime.manifest()))
        }

        if (request.method == "POST" && request.path == "/v1/tool-calls") {
            return try {
                val call = ToolProtocol.decodeCall(request.body)
                listener?.onCallReceived(call)
                val startedAt = System.nanoTime()
                val result = runtime.executeBlocking(
                    call,
                    attributes = mapOf("transport" to "loopback-http"),
                )
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                listener?.onCallCompleted(call, result, durationMs)
                HttpResponse(200, ToolProtocol.encodeResult(result))
            } catch (error: ToolProtocolException) {
                HttpResponse(400, ToolProtocol.encodeProtocolError("INVALID_REQUEST", error.message ?: "Invalid request"))
            } catch (error: IllegalArgumentException) {
                HttpResponse(400, ToolProtocol.encodeProtocolError("INVALID_REQUEST", error.message ?: "Invalid request"))
            }
        }

        return HttpResponse(404, ToolProtocol.encodeProtocolError("ROUTE_NOT_FOUND", "Unknown route"))
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        val expected = bearerToken ?: return true
        val actual = request.headers["authorization"] ?: return false
        val expectedBytes = "Bearer $expected".toByteArray(StandardCharsets.UTF_8)
        val actualBytes = actual.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }

    private fun readRequest(input: InputStream): HttpRequest {
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw IOException("Connection closed before headers")
            headerBytes.write(value)
            state = when (state) {
                0 -> if (value == '\r'.code) 1 else 0
                1 -> if (value == '\n'.code) 2 else 0
                2 -> if (value == '\r'.code) 3 else 0
                3 -> if (value == '\n'.code) 4 else 0
                else -> state
            }
            if (state == 4) break
        }
        if (state != 4) throw IOException("HTTP headers are too large")

        val lines = headerBytes.toString(StandardCharsets.ISO_8859_1).split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: throw IOException("Missing request line")
        if (requestLine.size < 2) throw IOException("Invalid request line")

        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] =
                    line.substring(separator + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength !in 0..maxRequestBytes) throw IOException("Invalid Content-Length")

        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw IOException("Connection closed before request body")
            offset += count
        }

        return HttpRequest(
            method = requestLine[0].uppercase(Locale.ROOT),
            path = requestLine[1].substringBefore('?'),
            headers = headers,
            body = body.toString(StandardCharsets.UTF_8),
        )
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val body = response.body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    @Synchronized
    override fun close() {
        isRunning = false
        runCatching { serverSocket?.close() }
        acceptor?.shutdownNow()
        workers?.shutdownNow()
        serverSocket = null
        acceptor = null
        workers = null
        boundPort = -1
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class HttpResponse(
        val status: Int,
        val body: String,
    )

    private companion object {
        const val MAX_HEADER_BYTES: Int = 64 * 1024
    }
}
