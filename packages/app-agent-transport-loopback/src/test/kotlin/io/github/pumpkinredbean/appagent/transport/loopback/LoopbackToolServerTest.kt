package io.github.pumpkinredbean.appagent.transport.loopback

import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolJson
import io.github.pumpkinredbean.appagent.ToolProtocol
import io.github.pumpkinredbean.appagent.ToolRuntime
import io.github.pumpkinredbean.appagent.ToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopbackToolServerTest {
    private var server: LoopbackToolServer? = null

    @AfterTest
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `serves manifest and executes calls on loopback`() {
        val runtime = ToolRuntime()
        runtime.register(
            ToolSpec(
                name = "echo",
                description = "Returns text unchanged when the user asks to echo a value.",
                inputSchema = ToolJson.parseObject(
                    """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
                ),
            ),
        ) { input, _ -> buildJsonObject { put("text", input.getValue("text")) } }

        server = LoopbackToolServer(runtime, port = 0, bearerToken = "test-secret").also { it.start() }
        val port = server!!.boundPort

        val manifest = request("http://127.0.0.1:$port/v1/tools", token = "test-secret")
        assertEquals(200, manifest.status)
        assertEquals(listOf("echo"), ToolProtocol.decodeManifest(manifest.body).tools.map { it.name })

        val call = ToolCall("call-1", "echo", buildJsonObject { put("text", "hello") })
        val result = request(
            "http://127.0.0.1:$port/v1/tool-calls",
            method = "POST",
            token = "test-secret",
            body = ToolProtocol.encodeCall(call),
        )
        assertEquals(200, result.status)
        val decoded = ToolProtocol.decodeResult(result.body)
        assertTrue(decoded.ok)
        assertEquals("hello", (decoded.output!!.let { it as kotlinx.serialization.json.JsonObject })["text"]?.let {
            (it as JsonPrimitive).content
        })
    }

    @Test
    fun `rejects missing bearer token when configured`() {
        server = LoopbackToolServer(ToolRuntime(), port = 0, bearerToken = "test-secret").also { it.start() }

        val response = request("http://127.0.0.1:${server!!.boundPort}/v1/tools")

        assertEquals(401, response.status)
    }

    private fun request(
        url: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
    ): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.setRequestProperty("Accept", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream.bufferedReader().use { it.readText() }
        connection.disconnect()
        return HttpResponse(status, responseBody)
    }

    private data class HttpResponse(val status: Int, val body: String)
}
