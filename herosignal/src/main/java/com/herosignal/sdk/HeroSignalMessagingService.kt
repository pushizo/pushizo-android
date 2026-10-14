package com.herosignal.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM mesajlarını alır: `received` bildirir, ön plan dinleyicilerine sorar ve bildirimi oluşturur
 * (kanal, büyük resim, küçük/büyük ikon, vurgu rengi, kilit ekranı görünürlüğü, aksiyon düğmeleri, collapse/tag,
 * grup, ses, deep link). Sunucu yalnızca `data`
 * mesajı gönderir; eski payload'lardaki `notification` bloğu da desteklenir.
 */
class HeroSignalMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        HsLog.d("FCM token yenilendi")
        // Uygulama kodu çalışmadan başlayan süreçte SDK son initialize yapılandırmasıyla başlatılır.
        if (HeroSignal.ensureInitialized(applicationContext)) HeroSignal.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!HsNotification.isHeroSignal(message.data) && message.notification == null) return // HeroSignal dışı veri mesajı
        val n = HsNotification.fromFcm(
            data = message.data,
            title = message.notification?.title,
            body = message.notification?.body,
            imageUrl = message.notification?.imageUrl?.toString(),
            // FCM collapseKey çoğu mesajda paket adıdır; yalnızca açık tag/collapse kimliği kullanılır.
            collapseKey = message.notification?.tag,
            channelId = message.notification?.channelId,
        )
        if (n.deliveryId == null && n.title == null && n.body == null) return // HeroSignal dışı mesaj
        // Uygulama kapalıyken (ör. Flutter motoru çalışmıyorken) gelen mesajın `received` bildirimi kaybolmasın diye
        // SDK kayıtlı yapılandırmayla başlatılır.
        val initialized = HeroSignal.ensureInitialized(applicationContext)
        n.deliveryId?.let { if (initialized) HeroSignal.reportReceived(it) }
        if (n.silent) return // sessiz mesaj: gösterilmez
        if (n.title == null && n.body == null) return // gösterilecek içerik yok

        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (inForeground && HeroSignal.isInitialized) {
            val event = HeroSignal.dispatchForeground(n)
            // Engellenmediyse ve dinleyici display() ile zaten göstermediyse şimdi gösterilir.
            if (!event.prevented && event.markDisplayed()) show(applicationContext, n)
            return
        }
        show(applicationContext, n)
    }

    companion object {
        fun show(context: Context, n: HsNotification) {
            val channelId = n.channelId ?: HeroSignal.options.defaultChannelId
            val soundUri = soundUri(context, n.sound)
            ensureChannel(context, channelId, soundUri)
            // collapse kimliği aynı olan bildirimler birbirinin yerine geçer (tag + id).
            val tag = n.collapseKey
            val notificationId = n.androidNotificationId
            val iconRes = smallIcon(context, n.smallIcon)

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(iconRes)
                .setContentTitle(n.title)
                .setContentText(n.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(NotificationOpenActivity.pendingIntent(context, n, null, tag, notificationId, notificationId))
            HsNotificationTray.mark(builder)
            n.subtitle?.let { builder.setSubText(it) }
            n.deliveryId?.let { builder.setDeleteIntent(NotificationDismissedReceiver.pendingIntent(context, it, notificationId)) }
            n.groupKey?.let { builder.setGroup(it) }
            (n.accentColor ?: defaultColor(context))?.let { builder.setColor(it) }
            // Kilit ekranı görünürlüğü (Android 8+ kanalda da ayarlanabilir; bildirim değeri daha kısıtlayıcıysa geçerlidir).
            n.visibility?.let { builder.setVisibility(it) }
            // Android 8+ seste kanal ayarı geçerlidir; önceki sürümlerde bildirime yazılır.
            if (soundUri != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) builder.setSound(soundUri)

            val largeIcon = n.largeIcon?.let { largeIconBitmap(context, it) }
            largeIcon?.let { builder.setLargeIcon(it) }
            n.imageUrl?.let { url ->
                HsImages.download(url)?.let { bitmap ->
                    // Büyük ikon ayrıca verilmediyse büyük resim daraltılmış görünümde ikon olarak da gösterilir.
                    if (largeIcon == null) builder.setLargeIcon(bitmap)
                    builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as Bitmap?))
                }
            }
            n.actions.take(3).forEachIndexed { index, action ->
                builder.addAction(0, action.title, NotificationOpenActivity.pendingIntent(context, n, action.id, tag, notificationId, notificationId * 31 + index + 1))
            }
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            try {
                manager.notify(tag, notificationId, builder.build())
                n.groupKey?.let { group -> postGroupSummary(context, manager, channelId, iconRes, group, n) }
            } catch (e: SecurityException) {
                // Android 13+: POST_NOTIFICATIONS izni geri alınmış olabilir.
                HsLog.w("bildirim gösterilemedi (izin yok)", e)
                if (HeroSignal.isInitialized) HeroSignal.reportError("notification_permission", "bildirim izni olmadan gösterim denendi", e, level = "warning")
            }
        }

        /** Android 7+ grup başlığı: aynı `hs_group` anahtarlı bildirimler tek grupta toplanır. */
        private fun postGroupSummary(context: Context, manager: NotificationManagerCompat, channelId: String, iconRes: Int, group: String, n: HsNotification) {
            if (!HsNotificationTray.supportsGroups) return
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(iconRes)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            HsNotificationTray.mark(builder)
            (n.accentColor ?: defaultColor(context))?.let { builder.setColor(it) }
            n.visibility?.let { builder.setVisibility(it) }
            manager.notify(HsNotificationTray.GROUP_SUMMARY_TAG, group.hashCode(), builder.build())
        }

        /**
         * Küçük ikon: `hs_small_icon` drawable adı → manifest `com.herosignal.sdk.default_notification_icon`
         * → SDK ikonu. Kaynak adı uygulama paketinde bulunamazsa sonraki seçeneğe düşülür.
         */
        private fun smallIcon(context: Context, name: String?): Int =
            name?.let { resourceId(context, it, "drawable") }
                ?: runCatching {
                    context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
                        .metaData?.getInt("com.herosignal.sdk.default_notification_icon", 0)?.takeIf { it != 0 }
                }.getOrNull()
                ?: R.drawable.hs_ic_notification

        /**
         * Varsayılan vurgu rengi: manifest `com.herosignal.sdk.default_notification_color` — renk kaynağı
         * (`android:resource="@color/…"`) veya doğrudan değer (`android:value="#FF9900"`). Yoksa null (sistem rengi).
         */
        private fun defaultColor(context: Context): Int? = runCatching {
            val meta = context.packageManager
                .getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
                ?: return@runCatching null
            val key = "com.herosignal.sdk.default_notification_color"
            if (!meta.containsKey(key)) return@runCatching null
            val raw = meta.getInt(key, 0)
            runCatching { androidx.core.content.ContextCompat.getColor(context, raw) }.getOrDefault(raw)
        }.getOrNull()

        /** Büyük ikon: http(s) URL'si indirilir; aksi halde drawable/mipmap kaynak adı olarak çözülür. */
        private fun largeIconBitmap(context: Context, value: String): Bitmap? {
            if (value.startsWith("https://") || value.startsWith("http://")) return HsImages.download(value)
            val resId = resourceId(context, value, "drawable") ?: resourceId(context, value, "mipmap") ?: return null
            return runCatching { BitmapFactory.decodeResource(context.resources, resId) }.getOrNull()
        }

        /** Uzantısız, küçük harfli kaynak adını uygulama paketinde arar; yoksa null. */
        private fun resourceId(context: Context, name: String, type: String): Int? {
            val clean = name.substringBeforeLast('.').lowercase()
            if (!clean.matches(Regex("[a-z0-9_]+"))) return null
            return context.resources.getIdentifier(clean, type, context.packageName).takeIf { it != 0 }
        }

        /** `hs_sound`: "default" → sistem sesi; aksi halde `res/raw/<ad>` (uzantısız) aranır. Kanal sesi için de kullanılır. */
        internal fun soundUri(context: Context, sound: String?): Uri? {
            if (sound.isNullOrBlank() || sound == "default") return null
            val name = sound.substringBeforeLast('.').lowercase()
            val resId = context.resources.getIdentifier(name, "raw", context.packageName)
            if (resId == 0) return null
            return Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/$name")
        }

        /**
         * Kanal yoksa oluşturur. Dashboard'daki kanallar (`android_channels`) başlatmada [HsNotificationChannels] ile
         * kurulduğundan `android_channel_id` o kanalı kullanır; tanımsız kimlikte kimliği ad olan yüksek öncelikli kanal açılır.
         */
        private fun ensureChannel(context: Context, channelId: String, soundUri: Uri?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) != null) return
            val name = if (channelId == HeroSignal.options.defaultChannelId) HeroSignal.options.defaultChannelName else channelId
            manager.createNotificationChannel(NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                soundUri?.let {
                    setSound(it, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                }
            })
        }
    }
}
