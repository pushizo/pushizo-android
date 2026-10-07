package com.herosignal.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Bir bildirim aksiyon düğmesi (`buttons`). */
data class HsAction(val id: String, val title: String, val url: String?, val icon: String? = null)

/** FCM `data` alanından ayrıştırılmış HeroSignal bildirimi. */
data class HsNotification(
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    val url: String?,
    val deliveryId: String?,
    val messageId: String?,
    val collapseKey: String?,
    val channelId: String?,
    val actions: List<HsAction>,
    val data: Map<String, String>,
    /** Alt başlık (`hs_subtitle`; bildirimde alt metin olarak gösterilir). */
    val subtitle: String? = null,
    /** Ses adı (`hs_sound`): "default" veya `res/raw` altındaki dosya adı. */
    val sound: String? = null,
    /** Android bildirim grubu anahtarı (`hs_group`). */
    val groupKey: String? = null,
    /** Sessiz (content_available) mesaj: gösterilmez, yalnızca `received` bildirilir. */
    val silent: Boolean = false,
) {
    /** Tıklanan düğmenin URL'si; yoksa bildirim URL'si. */
    fun urlFor(actionId: String?): String? = actions.firstOrNull { it.id == actionId }?.url ?: url

    companion object {
        /**
         * FCM mesajını ayrıştırır. Sunucu artık yalnızca `data` gönderir: `hs` (JSON: dlv, msg, url, buttons),
         * `hs_title`, `hs_body`, `hs_subtitle`, `hs_image`, `hs_url`, `hs_actions`, `hs_channel`, `hs_sound`,
         * `hs_collapse`, `hs_group`, `hs_silent` + müşteri verileri. Eski payload'lardaki `notification`
         * bloğu değerleri (title/body/image/tag/channel) parametre olarak verilirse önceliklidir.
         */
        fun fromFcm(data: Map<String, String>, title: String?, body: String?, imageUrl: String?, collapseKey: String?, channelId: String?): HsNotification {
            val hs = data["hs"]?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            val actions = data["hs_actions"]?.let { parseActions(it) } ?: hs?.optJSONArray("buttons")?.let { parseActions(it) } ?: emptyList()
            val custom = data.filterKeys { it != "hs" && !it.startsWith("hs_") }
            return HsNotification(
                title = title ?: data["hs_title"].nonBlank(),
                body = body ?: data["hs_body"].nonBlank(),
                imageUrl = imageUrl ?: data["hs_image"].nonBlank() ?: hs?.optStringOrNull("image"),
                url = data["hs_url"].nonBlank() ?: hs?.optStringOrNull("url"),
                deliveryId = hs?.optStringOrNull("dlv") ?: hs?.optStringOrNull("delivery_id") ?: data["hs_delivery_id"].nonBlank(),
                messageId = hs?.optStringOrNull("msg") ?: hs?.optStringOrNull("message_id") ?: data["hs_message_id"].nonBlank(),
                collapseKey = collapseKey ?: data["hs_collapse"].nonBlank(),
                channelId = channelId ?: data["hs_channel"].nonBlank(),
                actions = actions,
                data = custom,
                subtitle = data["hs_subtitle"].nonBlank(),
                sound = data["hs_sound"].nonBlank(),
                groupKey = data["hs_group"].nonBlank(),
                silent = data["hs_silent"] == "1",
            )
        }

        /** `hs` alanı veya `hs_*` anahtarı içeren mesajlar HeroSignal'e aittir. */
        fun isHeroSignal(data: Map<String, String>): Boolean = data.keys.any { it == "hs" || it.startsWith("hs_") }

        private fun parseActions(raw: String): List<HsAction> =
            runCatching { parseActions(JSONArray(raw)) }.getOrDefault(emptyList())

        private fun parseActions(arr: JSONArray): List<HsAction> = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optStringOrNull("id") ?: return@mapNotNull null
            HsAction(id, o.optStringOrNull("title") ?: id, o.optStringOrNull("url"), o.optStringOrNull("icon"))
        }

        private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
