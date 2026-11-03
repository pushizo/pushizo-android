package com.pushizo.sdk

import org.json.JSONObject
import java.security.SecureRandom

/** Özellik bayrağı kuralları (ADR-0066; saf mantık, Android bağımlılığı yok). */
internal object HsFeatureFlagRules {
    /** Sunucu yanıtından önbelleğe yazılacak `{ anahtar: { enabled, value } }`; yanıt bozuksa null (son değerler korunur). */
    fun parse(response: JSONObject): JSONObject? {
        val flags = response.optJSONObject("flags") ?: return null
        val out = JSONObject()
        flags.keys().forEach { key ->
            val item = flags.optJSONObject(key) ?: return@forEach
            val enabled = item.opt("enabled") as? Boolean ?: return@forEach
            out.put(key, JSONObject().put("enabled", enabled).put("value", item.opt("value") ?: JSONObject.NULL))
        }
        return out
    }

    fun isEnabled(cache: JSONObject?, key: String, default: Boolean): Boolean =
        cache?.optJSONObject(key)?.let { it.opt("enabled") as? Boolean } ?: default

    /** Ham değer: metin, sayı, mantıksal, `JSONObject` veya `JSONArray`. Bilinmeyen anahtarda null. */
    fun value(cache: JSONObject?, key: String): Any? =
        cache?.optJSONObject(key)?.opt("value")?.takeUnless { it == JSONObject.NULL }

    fun newSubject(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "anon_" + bytes.joinToString("") { "%02x".format(it) }
    }

    /** Abonelik varsa sahiplik kanıtı çağıran tarafından eklenir; yoksa kalıcı rastgele kimlik gönderilir. */
    fun body(subscriptionId: String?, subject: String): JSONObject =
        if (subscriptionId != null) JSONObject().put("subscription_id", subscriptionId) else JSONObject().put("subject", subject)
}
