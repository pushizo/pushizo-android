package com.pushizo.sdk

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Bildirim izni gözlemcileri (eski sağlayıcı `addPermissionObserver` eşdeğeri).
 *
 * Son bilinen etkin izin durumu tutulur; [update] yalnızca durum değiştiğinde gözlemcileri çağırır. İlk ölçüm
 * temel değerdir ve gözlemci tetiklemez. Bir gözlemcinin hatası diğerlerini engellemez ([onError]'a iletilir).
 * Android'e bağımlı değildir (JVM birim testi).
 */
internal class HsPermissionObservers(private val onError: (Throwable) -> Unit = {}) {
    private val lock = Any()
    private var last: Boolean? = null
    private val functions = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val observers = CopyOnWriteArraySet<Pushizo.Notifications.PermissionObserver>()

    /** Son bilinen izin durumu; henüz ölçülmediyse null. */
    val lastKnown: Boolean? get() = synchronized(lock) { last }

    val isEmpty: Boolean get() = functions.isEmpty() && observers.isEmpty()

    fun add(observer: (Boolean) -> Unit) { functions.add(observer) }
    fun remove(observer: (Boolean) -> Unit) { functions.remove(observer) }
    fun add(observer: Pushizo.Notifications.PermissionObserver) { observers.add(observer) }
    fun remove(observer: Pushizo.Notifications.PermissionObserver) { observers.remove(observer) }

    /** Güncel durumu kaydeder; önceki bilinen durumdan farklıysa gözlemcileri çağırır ve true döner. */
    fun update(granted: Boolean): Boolean {
        val changed = synchronized(lock) {
            val previous = last
            last = granted
            previous != null && previous != granted
        }
        if (changed) dispatch(granted)
        return changed
    }

    private fun dispatch(granted: Boolean) {
        functions.forEach { observer -> runCatching { observer(granted) }.onFailure(onError) }
        observers.forEach { observer -> runCatching { observer.onChange(granted) }.onFailure(onError) }
    }
}
