package com.pushizo.sdk

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
    /** Geçerli ön plan oturumu en az `seconds` saniye sürdüğünde (ADR-0019 §7); SDK süre dolunca yeniden değerlendirir. */
    data class SessionDuration(val seconds: Long) : HsInAppTrigger()
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
    /** Yerel bildirim izni istemini açar (Android 13+ `POST_NOTIFICATIONS`), ardından mesaj kapanır. */
    object PushPrompt : HsInAppAction()
    /** `Session.sendEvent(name, value)` ile outcome olayı gönderir (dönüşüm atribüsyonu), ardından mesaj kapanır. */
    data class Outcome(val name: String, val value: Double?) : HsInAppAction()
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

    /** Yatay kaydırmalı kartlar (v2 Faz 18); kart düğmeleri [HsInAppDisplay.buttons] içinde yer alır. */
    data class Carousel(val id: String, val cards: List<HsInAppCarouselCard>) : HsInAppBlock()
}

/** Carousel kartı; [button] geçersiz aksiyonlu ise null (kart yine gösterilir). */
data class HsInAppCarouselCard(
    val id: String,
    val title: String,
    val body: String?,
    val imageUrl: String?,
    val imageAlt: String?,
    val button: HsInAppBlock.Button?,
)

/** Mesaj içeriğinin biçimi: yerel bloklar veya yalıtılmış WebView'da çizilen HTML (ADR-0027). */
enum class HsInAppContentType(val wire: String) {
    BLOCKS("blocks"), HTML("html");

    companion object {
        /** Alan yoksa veya tanınmıyorsa bloklar (eski sunucu uyumu). */
        fun from(value: String?): HsInAppContentType = if (value == HTML.wire) HTML else BLOCKS
    }
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
    /** [HsInAppContentType.HTML] ise [blocks] boştur ve içerik [html] alanındadır. */
    val contentType: HsInAppContentType = HsInAppContentType.BLOCKS,
    /** Kullanıcının dilinde, kişiselleştirilmiş HTML (yalnızca HTML içerikte). */
    val html: String? = null,
    /** Banner/modal için ilk yükseklik (CSS px ≙ dp); köprünün `resize` bildirimiyle güncellenir. */
    val htmlHeight: Int? = null,
) {
    /** Düğme blokları ve carousel kartlarının düğmeleri (tıklama bildirimi bu listeden çözülür). */
    val buttons: List<HsInAppBlock.Button> get() = blocks.flatMap { block ->
        when (block) {
            is HsInAppBlock.Button -> listOf(block)
            is HsInAppBlock.Carousel -> block.cards.mapNotNull { it.button }
            else -> emptyList()
        }
    }

    val isHtml: Boolean get() = contentType == HsInAppContentType.HTML
}

/**
 * Sunucunun cihaz için döndürdüğü gösterilebilir in-app mesaj. Zamanlar epoch milisaniyedir.
 * [triggerGroups] VEYA ile bağlı gruplardır (grup içi VE); [triggers] ilk gruptur (geriye uyum).
 */
data class HsInAppMessage(
    val id: String,
    val priority: Int,
    val triggers: List<HsInAppTrigger>,
    val triggerGroups: List<List<HsInAppTrigger>>,
    /** Sunucunun seçtiği A/B görünüm varyantı; olay bildirimlerinde geri gönderilir. */
    val variantId: String?,
    val display: HsInAppDisplay,
    val remainingImpressions: Int,
    val minIntervalSeconds: Long,
    /** true: tıklama/kapatma sonrası bu cihazda (liste yenilenmesini beklemeden) bir daha gösterilmez. */
    val stopAfterInteraction: Boolean,
    val lastImpressionAt: Long?,
    val endAt: Long?,
    private val json: String,
    /** Sunucunun içeriği çözdüğü dil (liste yanıtındaki `language`); HTML belgesinin `lang` özniteliği. */
    val language: String? = null,
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
        val language = json.optStringOrNull("language")
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.let { item ->
                runCatching { parseMessage(item, language) }.onFailure { HsLog.w("in-app mesajı ayrıştırılamadı", it) }.getOrNull()
            }
        }
    }

    fun parseMessage(item: JSONObject): HsInAppMessage = parseMessage(item, null)

    /** [language]: liste yanıtındaki içerik dili (HTML belgesinin `lang` özniteliği). */
    fun parseMessage(item: JSONObject, language: String?): HsInAppMessage {
        val display = item.getJSONObject("display")
        val groups = parseTriggerGroups(item.optJSONArray("trigger_groups"), item.optJSONArray("triggers") ?: JSONArray())
        val contentType = HsInAppContentType.from(display.optStringOrNull("content_type"))
        val html = display.optStringOrNull("html")
        // HTML türünde içerik yoksa gösterilecek bir şey yoktur: mesaj atlanır.
        require(contentType != HsInAppContentType.HTML || !html.isNullOrBlank()) { "HTML in-app içeriği boş" }
        return HsInAppMessage(
            id = item.getString("id"),
            priority = item.optInt("priority", 0),
            triggers = groups.first(),
            triggerGroups = groups,
            variantId = item.optStringOrNull("variant_id"),
            display = HsInAppDisplay(
                layout = HsInAppLayout.from(display.optString("layout")),
                backgroundColor = display.optStringOrNull("background_color"),
                textColor = display.optStringOrNull("text_color"),
                dismissible = display.optBoolean("dismissible", true),
                autoDismissSeconds = if (display.isNull("auto_dismiss_seconds")) null else display.optInt("auto_dismiss_seconds").takeIf { it > 0 },
                blocks = if (contentType == HsInAppContentType.HTML) emptyList() else parseBlocks(display.optJSONArray("blocks") ?: JSONArray()),
                contentType = contentType,
                html = if (contentType == HsInAppContentType.HTML) html else null,
                htmlHeight = if (contentType != HsInAppContentType.HTML || display.isNull("html_height")) null else display.optInt("html_height").takeIf { it > 0 },
            ),
            remainingImpressions = item.optInt("remaining_impressions", 1),
            minIntervalSeconds = item.optLong("min_interval_seconds", 0),
            stopAfterInteraction = item.optBoolean("stop_after_interaction", false),
            lastImpressionAt = item.optStringOrNull("last_impression_at")?.let(HsIsoTime::parse),
            endAt = item.optStringOrNull("end_at")?.let(HsIsoTime::parse),
            json = item.toString(),
            language = language,
        )
    }

    /**
     * `trigger_groups` (VEYA grupları) yoksa veya boşsa `triggers` tek grup olur; boş grup (tetiksiz mesaj) korunur.
     * Sonuç her zaman en az bir grup içerir.
     */
    fun parseTriggerGroups(groups: JSONArray?, triggers: JSONArray): List<List<HsInAppTrigger>> {
        val parsed = if (groups == null) emptyList() else (0 until groups.length()).mapNotNull { i -> groups.optJSONArray(i)?.let(::parseTriggers) }
        return parsed.ifEmpty { listOf(parseTriggers(triggers)) }
    }

    fun parseTriggers(arr: JSONArray): List<HsInAppTrigger> = (0 until arr.length()).mapNotNull { i ->
        val t = arr.optJSONObject(i) ?: return@mapNotNull null
        when (val kind = t.optString("kind")) {
            "session_start" -> HsInAppTrigger.SessionStart
            "session_duration" -> HsInAppTrigger.SessionDuration(t.getLong("seconds"))
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
            "carousel" -> parseCarousel(b)
            // Tanınmayan blok türü atlanır (ileri uyumluluk).
            else -> null
        }
    }

    /** Kimliksiz veya kartsız carousel atlanır; kimliksiz kart atlanır, geçersiz kart düğmesi yalnızca düğmeyi düşürür. */
    private fun parseCarousel(b: JSONObject): HsInAppBlock.Carousel? {
        val id = b.optStringOrNull("id") ?: return null
        val arr = b.optJSONArray("cards") ?: return null
        val cards = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val cardId = c.optStringOrNull("id") ?: return@mapNotNull null
            val button = c.optJSONObject("button")?.let { btn ->
                val buttonId = btn.optStringOrNull("id") ?: return@let null
                val action = parseAction(btn.optJSONObject("action")) ?: return@let null
                HsInAppBlock.Button(buttonId, btn.optString("text"), action, backgroundColor = null, textColor = null)
            }
            HsInAppCarouselCard(
                id = cardId,
                title = if (c.isNull("title")) "" else c.optString("title"),
                body = c.optStringOrNull("body"),
                imageUrl = c.optStringOrNull("image_url"),
                imageAlt = c.optStringOrNull("image_alt"),
                button = button,
            )
        }
        return if (cards.isEmpty()) null else HsInAppBlock.Carousel(id, cards)
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
            "push_prompt" -> HsInAppAction.PushPrompt
            // Adsız outcome düğmesi geçersizdir (atlanır); değer sayı değilse null.
            "outcome" -> a.optStringOrNull("name")?.let { name ->
                HsInAppAction.Outcome(name, if (a.isNull("value")) null else (a.opt("value") as? Number)?.toDouble()?.takeIf { it.isFinite() })
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
