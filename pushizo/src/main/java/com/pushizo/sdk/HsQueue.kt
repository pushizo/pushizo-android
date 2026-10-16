package com.pushizo.sdk

import org.json.JSONObject

/**
 * İşlemin önkoşulu (ör. abonelik veya kullanıcı kimliği) henüz yok: işlem atılmaz ve kuyrukta kalır, sonraki
 * işlemler işlenmeye devam eder. Önkoşul oluşunca (kayıt, login) yapılan flush onu yeniden dener.
 */
internal class HsDeferredException(message: String) : Exception(message)

/** Kalıcı işlem kuyruğu deposu ([HsStorage]; birim testlerinde bellek içi sürüm kullanılır). */
internal interface HsQueueStore {
    var queue: List<JSONObject>
}

/**
 * Çevrimdışı işlem kuyruğu: JSON nesneleri (`type` alanı ile) kalıcı saklanır; sırayla işlenir.
 * Ardışık tag yamaları birleştirilir; 4xx hatasında işlem atılır, geçici hatada flush durur, önkoşulu eksik
 * ([HsDeferredException]) işlem kuyrukta bekletilir. [canFlush] false iken (gizlilik onayı yok) hiçbir işlem
 * işlenmez; kuyruk kalıcı olarak korunur.
 */
internal class HsQueue(
    private val storage: HsQueueStore,
    private val handler: (JSONObject) -> Unit,
    /** Kalıcı olarak reddedilip atılan işlem (hata raporu için). */
    private val onRejected: (JSONObject, HsApiException) -> Unit = { _, _ -> },
    private val canFlush: () -> Boolean = { true },
) {
    private val lock = Any()
    @Volatile private var flushing = false

    val size: Int get() = storage.queue.size

    fun enqueue(op: JSONObject) {
        synchronized(lock) {
            val queue = storage.queue.toMutableList()
            queue.add(op)
            storage.queue = mergeTail(queue).takeLast(MAX_QUEUE)
        }
    }

    /**
     * @return true: yeniden denenecek geçici hata yok (kuyruk boşaldı ya da yalnızca önkoşul bekleyen işlemler kaldı);
     * false: geçici hata veya onay yok, sonra tekrar denenecek.
     */
    fun flush(): Boolean {
        if (!canFlush()) return false
        if (flushing) return false
        flushing = true
        try {
            // Baştaki ertelenen (önkoşul bekleyen) işlem sayısı; bunlar atlanıp sonrakiler işlenir.
            var skip = 0
            while (true) {
                if (!canFlush()) return false
                val pending = synchronized(lock) { storage.queue }.drop(skip)
                val head = pending.firstOrNull() ?: return true
                // Ardışık olaylar tek istekte gönderilir (en fazla EVENT_BATCH_MAX).
                val events = leadingEvents(pending)
                val unit = if (events.size > 1) batchOf(events) else head
                val consumed = if (events.size > 1) events.size else 1
                try {
                    handler(unit)
                } catch (e: HsDeferredException) {
                    HsLog.d("işlem ertelendi (${e.message}): ${unit.optString("type")}")
                    skip += consumed
                    continue
                } catch (e: HsApiException) {
                    if (e.retryable) {
                        HsLog.d("flush durdu (geçici hata): ${unit.optString("type")}")
                        return false
                    }
                    HsLog.w("kuyruktaki işlem reddedildi, atılıyor: ${unit.optString("type")} ${e.code}")
                    runCatching { onRejected(unit, e) }
                }
                synchronized(lock) {
                    val queue = storage.queue
                    storage.queue = queue.take(skip) + queue.drop(skip + consumed)
                }
                // Başarılı işlem önkoşulu sağlamış olabilir (ör. contact_add kullanıcıyı oluşturur): ertelenenler yeniden denenir.
                skip = 0
            }
        } finally {
            flushing = false
        }
    }

    fun clear() { synchronized(lock) { storage.queue = emptyList() } }

    companion object {
        const val MAX_QUEUE = 500
        /** Tek istekte en fazla olay (SDK API sınırı). */
        const val EVENT_BATCH_MAX = 100

        /** Kuyruğun başındaki ardışık `event` işlemleri. */
        fun leadingEvents(queue: List<JSONObject>): List<JSONObject> {
            val run = mutableListOf<JSONObject>()
            for (op in queue) {
                if (op.optString("type") != "event" || run.size >= EVENT_BATCH_MAX) break
                run.add(op)
            }
            return run
        }

        fun batchOf(events: List<JSONObject>): JSONObject {
            val arr = org.json.JSONArray()
            events.forEach { arr.put(it) }
            return JSONObject().put("type", "event_batch").put("events", arr).put("at", events.first().optLong("at"))
        }

        fun mergeTail(queue: MutableList<JSONObject>): List<JSONObject> {
            if (queue.size < 2) return queue
            val last = queue[queue.size - 1]
            val prev = queue[queue.size - 2]
            if (last.optString("type") == "tags" && prev.optString("type") == "tags") {
                val merged = JSONObject(prev.getJSONObject("tags").toString())
                val lastTags = last.getJSONObject("tags")
                for (key in lastTags.keys()) merged.put(key, lastTags.get(key))
                val op = JSONObject().put("type", "tags").put("tags", merged).put("at", last.optLong("at"))
                return queue.dropLast(2) + op
            }
            return queue
        }
    }
}
