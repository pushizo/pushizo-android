package com.pushizo.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Geriye dönük uyumluluk: SDK'nın önceki sürümlerinin gösterdiği (bu receiver'ı hedefleyen) bildirimlerin
 * tıklamalarını işler. Yeni bildirimler [NotificationOpenActivity] kullanır; Android 12+ hedefleyen uygulamalarda
 * receiver'dan Activity açmak (trampoline) sistem tarafından engellenebilir, bu durumda yalnızca `opened` bildirilir.
 * Ekstra sabitleri (EXTRA_*) public API'nin parçasıdır ve açılan Activity intent'inde de kullanılır.
 */
class NotificationOpenedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notification = NotificationOpenActivity.notificationFrom(intent)
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
        if (Pushizo.ensureInitialized(context)) Pushizo.reportOpened(notification, actionId)
        runCatching { NotificationOpenActivity.launchTarget(context, notification.url, notification.deliveryId) }
            .onFailure { HsLog.w("bildirim hedefi açılamadı", it) }
    }

    companion object {
        const val EXTRA_DELIVERY_ID = "hs_delivery_id"
        const val EXTRA_MESSAGE_ID = "hs_message_id"
        const val EXTRA_TITLE = "hs_title"
        const val EXTRA_BODY = "hs_body"
        const val EXTRA_URL = "hs_url"
        const val EXTRA_ACTION_ID = "hs_action_id"
        const val EXTRA_DATA = "hs_data"
    }
}
