package com.herosignal.sdk.hms

import android.os.Bundle
import com.herosignal.sdk.HsLog
import com.herosignal.sdk.HsPushBridge
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage

/**
 * HMS Push Kit servisi (manifest merge ile eklenir): token yenileme ve veri mesajları. Sunucu HMS'e yalnızca veri mesajı
 * gönderir; `RemoteMessage.getData()` FCM data map'iyle aynı anahtarları taşıyan JSON metnidir ve çekirdeğin FCM mesaj
 * yoluyla (received, gösterim, açılma izleme) işlenir.
 */
class HeroSignalHmsMessageService : HmsMessageService() {

    override fun onNewToken(token: String?) {
        handleToken(token)
    }

    override fun onNewToken(token: String?, bundle: Bundle?) {
        // `subjectId` taşıyan token alt uygulamaya (çoklu gönderici) aittir; bu uygulamanın aboneliği değildir.
        if (isSubApp(bundle)) return
        handleToken(token)
    }

    override fun onTokenError(e: Exception?) {
        handleTokenError(e)
    }

    override fun onTokenError(e: Exception?, bundle: Bundle?) {
        if (isSubApp(bundle)) return
        handleTokenError(e)
    }

    private fun isSubApp(bundle: Bundle?): Boolean = !bundle?.getString(HmsMessageService.SUBJECT_ID).isNullOrBlank()

    override fun onMessageReceived(message: RemoteMessage?) {
        val data = HsPushBridge.dataFromJson(message?.data)
        if (data.isEmpty()) return
        HsPushBridge.onMessageReceived(applicationContext, data)
    }

    private fun handleToken(token: String?) {
        if (token.isNullOrBlank()) return
        HsLog.d("HMS token yenilendi")
        HsPushBridge.onNewToken(applicationContext, HsPushBridge.CHANNEL_HMS, token)
    }

    private fun handleTokenError(e: Exception?) {
        HsPushBridge.onTokenError(applicationContext, HsPushBridge.CHANNEL_HMS, e ?: IllegalStateException("HMS token hatası"))
    }
}
