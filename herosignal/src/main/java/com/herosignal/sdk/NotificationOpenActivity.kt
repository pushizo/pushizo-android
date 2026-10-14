package com.herosignal.sdk

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat

/**
 * Bildirim gövdesi ve aksiyon düğmesi tıklamalarını karşılayan arayüzsüz Activity.
 * Android 12+ "notification trampoline" kısıtı nedeniyle tıklama doğrudan bir Activity açar
 * (BroadcastReceiver → startActivity yasak): `opened` bildirir, tıklama dinleyicilerini çalıştırır,
 * URL'yi (http(s) → tarayıcı, uygulama şeması → deep link) ya da uygulamanın launcher activity'sini açar ve kapanır.
 */
class NotificationOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        val notification = notificationFrom(intent)
        val actionId = intent.getStringExtra(NotificationOpenedReceiver.EXTRA_ACTION_ID)
        // Aksiyon düğmeleri bildirimi kendiliğinden kapatmaz; burada kaldırılır.
        if (actionId != null && intent.hasExtra(EXTRA_NOTIFICATION_ID)) {
            NotificationManagerCompat.from(this).cancel(intent.getStringExtra(EXTRA_NOTIFICATION_TAG), intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        }
        // Soğuk başlangıçta SDK kayıtlı yapılandırmayla başlatılır; tıklama dinleyici eklenene kadar saklanır.
        if (HeroSignal.ensureInitialized(applicationContext)) HeroSignal.reportOpened(notification, actionId)
        launchTarget(this, notification.url, notification.deliveryId)
    }

    companion object {
        internal const val EXTRA_NOTIFICATION_ID = "hs_notification_id"
        internal const val EXTRA_NOTIFICATION_TAG = "hs_notification_tag"

        /** Gövde (actionId = null) veya düğme tıklaması için Activity PendingIntent'i. */
        internal fun pendingIntent(context: Context, n: HsNotification, actionId: String?, tag: String?, notificationId: Int, requestCode: Int): PendingIntent {
            val intent = Intent(context, NotificationOpenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtras(this, n, actionId, tag, notificationId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getActivity(context, requestCode, intent, flags)
        }

        internal fun putExtras(intent: Intent, n: HsNotification, actionId: String?, tag: String?, notificationId: Int) {
            intent.putExtra(NotificationOpenedReceiver.EXTRA_DELIVERY_ID, n.deliveryId)
            intent.putExtra(NotificationOpenedReceiver.EXTRA_MESSAGE_ID, n.messageId)
            intent.putExtra(NotificationOpenedReceiver.EXTRA_TITLE, n.title)
            intent.putExtra(NotificationOpenedReceiver.EXTRA_BODY, n.body)
            intent.putExtra(NotificationOpenedReceiver.EXTRA_URL, n.urlFor(actionId))
            intent.putExtra(NotificationOpenedReceiver.EXTRA_ACTION_ID, actionId)
            intent.putExtra(NotificationOpenedReceiver.EXTRA_DATA, HashMap(n.data))
            intent.putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            intent.putExtra(EXTRA_NOTIFICATION_TAG, tag)
        }

        /** Intent ekstralarından tıklanan bildirimi yeniden oluşturur. */
        internal fun notificationFrom(intent: Intent): HsNotification {
            @Suppress("UNCHECKED_CAST", "DEPRECATION")
            val data = (intent.getSerializableExtra(NotificationOpenedReceiver.EXTRA_DATA) as? HashMap<String, String>) ?: hashMapOf()
            return HsNotification(
                title = intent.getStringExtra(NotificationOpenedReceiver.EXTRA_TITLE),
                body = intent.getStringExtra(NotificationOpenedReceiver.EXTRA_BODY),
                imageUrl = null,
                url = intent.getStringExtra(NotificationOpenedReceiver.EXTRA_URL),
                deliveryId = intent.getStringExtra(NotificationOpenedReceiver.EXTRA_DELIVERY_ID),
                messageId = intent.getStringExtra(NotificationOpenedReceiver.EXTRA_MESSAGE_ID),
                collapseKey = null,
                channelId = null,
                actions = emptyList(),
                data = data,
                androidNotificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
            )
        }

        /** URL varsa onu, yoksa (veya açılamazsa) uygulamanın launcher activity'sini açar. */
        internal fun launchTarget(context: Context, url: String?, deliveryId: String?) {
            val appLaunch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(NotificationOpenedReceiver.EXTRA_DELIVERY_ID, deliveryId)
            }
            val target = url?.let {
                Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(NotificationOpenedReceiver.EXTRA_DELIVERY_ID, deliveryId)
                }
            } ?: appLaunch
            try {
                target?.let { context.startActivity(it) }
            } catch (e: ActivityNotFoundException) {
                // URL'yi açacak uygulama yoksa uygulamanın kendisi açılır.
                HsLog.w("bildirim URL'si açılamadı, uygulama açılıyor", e)
                appLaunch?.let { context.startActivity(it) }
            }
        }
    }
}
