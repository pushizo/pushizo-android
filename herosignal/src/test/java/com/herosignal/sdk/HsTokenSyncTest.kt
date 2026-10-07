package com.herosignal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

private const val CRLF = "\r\n"

class HsTokenSyncTest {
    private data class Call(val method: String, val path: String, val body: JSONObject?)

    private fun fake(handler: (Call) -> JSONObject): Pair<HsTokenSync, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        return HsTokenSync { method, path, body -> Call(method, path, body).also { calls.add(it) }.let(handler) } to calls
    }

    private val created = JSONObject().put("subscription_id", "sub_1").put("user_id", "usr_1").put("external_id", JSONObject.NULL)

    @Test
    fun `kayit yoksa POST ile abonelik olusturulur`() {
        val (sync, calls) = fake { created }
        val result = sync.sync(null, null, "tok_a", JSONObject().put("os", "Android"), JSONObject())
        assertEquals(HsTokenSyncResult.Created("sub_1", "usr_1", null), result)
        assertEquals(1, calls.size)
        assertEquals("POST", calls[0].method)
        assertEquals("/subscriptions", calls[0].path)
        assertEquals("android_push", calls[0].body!!.getString("channel"))
        assertEquals("tok_a", calls[0].body!!.getString("token"))
        assertTrue(calls[0].body!!.getBoolean("ownership_proof"))
    }

    @Test
    fun `token degisince yeni abonelik acilmaz PATCH ile guncellenir`() {
        val (sync, calls) = fake { JSONObject().put("id", "sub_1") }
        val result = sync.sync("sub_1", "tok_a", "tok_b", JSONObject().put("app_version", "2.0"), JSONObject())
        assertEquals(HsTokenSyncResult.Updated, result)
        assertEquals(1, calls.size)
        assertEquals("PATCH", calls[0].method)
        assertEquals("/subscriptions/sub_1", calls[0].path)
        assertEquals("tok_b", calls[0].body!!.getString("token"))
        assertEquals("2.0", calls[0].body!!.getJSONObject("device").getString("app_version"))
        // Sahiplik kanıtı (ADR-0016): eski (kayıtlı) token'ın SHA-256 özeti.
        assertEquals(HsOwnership.tokenHash("tok_a"), calls[0].body!!.getString("token_hash"))
    }

    @Test
    fun `sahiplik kaniti SHA-256 ozeti ve DELETE sorgusu`() {
        // Bilinen vektör: SHA-256("abc").
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HsOwnership.tokenHash("abc"))
        assertEquals("", HsOwnership.query(null, null))
        assertEquals("?token_hash=${HsOwnership.tokenHash("ayse@example.com")}", HsOwnership.query("ayse@example.com"))
        assertEquals(
            "?token_hash=${HsOwnership.tokenHash("+905321234567")}&identity_hash=${"a".repeat(64)}",
            HsOwnership.query("+905321234567", "a".repeat(64)),
        )
        val body = HsOwnership.withProof(JSONObject().put("opted_in", false), null)
        assertFalse(body.has("token_hash"))
        assertFalse(body.has("identity_hash"))
    }

    @Test
    fun `login ve logout govdesinde sahiplik kaniti`() {
        // login: identity_hash hedef kullanıcınındır; abonelik kanıtı kayıtlı token'ın özetidir.
        val login = HsOwnership.loginBody("u-42", "b".repeat(64), "tok_a")
        assertEquals("u-42", login.getString("external_id"))
        assertEquals("b".repeat(64), login.getString("identity_hash"))
        assertEquals(HsOwnership.tokenHash("tok_a"), login.getString("token_hash"))
        val anonymous = HsOwnership.loginBody("u-42", null, null)
        assertEquals(setOf("external_id"), anonymous.keys().asSequence().toSet())
        // logout/sessions: token özeti + giriş yapmış kullanıcının identity_hash'i.
        val logout = HsOwnership.withProof(JSONObject(), "tok_a", "c".repeat(64))
        assertEquals(HsOwnership.tokenHash("tok_a"), logout.getString("token_hash"))
        assertEquals("c".repeat(64), logout.getString("identity_hash"))
    }

    @Test
    fun `token ayniysa istek gonderilmez`() {
        val (sync, calls) = fake { fail("istek beklenmiyordu"); JSONObject() }
        assertEquals(HsTokenSyncResult.Unchanged, sync.sync("sub_1", "tok_a", "tok_a", JSONObject(), JSONObject()))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `abonelik sunucuda yoksa 404 sonrasi yeniden olusturulur`() {
        val (sync, calls) = fake { call ->
            if (call.method == "PATCH") throw HsApiException(404, "HS_NOT_FOUND", "yok")
            created
        }
        val user = JSONObject().put("external_id", "u1")
        val result = sync.sync("sub_old", "tok_a", "tok_b", JSONObject(), user)
        assertTrue(result is HsTokenSyncResult.Created)
        assertEquals(listOf("PATCH", "POST"), calls.map { it.method })
        assertEquals("u1", calls[1].body!!.getJSONObject("user").getString("external_id"))
    }

    @Test
    fun `gecici hata yukari firlatilir`() {
        val (sync, _) = fake { throw HsApiException(503, "HS_SERVICE_UNAVAILABLE", "x") }
        try {
            sync.sync("sub_1", "tok_a", "tok_b", JSONObject(), JSONObject())
            fail("hata bekleniyordu")
        } catch (e: HsApiException) {
            assertEquals(503, e.status)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bos token kaydedilmez`() {
        val (sync, _) = fake { created }
        sync.sync(null, null, " ", JSONObject(), JSONObject())
    }

    @Test
    fun `PATCH POST ve X-HTTP-Method-Override ile gonderilir`() {
        assertEquals("POST" to "PATCH", HsApi.wireMethod("PATCH"))
        assertEquals("DELETE" to null, HsApi.wireMethod("DELETE"))
        assertEquals("POST" to null, HsApi.wireMethod("POST"))

        // Gerçek HttpURLConnection ile: PATCH ProtocolException atmadan POST + override başlığı olarak gider.
        // (Android derleme sınıf yolunda com.sun.net.httpserver yok; tek istekli yalın HTTP sunucusu.)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var requestLine: String? = null
        val headers = mutableMapOf<String, String>()
        var body: String? = null
        val worker = thread {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                requestLine = input.readLine()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val (name, value) = line.split(":", limit = 2).map { it.trim() }
                    headers[name.lowercase()] = value
                }
                val length = headers["content-length"]?.toInt() ?: 0
                body = CharArray(length).also { buf -> var read = 0; while (read < length) read += input.read(buf, read, length - read) }.concatToString()
                val response = """{"id":"sub_1"}"""
                socket.getOutputStream().write(
                    listOf("HTTP/1.1 200 OK", "Content-Type: application/json", "Content-Length: ${response.length}", "Connection: close", "", response).joinToString(CRLF).toByteArray(),
                )
            }
        }
        try {
            val api = HsApi("http://127.0.0.1:${server.localPort}", "app_1")
            val result = api.request("PATCH", "/subscriptions/sub_1", JSONObject().put("token", "tok_b"))
            worker.join(5_000)
            assertEquals("sub_1", result.getString("id"))
            assertEquals("POST /sdk/v1/apps/app_1/subscriptions/sub_1 HTTP/1.1", requestLine)
            assertEquals("PATCH", headers[HsApi.METHOD_OVERRIDE_HEADER.lowercase()])
            assertEquals("tok_b", JSONObject(body!!).getString("token"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `alias etiketleri dogrulanir`() {
        assertEquals(mapOf("crm_id" to "42"), HsAliases.normalize(mapOf("crm_id" to " 42 ")))
        for (bad in listOf(mapOf("CRM" to "1"), mapOf("external_id" to "1"), mapOf("hs_id" to "1"), mapOf("crm_id" to " "), emptyMap())) {
            try {
                HsAliases.normalize(bad)
                fail("geçersiz alias kabul edildi: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        val many = (0..20).associate { "a$it" to "v" }
        try {
            HsAliases.normalize(many)
            fail("21 alias kabul edildi")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `hata raporlari tekillestirilir sinirlanir ve ag hatalari atlanir`() {
        val reporter = HsErrorReporter("android", "0.1.0", maxPerSession = 3)
        assertTrue(reporter.record("registration_failed", "kayıt başarısız", HsApiException(500, "HS_INTERNAL", "x")))
        assertFalse(reporter.record("registration_failed", "kayıt başarısız", HsApiException(500, "HS_INTERNAL", "x")))
        assertFalse(reporter.record("x", "ağ", HsApiException(0, "NETWORK", "offline")))
        assertFalse(reporter.record("x", "ağ", java.io.IOException("offline")))
        val token = "f".repeat(64)
        assertTrue(reporter.record("b", "token $token", context = mapOf("op" to "tags")))
        assertTrue(reporter.record("c", "üçüncü"))
        assertFalse(reporter.record("d", "limit aşıldı"))
        val batch = reporter.drain()!!.getJSONArray("errors")
        assertEquals(3, batch.length())
        val first = batch.getJSONObject(0)
        assertEquals("android", first.getString("platform"))
        assertEquals("kayıt başarısız: HTTP 500 HS_INTERNAL", first.getString("message"))
        assertTrue(first.getString("occurred_at").endsWith("Z"))
        val second = batch.getJSONObject(1)
        assertFalse(second.getString("message").contains(token))
        assertEquals("tags", second.getJSONObject("context").getString("op"))
        assertNull(reporter.drain())
    }
}
