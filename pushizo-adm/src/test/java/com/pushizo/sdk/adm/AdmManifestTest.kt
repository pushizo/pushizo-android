package com.pushizo.sdk.adm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Uygulamaya manifest merge ile eklenecek ADM tanımları (izinler, özellik, servisler, alıcı). */
class AdmManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"
    private val amazon = "http://schemas.amazon.com/apk/res/android"

    private val manifest: Element by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
    }

    private fun elements(parent: Element, ns: String?, name: String): List<Element> {
        val nodes = if (ns == null) parent.getElementsByTagName(name) else parent.getElementsByTagNameNS(ns, name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    @Test
    fun `uygulamaya ozel ve ADM izinleri istenir`() {
        val permission = elements(manifest, null, "permission").single()
        assertEquals("\${applicationId}.permission.RECEIVE_ADM_MESSAGE", permission.attr("name"))
        assertEquals("signature", permission.attr("protectionLevel"))
        val uses = elements(manifest, null, "uses-permission").map { it.attr("name") }.toSet()
        assertTrue(uses.containsAll(setOf(
            "\${applicationId}.permission.RECEIVE_ADM_MESSAGE",
            "com.amazon.device.messaging.permission.RECEIVE",
            "android.permission.WAKE_LOCK",
        )))
    }

    @Test
    fun `ADM ozelligi zorunlu degildir (Google Play cihazlarina da kurulur)`() {
        val feature = elements(manifest, amazon, "enable-feature").single()
        assertEquals("com.amazon.device.messaging", feature.attr("name"))
        assertEquals("false", feature.attr("required"))
    }

    @Test
    fun `isleyiciler ve alici dogru izin ve niyetlerle tanimlidir`() {
        val services = elements(manifest, null, "service").associateBy { it.attr("name") }
        val job = services.getValue(".PushizoAdmMessageHandler")
        assertEquals("android.permission.BIND_JOB_SERVICE", job.attr("permission"))
        assertEquals("false", job.attr("exported"))
        assertEquals("false", services.getValue(".PushizoAdmLegacyMessageHandler").attr("exported"))

        val receiver = elements(manifest, null, "receiver").single()
        assertEquals(".PushizoAdmMessageReceiver", receiver.attr("name"))
        assertEquals("com.amazon.device.messaging.permission.SEND", receiver.attr("permission"))
        assertEquals("true", receiver.attr("exported"))
        val actions = elements(receiver, null, "action").map { it.attr("name") }.toSet()
        assertEquals(setOf("com.amazon.device.messaging.intent.REGISTRATION", "com.amazon.device.messaging.intent.RECEIVE"), actions)
        assertEquals("\${applicationId}", elements(receiver, null, "category").single().attr("name"))

        // Manifest adları gerçek sınıflara karşılık gelir (yeniden adlandırmada birleşim bozulmasın).
        val pkg = "com.pushizo.sdk.adm"
        assertEquals("$pkg.PushizoAdmMessageHandler", PushizoAdmMessageHandler::class.java.name)
        assertEquals("$pkg.PushizoAdmLegacyMessageHandler", PushizoAdmLegacyMessageHandler::class.java.name)
        assertEquals("$pkg.PushizoAdmMessageReceiver", PushizoAdmMessageReceiver::class.java.name)
    }
}
