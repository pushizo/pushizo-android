package com.pushizo.sdk

import org.json.JSONArray
import org.json.JSONObject

/** `POST /events` ve kayıt gövdesi yardımcıları (Android bağımlılığı yok; JVM birim testinde sınanır). */
internal object HsEvents {
    /**
     * Kuyruktaki `event` işlemlerinden olay öğeleri. Her öğeye aboneliğin sahiplik kanıtı eklenir (ADR-0016 Karar 8):
     * `token_hash` (kayıtlı push token'ının ya da e-posta/SMS kanonik adresinin SHA-256 özeti) ve kimlikli kullanıcıda
     * `identity_hash`. Değer yoksa alan gönderilmez.
     */
    fun items(ops: List<JSONObject>, subscriptionId: String, tokenHash: String?, identityHash: String?): JSONArray {
        val events = JSONArray()
        for (e in ops) {
            val event = JSONObject()
                .put("name", e.getString("name"))
                .put("properties", e.optJSONObject("properties") ?: JSONObject())
                .put("occurred_at", e.getString("occurred_at"))
                .put("subscription_id", subscriptionId)
            if (e.has("value")) event.put("value", e.getDouble("value"))
            // İstemci olay kimliği: yeniden denemede sunucu aynı olayı ikinci kez kaydetmez (eski kuyruk öğelerinde yok).
            e.optString("event_id").takeIf { it.isNotEmpty() }?.let { event.put("event_id", it) }
            tokenHash?.let { event.put("token_hash", it) }
            identityHash?.let { event.put("identity_hash", it) }
            events.put(event)
        }
        return events
    }

    /**
     * Push aboneliği `device` alanı. `app_version` bilinmiyorsa alan hiç gönderilmez (JSON `null` sunucuda 400 ile
     * reddedilir). `push_capable`: bildirim izni verilmiş ve bildirimler açık mı (false ise abonelik `pending` kalır).
     */
    fun deviceInfo(
        model: String,
        osVersion: String?,
        appVersion: String?,
        sdkVersion: String,
        locale: String,
        timezone: String,
        pushCapable: Boolean,
    ): JSONObject = JSONObject()
        .put("model", model)
        .put("os", "Android")
        .apply { osVersion?.let { put("os_version", it) } }
        .apply { appVersion?.takeIf { it.isNotBlank() }?.let { put("app_version", it) } }
        .put("sdk_version", sdkVersion)
        .put("sdk_platform", "android")
        .put("locale", locale)
        .put("timezone", timezone)
        .put("push_capable", pushCapable)
}
