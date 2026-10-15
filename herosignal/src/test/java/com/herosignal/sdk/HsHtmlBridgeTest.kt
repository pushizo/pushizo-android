package com.herosignal.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** HTML in-app köprüsü: ortak vektörler (`sdk/testdata/html-bridge-vectors.json`; Web ve iOS aynı dosyayı kullanır). */
class HsHtmlBridgeTest {
    private val vectors: JSONObject by lazy {
        val dir = System.getProperty("herosignal.testdata") ?: "../../testdata"
        JSONObject(File(dir, "html-bridge-vectors.json").readText(Charsets.UTF_8))
    }

    /** Karşılaştırma için JSON değerini düz Kotlin değerine çevirir (sayılar Double). */
    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { normalize(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { normalize(value.opt(it)) }
        is Map<*, *> -> value.entries.associate { it.key.toString() to normalize(it.value) }
        is Number -> value.toDouble()
        else -> value
    }

    /** Eylemin vektör dosyasındaki `expected` biçimi (Web SDK `HtmlBridgeAction` nesnesi). */
    private fun HsHtmlBridgeAction.toMap(): Map<String, Any?> = when (this) {
        HsHtmlBridgeAction.Ready -> mapOf("type" to "ready")
        HsHtmlBridgeAction.Close -> mapOf("type" to "close")
        is HsHtmlBridgeAction.Click -> buildMap {
            put("type", "click")
            put("actionId", actionId)
            url?.let { put("url", it) }
        }
        is HsHtmlBridgeAction.AddTag -> mapOf("type" to "add_tag", "key" to key, "value" to value)
        is HsHtmlBridgeAction.TrackEvent -> mapOf("type" to "track_event", "name" to name, "props" to props)
        is HsHtmlBridgeAction.Resize -> mapOf("type" to "resize", "height" to height)
    }

    @Test
    fun `kopru mesaj vektorleri`() {
        val nonce = vectors.getString("nonce")
        val cases = vectors.getJSONArray("messages")
        assertTrue(cases.length() >= 20)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val expected = if (c.isNull("expected")) null else normalize(c.getJSONObject("expected"))
            // Yerel kanal mesajı JSON metni olarak alır (`postMessage(JSON.stringify(m))`).
            val actual = HsHtmlBridge.parse(c.getJSONObject("data").toString(), nonce)
            assertEquals(name, expected, actual?.let { normalize(it.toMap()) })
        }
    }

    @Test
    fun `URL vektorleri`() {
        val cases = vectors.getJSONArray("urls")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val expected = if (c.isNull("expected")) null else c.getString("expected")
            assertEquals(c.getString("value"), expected, HsHtmlBridge.safeUrl(c.getString("value")))
        }
        assertNull(HsHtmlBridge.safeUrl(42))
        assertTrue(HsHtmlBridge.isWebUrl("HTTPS://shop.example.com"))
        assertTrue(!HsHtmlBridge.isWebUrl("myapp://cart"))
    }

    @Test
    fun `bozuk veya asiri buyuk mesaj reddedilir`() {
        assertNull(HsHtmlBridge.parse("bozuk", "nonce-12345"))
        assertNull(HsHtmlBridge.parse("[1,2]", "nonce-12345"))
        val big = JSONObject().put("hs", "herosignal-bridge").put("v", 1).put("nonce", "nonce-12345").put("type", "close")
            .put("pad", "x".repeat(HsHtmlBridge.MAX_MESSAGE_LENGTH))
        assertNull(HsHtmlBridge.parse(big.toString(), "nonce-12345"))
        // Sürüm metin olarak gelirse reddedilir; dizi özellik reddedilir.
        assertNull(HsHtmlBridge.parse("""{"hs":"herosignal-bridge","v":"1","nonce":"nonce-12345","type":"close"}""", "nonce-12345"))
        assertNull(HsHtmlBridge.parse("""{"hs":"herosignal-bridge","v":1,"nonce":"nonce-12345","type":"track_event","name":"x","props":[1]}""", "nonce-12345"))
        // Uzun metin özellik 255 karaktere kırpılır.
        val event = HsHtmlBridge.parse(
            """{"hs":"herosignal-bridge","v":1,"nonce":"nonce-12345","type":"track_event","name":"x","props":{"s":"${"a".repeat(300)}"}}""",
            "nonce-12345",
        ) as HsHtmlBridgeAction.TrackEvent
        assertEquals(255, (event.props["s"] as String).length)
    }

    @Test
    fun `belge CSP ile baslar ve yerel kopru betigini icerir`() {
        val nonce = HsHtmlBridge.newNonce()
        val doc = HsHtmlBridge.document("<p>Merhaba</p><script>evil()</script>", nonce, "tr-TR")
        assertTrue(doc.startsWith("<!doctype html><html lang=\"tr-TR\"><head><meta charset=\"utf-8\">"))
        val csp = doc.indexOf("<meta http-equiv=\"Content-Security-Policy\" content=\"${HsHtmlBridge.CSP}\">")
        assertTrue(csp > 0)
        assertTrue(csp < doc.indexOf("<p>Merhaba</p>"))
        assertTrue(doc.indexOf("<script>(function()") < doc.indexOf("<p>Merhaba</p>"))
        assertTrue(doc.contains("var N=\"$nonce\""))
        assertTrue(doc.contains("P=window.HeroSignalNative"))
        assertTrue(doc.contains("P.postMessage(JSON.stringify(m))"))
        assertTrue(!doc.contains("window.parent"))
        assertTrue(doc.contains("<meta name=\"referrer\" content=\"no-referrer\">"))
        assertTrue(doc.endsWith("</body></html>"))
        // Dil özniteliği temizlenir; içerik üst sınırda kesilir.
        assertTrue(HsHtmlBridge.document("x", nonce, "\"><script>").startsWith("<!doctype html><html lang=\"script\">"))
        val long = HsHtmlBridge.document("a".repeat(HsHtmlBridge.MAX_HTML_LENGTH + 10), nonce, null)
        assertTrue(long.contains("lang=\"en\""))
        assertEquals(HsHtmlBridge.MAX_HTML_LENGTH, long.substringAfter("<body>").substringBefore("</body>").length)
    }

    @Test
    fun `nonce guvenli karakterlerden olusur ve her gosterimde degisir`() {
        val a = HsHtmlBridge.newNonce()
        val b = HsHtmlBridge.newNonce()
        assertEquals(24, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        assertNotEquals(a, b)
        // Betiğe gömülen nonce temizlenir; kısa nonce reddedilir.
        assertTrue(HsHtmlBridge.script("abc\"def</script>ghi").contains("var N=\"abcdefscriptghi\""))
        assertTrue(runCatching { HsHtmlBridge.script("kısa") }.isFailure)
    }
}
