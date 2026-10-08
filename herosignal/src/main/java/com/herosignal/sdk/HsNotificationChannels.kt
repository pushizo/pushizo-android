package com.herosignal.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dashboard'da tanımlanan bildirim kategorileri (`/config` → `android_channels`, ADR-0019 §12): başlatmada ve config
 * yenilendiğinde Android 8+ `NotificationChannel` olarak oluşturulur/güncellenir. Android, var olan kanalda yalnızca
 * ad, açıklama ve grubu günceller; önem, ses ve titreşim ilk oluşturmada belirlenir (kullanıcı ayarı korunur).
 * Config'de artık bulunmayan kanallar silinmez (kullanıcının sistem ayarları ve bekleyen bildirimler korunur).
 */
internal object HsNotificationChannels {
    /** Config'deki `importance` → `NotificationManager.IMPORTANCE_*`; bilinmeyen değer varsayılan. */
    fun importance(wire: String?): Int = when (wire) {
        "low" -> NotificationManager.IMPORTANCE_LOW
        "high" -> NotificationManager.IMPORTANCE_HIGH
        "urgent" -> NotificationManager.IMPORTANCE_MAX
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }

    /** Config dizisini kanallara uygular; bozuk öğe (kimlik/ad yok) atlanır. */
    fun sync(context: Context, channels: JSONArray?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channels == null) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            val id = c.optStringOrNull("id") ?: continue
            val name = c.optStringOrNull("name") ?: continue
            runCatching { apply(context, manager, id, name, c) }
                .onFailure { HsLog.w("bildirim kanalı oluşturulamadı: $id", it) }
        }
    }

    private fun apply(context: Context, manager: NotificationManager, id: String, name: String, c: JSONObject) {
        val channel = NotificationChannel(id, name, importance(c.optStringOrNull("importance"))).apply {
            description = c.optStringOrNull("description")
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(c.optBoolean("vibration", true))
            // "default"/boş → sistem bildirim sesi; aksi halde uygulamanın `res/raw/<ad>` kaynağı (yoksa sistem sesi).
            HeroSignalMessagingService.soundUri(context, c.optStringOrNull("sound"))?.let {
                setSound(it, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
        }
        c.optStringOrNull("group")?.let { groupId ->
            // Grup yoksa kimliği ad olan grup açılır (dashboard grup adı taşımaz).
            if (manager.getNotificationChannelGroup(groupId) == null) manager.createNotificationChannelGroup(NotificationChannelGroup(groupId, groupId))
            channel.group = groupId
        }
        manager.createNotificationChannel(channel)
    }
}
