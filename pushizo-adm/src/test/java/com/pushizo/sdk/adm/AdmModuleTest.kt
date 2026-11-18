package com.pushizo.sdk.adm

import android.content.Context
import android.content.ContextWrapper
import com.pushizo.sdk.HsPushBridge
import com.pushizo.sdk.HsPushRegistrar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdmModuleTest {

    private val context: Context = ContextWrapper(null)
    private val sink = RecordingSink()

    @Before
    fun setUp() {
        AdmDispatcher.sink = sink
    }

    @After
    fun tearDown() {
        AdmDispatcher.sink = CoreAdmSink
    }

    @Test
    fun `kayit sinifi cekirdegin yansima ile bulacagi adda ve adm_push kanalini kullanir`() {
        // Çekirdek HsPushProviders.ADM_REGISTRAR_CLASS ile aynı ad (yeniden adlandırılırsa otomatik kayıt bozulur).
        assertEquals("com.pushizo.sdk.adm.AdmRegistrar", AdmRegistrar::class.java.name)
        val registrar = Class.forName("com.pushizo.sdk.adm.AdmRegistrar").getDeclaredConstructor().newInstance()
        assertTrue(registrar is HsPushRegistrar)
        assertEquals("adm_push", HsPushBridge.CHANNEL_ADM)
    }

    @Test
    fun `ADM sinifi yoksa modul sessizce devre disi kalir`() {
        var factoryCalled = false
        AdmRegistrar({ false }, { factoryCalled = true; FakeClient("amzn1.adm-registration.v3.abc") }).start(context)
        assertFalse("ADM istemcisi oluşturulmamalı", factoryCalled)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `ADM sinifi aramasi ClassNotFoundException yakalar`() {
        assertFalse(AdmClasses.isPresent("com.amazon.device.messaging.YokBoyleSinif"))
        // Test sınıf yolunda yalnızca derleme saplaması vardır; ad doğru olmalıdır.
        assertTrue(AdmClasses.isPresent(AdmClasses.ADM))
        assertTrue(AdmClasses.isPresent(AdmClasses.JOB_BASE))
    }

    @Test
    fun `ADM istemcisi olusturulurken sinif hatasi uygulamayi cokertmez`() {
        AdmRegistrar({ true }, { throw NoClassDefFoundError("com/amazon/device/messaging/ADM") }).start(context)
        AdmRegistrar({ true }, { throw RuntimeException("Stub") }).start(context)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `kayitli kimlik varsa cekirdege iletilir, yoksa kayit baslatilir`() {
        val existing = FakeClient("  amzn1.adm-registration.v3.abc  ")
        AdmRegistrar({ true }, { existing }).start(context)
        assertEquals(listOf("registered:amzn1.adm-registration.v3.abc"), sink.events)
        assertFalse(existing.registerStarted)

        sink.events.clear()
        val fresh = FakeClient(null)
        AdmRegistrar({ true }, { fresh }).start(context)
        assertTrue(fresh.registerStarted)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `ADM desteklenmeyen cihazda kayit baslatilmaz`() {
        val client = FakeClient(null, isSupported = false)
        AdmRegistrar({ true }, { client }).start(context)
        assertFalse(client.registerStarted)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `kayit sonucu, silme ve hata cekirdege iletilir`() {
        AdmDispatcher.onRegistered(context, " amzn1.adm-registration.v3.xyz ")
        AdmDispatcher.onRegistered(context, "  ")
        AdmDispatcher.onRegistered(context, null)
        AdmDispatcher.onUnregistered(context, "amzn1.adm-registration.v3.xyz")
        AdmDispatcher.onUnregistered(context, "")
        AdmDispatcher.onRegistrationError(context, "SERVICE_NOT_AVAILABLE")
        AdmDispatcher.onRegistrationError(context, null)
        assertEquals(
            listOf(
                "registered:amzn1.adm-registration.v3.xyz",
                "unregistered:amzn1.adm-registration.v3.xyz",
                "unregistered:null",
                "error:SERVICE_NOT_AVAILABLE",
                "error:UNKNOWN",
            ),
            sink.events,
        )
        assertEquals("ADM kayıt hatası: INVALID_SENDER", AdmRegistrationException("INVALID_SENDER").message)
    }

    @Test
    fun `gelen mesaj FCM data mapine cevrilip ana hatta iletilir`() {
        val hs = """{"dlv":"dlv_1","msg":"msg_1"}"""
        AdmDispatcher.onMessage(
            context,
            mapOf(
                "hs" to hs,
                "hs_delivery_id" to "dlv_1",
                "hs_title" to "Kampanya",
                "hs_body" to "Sepetinizde ürün var",
                "order_id" to "42",
                "sayi" to 7, // metin olmayan ekstralar atlanır
                "" to "bos-anahtar",
                "yok" to null,
            ),
        )
        assertEquals(1, sink.messages.size)
        val data = sink.messages.single()
        assertEquals(listOf("hs", "hs_delivery_id", "hs_title", "hs_body", "order_id"), data.keys.toList())
        assertEquals(hs, data["hs"])
        assertEquals("Kampanya", data["hs_title"])
    }

    @Test
    fun `bos veya yalnizca metin disi ekstrali mesaj iletilmez`() {
        AdmDispatcher.onMessage(context, emptyMap())
        AdmDispatcher.onMessage(context, mapOf("sayi" to 1, "liste" to listOf("a")))
        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun `alici is kimligi sabittir`() {
        assertEquals(0x505A4144, PushizoAdmMessageReceiver.JOB_ID)
    }

    private class FakeClient(
        override val registrationId: String?,
        override val isSupported: Boolean = true,
    ) : AdmClient {
        var registerStarted = false
        override fun startRegister() {
            registerStarted = true
        }
    }

    private class RecordingSink : AdmSink {
        val events = mutableListOf<String>()
        val messages = mutableListOf<Map<String, String>>()
        override fun registered(context: Context, registrationId: String) { events += "registered:$registrationId" }
        override fun unregistered(context: Context, registrationId: String?) { events += "unregistered:$registrationId" }
        override fun registrationError(context: Context, errorId: String) { events += "error:$errorId" }
        override fun message(context: Context, data: Map<String, String>) { messages += data }
    }
}
