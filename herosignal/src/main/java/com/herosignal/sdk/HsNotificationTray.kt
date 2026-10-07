package com.herosignal.sdk

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Bildirim tepsisindeki HeroSignal bildirimlerini yönetir (eski sağlayıcı `clearAll`, `removeNotification`,
 * `removeGroupedNotifications` eşdeğeri). SDK'nın gösterdiği her bildirim ve grup özeti [EXTRA_MARK] ekstrasıyla
 * işaretlenir; uygulamanın kendi (HeroSignal dışı) bildirimlerine dokunulmaz.
 */
internal object HsNotificationTray {
    /** Bildirim ekstrası: bildirimi HeroSignal'in gösterdiğini belirtir. */
    const val EXTRA_MARK = "com.herosignal.sdk.NOTIFICATION"

    /** Grup özet bildirimlerinin etiketi (kimlik: grup anahtarının hash'i). */
    const val GROUP_SUMMARY_TAG = "hs_group_summary"

    /** Bildirime HeroSignal işaretini ekler. */
    fun mark(builder: NotificationCompat.Builder): NotificationCompat.Builder =
        builder.addExtras(Bundle().apply { putBoolean(EXTRA_MARK, true) })

    private fun manager(context: Context): NotificationManager? = context.getSystemService(NotificationManager::class.java)

    /** Tepsideki HeroSignal bildirimleri (özetler dahil). Android 6.0+ (minSdk 23) aktif bildirim listesi verir. */
    fun active(context: Context): List<StatusBarNotification> =
        runCatching { manager(context)?.activeNotifications?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.notification.extras?.getBoolean(EXTRA_MARK, false) == true || it.tag == GROUP_SUMMARY_TAG }

    /** Tüm HeroSignal bildirimlerini kaldırır; tepsi boşalınca başlatıcı rozeti de sıfırlanır. */
    fun clearAll(context: Context) {
        val compat = NotificationManagerCompat.from(context)
        active(context).forEach { compat.cancel(it.tag, it.id) }
    }

    /** Kimliği verilen HeroSignal bildirimini kaldırır; grubunda başka bildirim kalmadıysa grup özeti de kaldırılır. */
    fun remove(context: Context, androidNotificationId: Int) {
        val compat = NotificationManagerCompat.from(context)
        val targets = active(context).filter { it.id == androidNotificationId && it.tag != GROUP_SUMMARY_TAG }
        targets.forEach { compat.cancel(it.tag, it.id) }
        targets.mapNotNull { it.notification.group }.distinct().forEach { group ->
            if (childCount(context, group) == 0) compat.cancel(GROUP_SUMMARY_TAG, group.hashCode())
        }
    }

    /** Aynı grup anahtarına (`hs_group` / `android_group`) sahip tüm bildirimleri ve grup özetini kaldırır. */
    fun removeGroup(context: Context, group: String) {
        val compat = NotificationManagerCompat.from(context)
        active(context).filter { it.notification.group == group }.forEach { compat.cancel(it.tag, it.id) }
        compat.cancel(GROUP_SUMMARY_TAG, group.hashCode())
    }

    /** Gruptaki (özet hariç) görünen HeroSignal bildirimi sayısı. */
    fun childCount(context: Context, group: String): Int =
        active(context).count { it.notification.group == group && it.tag != GROUP_SUMMARY_TAG }

    /** Android 7 öncesinde gruplama yoktur; özet bildirimi gösterilmez. */
    val supportsGroups: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
}
