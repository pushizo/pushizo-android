package com.pushizo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class HsConsentAndInitTest {

    @Test
    fun `onay kapisi kapaliyken istek baglanti acmadan yeniden denenebilir hatayla reddedilir`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 500
            var accepted = false
            val acceptor = thread {
                try {
                    server.accept().close()
                    accepted = true
                } catch (_: SocketTimeoutException) {
                    // Beklenen: hiç bağlantı gelmez.
                } catch (_: Exception) {
                }
            }
            val api = HsApi("http://127.0.0.1:${server.localPort}", "app_1", networkAllowed = { false })
            try {
                api.request("POST", "/events", JSONObject())
                fail("onay yokken istek gönderilmemeli")
            } catch (e: HsApiException) {
                assertEquals(HsApi.CONSENT_REQUIRED, e.code)
                assertTrue("kuyruk işlemi atılmamalı (yeniden denenebilir)", e.retryable)
            }
            acceptor.join()
            assertEquals(false, accepted)
        }
    }

    @Test
    fun `baslatma yapilandirmasi kaydedilip geri okunur`() {
        val options = Pushizo.Options(
            apiUrl = "https://api.example.com",
            requiresPrivacyConsent = true,
            logLevel = HsLog.Level.DEBUG,
            defaultChannelName = "Kampanyalar",
            defaultChannelId = "promo",
        )
        val decoded = HsInitConfig.decode(JSONObject(HsInitConfig.encode("app_1", options).toString()))
        assertEquals("app_1" to options, decoded)
    }

    @Test
    fun `eksik veya bozuk yapilandirmada otomatik baslatma yapilmaz, eksik alanlar varsayilana duser`() {
        assertNull(HsInitConfig.decode(JSONObject()))
        assertNull(HsInitConfig.decode(JSONObject().put("app_id", " ")))
        val (appId, options) = HsInitConfig.decode(JSONObject().put("app_id", "app_2").put("log_level", "YOK"))!!
        assertEquals("app_2", appId)
        assertEquals(Pushizo.Options(), options)
    }
}
