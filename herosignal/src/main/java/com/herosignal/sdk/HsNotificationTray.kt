package com.herosignal.sdk

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat

/**
 * Bildirim tepsisi yönetimi (`HeroSignal.Notifications.clearAll/removeNotification/removeGroupedNotifications`).
 * Yalnızca SDK'nın gösterdiği bildirimlere dokunur: SDK her bildirime [EXTRA_MARKER] ekler, uygulamanın
 * kendi bildirimleri işaret taşımadığından etkilenmez.
 */
internal object HsNotificationTray {
    const val EXTRA_MARKER = "com.herosignal.sdk.notification"

    /** Tepsideki bir bildirimin seçim için gereken özeti. */
    data class Entry(val tag: String?, val id: Int, val group: String?, val isSummary: Boolean, val fromSdk: Boolean)

    fun markerExtras(): Bundle = Bundle().apply { putBoolean(EXTRA_MARKER, true) }

    fun clearAll(context: Context) = cancel(context) { selectAll(it) }

    fun removeNotification(context: Context, id: Int) = cancel(context) { selectById(it, id) }

    fun removeGroupedNotifications(context: Context, group: String) = cancel(context) { selectGroup(it, group) }

    /** SDK'nın tüm bildirimleri (grup özetleri dahil). */
    fun selectAll(entries: List<Entry>): List<Entry> = entries.filter { it.fromSdk }

    /**
     * Kimliği eşleşen SDK bildirimleri; kaldırma sonrası grubunda başka bildirim kalmayan grup özetleri de
     * seçilir (boş özet tepside kalmasın).
     */
    fun selectById(entries: List<Entry>, id: Int): List<Entry> {
        val sdk = entries.filter { it.fromSdk }
        val removed = sdk.filter { !it.isSummary && it.id == id }
        if (removed.isEmpty()) return emptyList()
        val orphanSummaries = removed.mapNotNull { it.group }.distinct().flatMap { group ->
            val remaining = sdk.count { !it.isSummary && it.group == group && it !in removed }
            if (remaining == 0) sdk.filter { it.isSummary && it.group == group } else emptyList()
        }
        return removed + orphanSummaries
    }

    /** Grubu eşleşen SDK bildirimleri ve grup özeti. */
    fun selectGroup(entries: List<Entry>, group: String): List<Entry> = entries.filter { it.fromSdk && it.group == group }

    private fun cancel(context: Context, select: (List<Entry>) -> List<Entry>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { manager.activeNotifications.toList() }
            .onFailure { HsLog.w("etkin bildirimler okunamadı", it) }
            .getOrDefault(emptyList())
        val entries = active.map { sbn ->
            val notification = sbn.notification
            Entry(
                tag = sbn.tag,
                id = sbn.id,
                group = notification.group,
                isSummary = (notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0,
                fromSdk = notification.extras?.getBoolean(EXTRA_MARKER, false) == true,
            )
        }
        val compat = NotificationManagerCompat.from(context)
        select(entries).forEach { compat.cancel(it.tag, it.id) }
    }
}
