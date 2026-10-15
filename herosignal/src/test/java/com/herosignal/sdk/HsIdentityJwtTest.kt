package com.herosignal.sdk

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** JWT identity verification (ADR-0023 §9): başlık, mod kuralları ve kalıcı belirteç yaşam döngüsü. */
class HsIdentityJwtTest {

    private data class Captured(val method: String, val uri: String, val authorization: String?, val body: String)

    /**
     * Gelen istekleri kaydeden en küçük yerel HTTP/1.1 sunucusu (ServerSocket; Android test sınıf yolunda JDK
     * `HttpServer` yok). Her bağlantıda tek istek okunur ve JSON yanıt verilir.
     */
    private fun <T> withServer(block: (baseUrl: String, captured: List<Captured>) -> T): T {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                socket.use { captured += serve(it) }
            }
        }
        try {
            return block("http://127.0.0.1:${server.localPort}", captured)
        } finally {
            server.close()
            worker.join(1_000)
        }
    }

    private fun serve(socket: Socket): Captured {
        val input = BufferedInputStream(socket.getInputStream())
        fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1 || b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
            }
            return line.toString()
        }
        val (method, uri) = readLine().split(" ").let { it[0] to it[1] }
        val headers = generateSequence { readLine().takeIf { it.isNotEmpty() } }
            .map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            .toMap()
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length).also { buffer ->
            var read = 0
            while (read < length) read += input.read(buffer, read, length - read).also { check(it >= 0) }
        }
        val response = """{"user_id":"usr_1","external_id":"user_42","tags":{}}""".toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(response)
            flush()
        }
        return Captured(method, uri, headers["authorization"], String(body, Charsets.UTF_8))
    }

    @Test
    fun `jwt varsa Authorization Bearer basligiyla gider, govdeye ve URL'ye yazilmaz`() = withServer { baseUrl, captured ->
        val jwt = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1c2VyXzQyIn0.c2ln"
        val api = HsApi(baseUrl, "app_1", identityToken = { jwt })
        api.request("POST", "/subscriptions/sub_1/login", HsOwnership.loginBody("user_42", null, "fcm-token"))
        api.request("GET", "/config")
        assertEquals(2, captured.size)
        captured.forEach {
            assertEquals("Bearer $jwt", it.authorization)
            assertFalse("belirteç gövdede olmamalı", it.body.contains(jwt))
            assertFalse("belirteç URL'de olmamalı", it.uri.contains(jwt))
            assertTrue(it.uri.startsWith("/sdk/v1/apps/app_1/"))
        }
        assertFalse(JSONObject(captured[0].body).has("jwt"))
    }

    @Test
    fun `jwt yoksa veya bossa Authorization basligi eklenmez`() = withServer { baseUrl, captured ->
        HsApi(baseUrl, "app_1").request("GET", "/config")
        HsApi(baseUrl, "app_1", identityToken = { "" }).request("GET", "/config")
        assertEquals(listOf(null, null), captured.map { it.authorization })
    }

    @Test
    fun `belirtec her istekte yeniden okunur (updateUserJwt sonrasi yeni deger gider)`() = withServer { baseUrl, captured ->
        var current: String? = "jwt-1"
        val api = HsApi(baseUrl, "app_1", identityToken = { current })
        api.request("GET", "/config")
        current = "jwt-2"
        api.request("PATCH", "/users/usr_1", JSONObject().put("tags", JSONObject().put("plan", "pro")))
        current = null
        api.request("GET", "/config")
        assertEquals(listOf("Bearer jwt-1", "Bearer jwt-2", null), captured.map { it.authorization })
    }

    @Test
    fun `config modu okunur, alan yoksa veya taninmiyorsa hmac sayilir`() {
        assertEquals(HsIdentityVerificationMode.HMAC, HsIdentityVerificationMode.fromConfig(JSONObject()))
        assertEquals(HsIdentityVerificationMode.HMAC, HsIdentityVerificationMode.fromConfig(JSONObject().put("identity_verification_mode", "rsa")))
        assertEquals(HsIdentityVerificationMode.JWT, HsIdentityVerificationMode.fromConfig(JSONObject().put("identity_verification_mode", "jwt")))
        assertEquals(HsIdentityVerificationMode.BOTH, HsIdentityVerificationMode.fromConfig(JSONObject().put("identity_verification_mode", "both")))
        assertEquals(HsIdentityVerificationMode.HMAC, HsIdentityVerificationMode.fromConfig(JSONObject().put("identity_verification_mode", "hmac")))
    }

    @Test
    fun `login kanit kurallari web SDK ile aynidir`() {
        val jwt = HsIdentityVerificationMode.JWT
        val hmac = HsIdentityVerificationMode.HMAC
        val both = HsIdentityVerificationMode.BOTH
        // Doğrulama zorunlu değilse kanıt aranmaz.
        HsIdentityVerificationMode.values().forEach { assertNull(HsIdentityVerification.loginError(false, it, null, null)) }
        // jwt modu: JWT şart, HMAC yetmez.
        assertNull(HsIdentityVerification.loginError(true, jwt, null, "t"))
        assertTrue(HsIdentityVerification.loginError(true, jwt, "h", null)!!.contains("JWT"))
        assertTrue(HsIdentityVerification.loginError(true, jwt, null, "")!!.contains("JWT"))
        // hmac modu: identityHash şart, JWT yetmez.
        assertNull(HsIdentityVerification.loginError(true, hmac, "h", null))
        assertTrue(HsIdentityVerification.loginError(true, hmac, null, "t")!!.contains("identityHash"))
        // both: biri yeter.
        assertNull(HsIdentityVerification.loginError(true, both, "h", null))
        assertNull(HsIdentityVerification.loginError(true, both, null, "t"))
        assertTrue(HsIdentityVerification.loginError(true, both, null, null)!!.contains("veya"))
        try {
            HsIdentityVerification.requireLoginProof(true, jwt, "h", null)
            fail("jwt modunda JWT'siz login reddedilmeli")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `belirtec kalici saklanir, logout kimlik temizliginde silinir`() {
        val store = HsStorage(MemoryPrefs())
        store.externalId = "user_42"
        store.userJwt = "jwt-1"
        store.userIdentityHash = "h"
        assertEquals("jwt-1", store.userJwt)
        store.clearIdentity()
        assertNull(store.userJwt)
        assertNull(store.userIdentityHash)
        assertNull(store.externalId)
    }

    @Test
    fun `updateUserJwt giris yapmis kullanicinin belirtecini yeniler, giris yoksa hata verir`() {
        val store = HsStorage(MemoryPrefs())
        try {
            HsIdentityVerification.updateJwt(store, "jwt-1")
            fail("login olmadan JWT güncellenmemeli")
        } catch (_: IllegalStateException) {
        }
        assertNull(store.userJwt)
        store.externalId = "user_42"
        store.userJwt = "jwt-1"
        try {
            HsIdentityVerification.updateJwt(store, " ")
            fail("boş JWT reddedilmeli")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("jwt-1", store.userJwt)
        HsIdentityVerification.updateJwt(store, "jwt-2")
        assertEquals("jwt-2", store.userJwt)
    }

    /** Bellek içi [SharedPreferences] (birim testinde Android çalışma zamanı yok). */
    private class MemoryPrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            private fun change(block: () -> Unit): SharedPreferences.Editor {
                block()
                return this
            }

            override fun putString(key: String, value: String?) = change { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = change { changes[key] = values }
            override fun putInt(key: String, value: Int) = change { changes[key] = value }
            override fun putLong(key: String, value: Long) = change { changes[key] = value }
            override fun putFloat(key: String, value: Float) = change { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = change { changes[key] = value }
            override fun remove(key: String) = change { removals += key }
            override fun clear() = change { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removals.forEach { values.remove(it) }
                // Android'de `putString(key, null)` anahtarı siler.
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
