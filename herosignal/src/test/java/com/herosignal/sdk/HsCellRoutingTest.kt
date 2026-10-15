package com.herosignal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Hücre yönlendirmesi (ADR-0032 §4): ortak vektörler (`sdk/testdata/cell-routing-vectors.json`) ve 421 yeniden denemesi. */
class HsCellRoutingTest {
    private val vectors: JSONObject by lazy {
        val dir = System.getProperty("herosignal.testdata") ?: "../../testdata"
        JSONObject(File(dir, "cell-routing-vectors.json").readText(Charsets.UTF_8))
    }

    private fun JSONObject.list(name: String): List<JSONObject> {
        val arr = getJSONArray(name)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun JSONObject.expected(): String? = if (isNull("expected")) null else getString("expected")

    @Test
    fun `acceptApiUrl vektorleri`() {
        val cases = vectors.list("accept")
        assertTrue(cases.size >= 10)
        for (c in cases) {
            assertEquals(c.getString("name"), c.expected(), HsCellRouting.acceptApiUrl(c.opt("candidate"), c.getString("current")))
        }
    }

    @Test
    fun `wrongCellTarget vektorleri`() {
        for (c in vectors.list("wrong_cell")) {
            val body = c.optJSONObject("body")
            assertEquals(c.getString("name"), c.expected(), HsCellRouting.wrongCellTarget(c.getInt("status"), body, c.getString("current")))
        }
    }

    @Test
    fun `baslangic koku ayni seed icin kayitli bolgesel koku kullanir`() {
        val seed = "https://api.herosignal.dev"
        assertEquals("https://api.eu.test", HsCellRouting.initialBase("$seed/", seed, "https://api.eu.test"))
        // Seed değiştiyse veya kayıt geçersizse başlangıç kökü kullanılır.
        assertEquals("https://other.test", HsCellRouting.initialBase("https://other.test", seed, "https://api.eu.test"))
        assertEquals(seed, HsCellRouting.initialBase(seed, seed, "http://evil.test"))
        assertEquals(seed, HsCellRouting.initialBase(seed, null, null))
    }

    private data class Reply(val status: Int, val body: String)

    /** Her bağlantıda tek istek okuyan ve [handler] yanıtını dönen en küçük yerel HTTP/1.1 sunucusu. */
    private class Server(private val handler: (String) -> Reply) : AutoCloseable {
        val paths = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${socket.localPort}"
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                client.use { serve(it) }
            }
        }

        private fun serve(client: Socket) {
            val input = BufferedInputStream(client.getInputStream())
            fun readLine(): String {
                val line = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1 || b == '\n'.code) break
                    if (b != '\r'.code) line.append(b.toChar())
                }
                return line.toString()
            }
            val path = readLine().split(" ")[1]
            val headers = generateSequence { readLine().takeIf { it.isNotEmpty() } }
                .map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                .toMap()
            val length = headers["content-length"]?.toInt() ?: 0
            var read = 0
            val buffer = ByteArray(length)
            while (read < length) read += input.read(buffer, read, length - read).also { check(it >= 0) }
            paths += path
            val reply = handler(path)
            val bytes = reply.body.toByteArray()
            val head = "HTTP/1.1 ${reply.status} X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }
    }

    private fun wrongCell(apiUrl: String) = Reply(
        421,
        JSONObject().put(
            "error",
            JSONObject().put("code", "HS_WRONG_CELL").put("message", "x").put("details", JSONObject().put("cell_id", "eu").put("api_url", apiUrl)),
        ).toString(),
    )

    @Test
    fun `421 HS_WRONG_CELL yeni koke gecer, istegi bir kez yeniden dener ve degisikligi bildirir`() {
        Server { Reply(200, """{"ok":true}""") }.use { regional ->
            Server { wrongCell(regional.url) }.use { global ->
                val changes = mutableListOf<String>()
                val api = HsApi(global.url, "app_1", onApiUrlChange = { changes += it })
                val result = api.request("POST", "/events", JSONObject().put("events", org.json.JSONArray()))
                assertTrue(result.getBoolean("ok"))
                assertEquals(listOf("/sdk/v1/apps/app_1/events"), global.paths)
                assertEquals(listOf(regional.url), changes)
                assertEquals(regional.url, api.apiUrl)
                // Sonraki istekler (kuyruktaki işlemler dahil) doğrudan bölgesel köke gider.
                api.request("GET", "/config")
                assertEquals(1, global.paths.size)
                assertEquals(listOf("/sdk/v1/apps/app_1/events", "/sdk/v1/apps/app_1/config"), regional.paths)
            }
        }
    }

    @Test
    fun `hucreler birbirini gosterse de tek yonlendirme izlenir (dongu yok)`() {
        var aUrl = ""
        Server { wrongCell(aUrl) }.use { b ->
            Server { wrongCell(b.url) }.use { a ->
                aUrl = a.url
                val api = HsApi(a.url, "app_1")
                try {
                    api.request("GET", "/config")
                    fail("421 beklenirdi")
                } catch (e: HsApiException) {
                    assertEquals(421, e.status)
                    assertEquals("HS_WRONG_CELL", e.code)
                    assertEquals(false, e.retryable)
                }
                assertEquals(1, a.paths.size)
                assertEquals(1, b.paths.size)
            }
        }
    }

    @Test
    fun `gecersiz api_url izlenmez`() {
        Server { wrongCell("http://evil.example") }.use { server ->
            val api = HsApi(server.url, "app_1")
            try {
                api.request("GET", "/config")
                fail("421 beklenirdi")
            } catch (e: HsApiException) {
                assertEquals(421, e.status)
            }
            assertEquals(1, server.paths.size)
            assertEquals(server.url, api.apiUrl)
        }
    }
}
