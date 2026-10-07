package com.herosignal.sdk

import org.json.JSONArray
import org.json.JSONObject

/** In-app mesaj tetik koşulu (`docs/messaging.md` §7). */
sealed class HsInAppTrigger {
    /** Uygulama oturumu başladığında. */
    object SessionStart : HsInAppTrigger()
    /** `Session.sendEvent(name)` çağrıldığında. */
    data class Event(val name: String) : HsInAppTrigger()
    /** `InAppMessages.setScreen(name)` ile bildirilen ekran. */
    data class Screen(val name: String) : HsInAppTrigger()
    /** `InAppMessages.addTrigger(key, value)` değerleri; `op`: eq | neq | exists | gt | lt. `value` String veya Number. */
    data class Condition(val key: String, val op: String, val value: Any?) : HsInAppTrigger()
    /** Bu SDK sürümünün tanımadığı tetik türü: mesaj gösterilmez. */
    data class Unsupported(val kind: String) : HsInAppTrigger()
}

/** Düğme aksiyonu. */
sealed class HsInAppAction {
    object Dismiss : HsInAppAction()
    /** http/https adresi; tarayıcıda açılır. */
    data class Url(val url: String) : HsInAppAction()
    /** Uygulama şeması (ör. `myapp://cart`); uygulama içinde açılır. */
    data class Deeplink(val url: String) : HsInAppAction()
    /** Kullanıcıya etiket yazar (`User.addTags`). */
    data class Tags(val tags: Map<String, String>) : HsInAppAction()
}

/** Mesaj içerik bloğu; metinler sunucuda kullanıcının dilinde çözülmüş düz dizedir. */
sealed class HsInAppBlock {
    data class Image(val url: String, val alt: String?) : HsInAppBlock()
    data class Title(val text: String) : HsInAppBlock()
    data class Body(val text: String) : HsInAppBlock()
    data class Button(
        val id: String,
        val text: String,
        val action: HsInAppAction,
        val backgroundColor: String?,
        val textColor: String?,
    ) : HsInAppBlock()
}

enum class HsInAppLayout(val wire: String) {
    MODAL("modal"), BANNER_TOP("banner_top"), BANNER_BOTTOM("banner_bottom"), FULLSCREEN("fullscreen");

    companion object {
        /** Bilinmeyen düzen modal olarak çizilir. */
        fun from(value: String?): HsInAppLayout = entries.firstOrNull { it.wire == value } ?: MODAL
    }
}

data class HsInAppDisplay(
    val layout: HsInAppLayout,
    val backgroundColor: String?,
    val textColor: String?,
    val dismissible: Boolean,
    val autoDismissSeconds: Int?,
    val blocks: List<HsInAppBlock>,
) {
    val buttons: List<HsInAppBlock.Button> get() = blocks.filterIsInstance<HsInAppBlock.Button>()
}

/** Sunucunun cihaz için döndürdüğü gösterilebilir in-app mesaj. Zamanlar epoch milisaniyedir. */
data class HsInAppMessage(
    val id: String,
    val priority: Int,
    val triggers: List<HsInAppTrigger>,
    val display: HsInAppDisplay,
    val remainingImpressions: Int,
    val minIntervalSeconds: Long,
    /** true: tıklama/kapatma sonrası bu cihazda (liste yenilenmesini beklemeden) bir daha gösterilmez. */
    val stopAfterInteraction: Boolean,
    val lastImpressionAt: Long?,
    val endAt: Long?,
    private val json: String,
) {
    /** Sunucudaki ham JSON (özel çizim katmanları, ör. Flutter köprüsü için). */
    fun toJson(): String = json
}

/** In-app mesaj tıklaması. `actionId` düğme kimliği; `url` url/deeplink aksiyonunun adresi. */
data class HsInAppClickEvent(val messageId: String, val actionId: String, val url: String?)

/** Yaşam döngüsü olayı (willDisplay/didDisplay/willDismiss/didDismiss). */
data class HsInAppLifecycleEvent(val message: HsInAppMessage)

/** In-app yaşam döngüsü dinleyicisi; varsayılan gövdeler boştur. */
interface HsInAppLifecycleListener {
    fun onWillDisplay(event: HsInAppLifecycleEvent) {}
    fun onDidDisplay(event: HsInAppLifecycleEvent) {}
    fun onWillDismiss(event: HsInAppLifecycleEvent) {}
    fun onDidDismiss(event: HsInAppLifecycleEvent) {}
}

/** `GET /in-app` yanıt ayrıştırıcı; bozuk mesaj atlanır, diğerleri kullanılır. */
internal object HsInAppParser {
    fun parseList(json: JSONObject): List<HsInAppMessage> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.let { item ->
                runCatching { parseMessage(item) }.onFailure { HsLog.w("in-app mesajı ayrıştırılamadı", it) }.getOrNull()
            }
        }
    }

    fun parseMessage(item: JSONObject): HsInAppMessage {
        val display = item.getJSONObject("display")
        return HsInAppMessage(
            id = item.getString("id"),
            priority = item.optInt("priority", 0),
            triggers = parseTriggers(item.optJSONArray("triggers") ?: JSONArray()),
            display = HsInAppDisplay(
                layout = HsInAppLayout.from(display.optString("layout")),
                backgroundColor = display.optStringOrNull("background_color"),
                textColor = display.optStringOrNull("text_color"),
                dismissible = display.optBoolean("dismissible", true),
                autoDismissSeconds = if (display.isNull("auto_dismiss_seconds")) null else display.optInt("auto_dismiss_seconds").takeIf { it > 0 },
                blocks = parseBlocks(display.optJSONArray("blocks") ?: JSONArray()),
            ),
            remainingImpressions = item.optInt("remaining_impressions", 1),
            minIntervalSeconds = item.optLong("min_interval_seconds", 0),
            stopAfterInteraction = item.optBoolean("stop_after_interaction", false),
            lastImpressionAt = item.optStringOrNull("last_impression_at")?.let(HsIsoTime::parse),
            endAt = item.optStringOrNull("end_at")?.let(HsIsoTime::parse),
            json = item.toString(),
        )
    }

    fun parseTriggers(arr: JSONArray): List<HsInAppTrigger> = (0 until arr.length()).mapNotNull { i ->
        val t = arr.optJSONObject(i) ?: return@mapNotNull null
        when (val kind = t.optString("kind")) {
            "session_start" -> HsInAppTrigger.SessionStart
            "event" -> HsInAppTrigger.Event(t.getString("name"))
            "screen" -> HsInAppTrigger.Screen(t.getString("name"))
            "trigger" -> HsInAppTrigger.Condition(
                key = t.getString("key"),
                op = t.getString("op"),
                value = if (t.isNull("value")) null else t.get("value").takeIf { it is String || it is Number },
            )
            else -> HsInAppTrigger.Unsupported(kind)
        }
    }

    private fun parseBlocks(arr: JSONArray): List<HsInAppBlock> = (0 until arr.length()).mapNotNull { i ->
        val b = arr.optJSONObject(i) ?: return@mapNotNull null
        when (b.optString("type")) {
            "image" -> b.optStringOrNull("url")?.let { HsInAppBlock.Image(it, b.optStringOrNull("alt")) }
            "title" -> HsInAppBlock.Title(b.optString("text"))
            "body" -> HsInAppBlock.Body(b.optString("text"))
            "button" -> {
                val action = parseAction(b.optJSONObject("action")) ?: return@mapNotNull null
                HsInAppBlock.Button(
                    id = b.getString("id"),
                    text = b.optString("text"),
                    action = action,
                    backgroundColor = b.optStringOrNull("background_color"),
                    textColor = b.optStringOrNull("text_color"),
                )
            }
            // Tanınmayan blok türü atlanır (ileri uyumluluk).
            else -> null
        }
    }

    private fun parseAction(a: JSONObject?): HsInAppAction? {
        if (a == null) return HsInAppAction.Dismiss
        return when (a.optString("type")) {
            "dismiss" -> HsInAppAction.Dismiss
            "url" -> a.optStringOrNull("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { HsInAppAction.Url(it) }
            "deeplink" -> a.optStringOrNull("url")?.let { HsInAppAction.Deeplink(it) }
            "tags" -> {
                val tags = a.optJSONObject("tags") ?: JSONObject()
                HsInAppAction.Tags(tags.keys().asSequence().associateWith { tags.optString(it) })
            }
            else -> HsInAppAction.Dismiss
        }
    }
}

/** Null/eksik alanı null döndüren `optString` (org.json `optString` "null" metni döndürebilir). */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/** ISO-8601 (UTC `Z` veya `±hh:mm`) zaman ayrıştırıcı; java.time gerektirmez (minSdk 23). */
internal object HsIsoTime {
    private val PATTERN = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})$")

    fun parse(value: String): Long? {
        val m = PATTERN.matchEntire(value.trim()) ?: return null
        val g = m.groupValues
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        }
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.substring(0, 3)?.toLong() ?: 0L
        val zone = g[8]
        val offsetMinutes = if (zone == "Z") 0 else {
            val digits = zone.substring(1).replace(":", "")
            val minutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
            if (zone[0] == '-') -minutes else minutes
        }
        return cal.timeInMillis + millis - offsetMinutes * 60_000L
    }
}
