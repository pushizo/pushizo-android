package com.pushizo.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Gözlemci kümesi: bir gözlemcinin hatası diğerlerini engellemez ([onError]'a iletilir). Android'e bağımlı değildir
 * (JVM birim testi); çağrının hangi iş parçacığında yapılacağına çağıran karar verir.
 */
internal class HsObserverSet<T : Any>(private val onError: (Throwable) -> Unit = {}) {
    private val observers = CopyOnWriteArraySet<T>()

    val isEmpty: Boolean get() = observers.isEmpty()

    fun add(observer: T) { observers.add(observer) }
    fun remove(observer: T) { observers.remove(observer) }

    fun dispatch(block: (T) -> Unit) {
        observers.forEach { observer -> runCatching { block(observer) }.onFailure(onError) }
    }
}

/** Özellik bayrağı gözlemcisi: değerler sunucudan başarıyla yenilendiğinde ana iş parçacığında çağrılır. */
fun interface HsFeatureFlagObserver {
    fun onChange()
}

/**
 * Bildirim kutusu değişim olayı (Web SDK `InboxChangeEvent` ile aynı). [type]: [LISTED], [READ], [READ_ALL],
 * [ARCHIVED] veya [COUNT]; [itemId] yalnızca tek öğe olaylarında doludur; [unreadCount] güncel okunmamış sayısıdır.
 */
data class HsInboxChangeEvent(val type: String, val itemId: String?, val unreadCount: Int) {
    /** Köprüler (Flutter, React Native…) için düz harita. */
    fun toMap(): Map<String, Any?> = mapOf("type" to type, "itemId" to itemId, "unreadCount" to unreadCount)

    companion object {
        const val LISTED = "listed"
        const val READ = "read"
        const val READ_ALL = "read_all"
        const val ARCHIVED = "archived"
        const val COUNT = "count"
    }
}

/** Bildirim kutusu gözlemcisi; ana iş parçacığında çağrılır. */
fun interface HsInboxObserver {
    fun onChange(event: HsInboxChangeEvent)
}

/**
 * Bildirim kutusunun yerel durumu (Web SDK `InboxClient` ile aynı kurallar): son bilinen okunmamış sayısı ve son
 * listelenen öğelerin okunma durumu tutulur. Öğe olayları (`read`, `read_all`, `archived`) her zaman, `listed` ve
 * `count` yalnızca okunmamış sayısı değiştiyse bildirilir. Okunmamış sayısı yerelde hesaplanamıyorsa [fetchCount]
 * ile sunucudan alınır. Android'e bağımlı değildir (JVM birim testi).
 */
internal class HsInboxTracker(private val emit: (HsInboxChangeEvent) -> Unit) {
    private val lock = Any()
    private var unread: Int? = null
    private val known = HashMap<String, Boolean>()

    fun listed(page: HsInboxPage, firstPage: Boolean) {
        synchronized(lock) {
            if (firstPage) known.clear()
            page.items.forEach { known[it.id] = it.read }
        }
        update(HsInboxChangeEvent(HsInboxChangeEvent.LISTED, null, page.unreadCount))
    }

    fun read(itemId: String, fetchCount: () -> Int) {
        val local = synchronized(lock) {
            val wasUnread = known[itemId] == false
            known[itemId] = true
            unread?.takeIf { wasUnread }?.let { maxOf(0, it - 1) }
        }
        update(HsInboxChangeEvent(HsInboxChangeEvent.READ, itemId, local ?: fetchCount()))
    }

    fun readAll(unreadCount: Int) {
        synchronized(lock) { known.keys.toList().forEach { known[it] = true } }
        update(HsInboxChangeEvent(HsInboxChangeEvent.READ_ALL, null, unreadCount))
    }

    fun archived(itemId: String, fetchCount: () -> Int) {
        val local = synchronized(lock) {
            val state = known.remove(itemId)
            val current = unread
            if (state != null && current != null) maxOf(0, current - if (state) 0 else 1) else null
        }
        update(HsInboxChangeEvent(HsInboxChangeEvent.ARCHIVED, itemId, local ?: fetchCount()))
    }

    fun counted(unreadCount: Int) = update(HsInboxChangeEvent(HsInboxChangeEvent.COUNT, null, unreadCount))

    /** Kullanıcı değişince (login/logout) önbellek temizlenir. */
    fun reset() = synchronized(lock) {
        known.clear()
        unread = null
    }

    private fun update(event: HsInboxChangeEvent) {
        val changed = synchronized(lock) {
            val different = unread != event.unreadCount
            unread = event.unreadCount
            different
        }
        if (!changed && (event.type == HsInboxChangeEvent.LISTED || event.type == HsInboxChangeEvent.COUNT)) return
        emit(event)
    }
}

/** `User.setProperty` değerini kuyruk JSON'una çevirir (null → JSON null; harita/liste iç içe çevrilir). */
internal object HsUserProperties {
    fun op(key: String, value: Any?, at: Long): JSONObject {
        require(key.isNotBlank()) { "Özellik anahtarı boş olamaz." }
        return JSONObject().put("type", "properties").put("properties", JSONObject().put(key, wrap(value))).put("at", at)
    }

    fun wrap(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Boolean, is Number -> value
        is Map<*, *> -> JSONObject().also { json -> value.forEach { (k, v) -> json.put(k.toString(), wrap(v)) } }
        is Iterable<*> -> JSONArray().also { arr -> value.forEach { arr.put(wrap(it)) } }
        is Array<*> -> JSONArray().also { arr -> value.forEach { arr.put(wrap(it)) } }
        else -> value.toString()
    }
}

/**
 * Rozet sayısı kuralları. Android'de başlatıcı rozeti için standart bir "sayıyı ayarla" API'si yoktur: rozet, tepsideki
 * bildirimlerden üretilir ve sayı gösteren başlatıcılar bildirimin `number` alanını toplar (Android 8+). SDK bu yüzden
 * sayıyı saklar ve gösterdiği sonraki bildirimlere yazar; üreticiye özel yayınlar (ShortcutBadger benzeri) kullanılmaz.
 */
internal object HsBadgeRules {
    const val MIN_SDK = 26

    /** Negatif değer 0 sayılır. */
    fun normalize(count: Int): Int = maxOf(0, count)

    /** Rozet bu cihazda gösterilebilir mi: Android 8+, bildirimler açık ve kanal rozete izin veriyor. */
    fun supported(sdkInt: Int, notificationsEnabled: Boolean, channelShowsBadge: Boolean): Boolean =
        sdkInt >= MIN_SDK && notificationsEnabled && channelShowsBadge

    /** Bildirime yazılacak sayı; 0 ise alan yazılmaz (başlatıcı varsayılanı: bildirim başına 1). */
    fun numberFor(count: Int): Int? = normalize(count).takeIf { it > 0 }
}
