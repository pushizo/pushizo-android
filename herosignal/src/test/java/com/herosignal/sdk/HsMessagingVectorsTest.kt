package com.herosignal.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ortak vektörler (`sdk/testdata/messaging-vectors.json`): iOS testleri aynı dosyayı kullanır. */
class HsMessagingVectorsTest {
    private val vectors: JSONObject by lazy {
        val dir = System.getProperty("herosignal.testdata") ?: "../../testdata"
        JSONObject(File(dir, "messaging-vectors.json").readText(Charsets.UTF_8))
    }

    private fun JSONObject.list(name: String): List<JSONObject> {
        val arr = getJSONArray(name)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun cause(json: JSONObject): HsInAppCause = when (json.getString("kind")) {
        "session_start" -> HsInAppCause.SessionStart
        "event" -> HsInAppCause.Event(json.getString("name"))
        "screen" -> HsInAppCause.Screen(json.getString("name"))
        "trigger" -> {
            val keys = json.getJSONArray("keys")
            HsInAppCause.TriggerChange((0 until keys.length()).map { keys.getString(it) }.toSet())
        }
        else -> error("bilinmeyen neden")
    }

    private fun state(json: JSONObject): HsInAppState {
        val t = json.optJSONObject("triggers") ?: JSONObject()
        return HsInAppState(
            screen = json.optStringOrNull("screen"),
            triggers = t.keys().asSequence().associateWith { t.getString(it) },
        )
    }

    private fun message(id: String, priority: Int, triggers: JSONArray, extra: JSONObject = JSONObject()): HsInAppMessage {
        val json = JSONObject()
            .put("id", id)
            .put("priority", priority)
            .put("triggers", triggers)
            .put("display", JSONObject().put("layout", "modal").put("dismissible", true).put("blocks", JSONArray()))
        for (key in extra.keys()) json.put(key, extra.get(key))
        return HsInAppParser.parseMessage(json)
    }

    @Test
    fun `tetik vektorleri`() {
        val cases = vectors.list("triggers")
        assertTrue(cases.size >= 30)
        for (c in cases) {
            val triggers = HsInAppParser.parseTriggers(c.getJSONArray("triggers"))
            val actual = HsInAppRules.matches(triggers, cause(c.getJSONObject("cause")), state(c.getJSONObject("state")))
            assertEquals(c.getString("name"), c.getBoolean("expected"), actual)
        }
    }

    @Test
    fun `siklik vektorleri`() {
        for (c in vectors.list("frequency")) {
            val m = message("iam_1", 0, JSONArray(), c.getJSONObject("message"))
            val local = HsLocalImpressions(
                c.getInt("local_impressions"),
                c.optStringOrNull("local_last_impression_at")?.let(HsIsoTime::parse),
                c.optBoolean("local_interacted", false),
            )
            val now = HsIsoTime.parse(c.getString("now"))!!
            assertEquals(c.getString("name"), c.getBoolean("expected"), HsInAppRules.canDisplay(m, local, now))
        }
    }

    @Test
    fun `secim vektorleri`() {
        for (c in vectors.list("selection")) {
            val messages = c.list("messages").map {
                message(it.getString("id"), it.getInt("priority"), it.getJSONArray("triggers"), JSONObject().put("remaining_impressions", 1))
            }
            val selected = HsInAppRules.select(messages, cause(c.getJSONObject("cause")), state(c.getJSONObject("state")), emptyMap(), 0L)
            assertEquals(c.getString("name"), c.optStringOrNull("expected"), selected?.id)
        }
    }

    @Test
    fun `e-posta ve telefon normalizasyonu`() {
        for (c in vectors.list("email")) assertEquals(c.getString("input"), c.optStringOrNull("expected"), HsContacts.normalizeEmail(c.getString("input")))
        for (c in vectors.list("phone")) assertEquals(c.getString("input"), c.optStringOrNull("expected"), HsContacts.normalizePhone(c.getString("input")))
    }

    @Test
    fun `e-posta ve SMS kaydinda user nesnesi`() {
        for (c in vectors.list("registration_user")) {
            val user = HsContacts.registrationUser(c.optStringOrNull("external_id"), c.optStringOrNull("identity_hash"), c.optStringOrNull("subscription_id"))
            val expected = c.optJSONObject("expected")
            assertEquals(c.getString("name"), expected?.toMap(), user?.toMap())
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { get(it) }

    @Test
    fun `renk ve kontrast`() {
        for (c in vectors.list("colors")) {
            assertEquals(c.getString("input"), c.optStringOrNull("expected"), HsInAppStyle.parseColor(c.getString("input"))?.let(HsInAppStyle::format))
        }
        for (c in vectors.list("contrast")) {
            val bg = HsInAppStyle.parseColor(c.getString("background"))!!
            assertEquals(c.getString("background"), c.getString("expected"), HsInAppStyle.format(HsInAppStyle.contrastingText(bg)))
        }
    }
}
