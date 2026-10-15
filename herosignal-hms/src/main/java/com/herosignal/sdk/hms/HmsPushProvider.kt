package com.herosignal.sdk.hms

import android.content.Context
import android.content.pm.PackageManager
import com.huawei.agconnect.AGConnectOptionsBuilder
import com.huawei.hms.aaid.HmsInstanceId
import com.huawei.hms.api.ConnectionResult
import com.huawei.hms.api.HuaweiApiAvailability
import com.herosignal.sdk.HsPushBridge
import com.herosignal.sdk.HsPushProvider

/**
 * Huawei HMS Push Kit sağlayıcısı. Çekirdek SDK bu sınıfı yansıma ile bulur (modül eklendiyse); cihazda Google Play
 * Services yoksa ve HMS Core varsa FCM yerine kullanılır (`hms_push` aboneliği). Uygulama kodunun çağırması gerekmez.
 */
class HmsPushProvider : HsPushProvider {
    override val channel: String = HsPushBridge.CHANNEL_HMS

    override fun isAvailable(context: Context): Boolean = runCatching {
        HmsAvailability.isUsable(HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(context))
    }.getOrDefault(false)

    /** `HmsInstanceId.getToken(appId, "HCM")` engelleyicidir; çekirdek bunu arka plan iş parçacığında çağırır. */
    override fun requestToken(context: Context, onToken: (String) -> Unit, onError: (Throwable) -> Unit) {
        val appId = HmsAppId.resolve(context) ?: return onError(
            IllegalStateException(
                "HMS App ID bulunamadı: agconnect-services.json ve AppGallery Connect Gradle eklentisini ya da " +
                    "${HmsAppId.META_APP_ID} meta-data değerini ekleyin",
            ),
        )
        try {
            val token = HmsInstanceId.getInstance(context).getToken(appId, TOKEN_SCOPE)
            // Boş token: eski EMUI sürümlerinde token daha sonra HeroSignalHmsMessageService.onNewToken ile gelir.
            if (!token.isNullOrBlank()) onToken(token)
        } catch (e: Exception) {
            onError(e)
        }
    }

    private companion object {
        /** Push Kit token kapsamı. */
        const val TOKEN_SCOPE = "HCM"
    }
}

/** HMS Core kullanılabilirliği (saf mantık; birim testinde sınanır). */
internal object HmsAvailability {
    /**
     * HMS Core kurulu ve kullanılabilir (güncelleme gerektirse de: GMS'siz cihazda tek push yolu HMS'tir ve HMS güncellemeyi
     * kendisi ister). Eksik, devre dışı veya geçersiz HMS Core'da false.
     */
    fun isUsable(result: Int): Boolean =
        result == ConnectionResult.SUCCESS || result == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
}

/**
 * AppGallery Connect App ID: önce uygulama manifest'indeki `com.herosignal.sdk.hms_app_id` meta-data (açık ayar), sonra
 * `agconnect-services.json` (`client/app_id`; AppGallery Connect Gradle eklentisi assets'e kopyalar), son olarak HMS'in
 * `com.huawei.hms.client.appid` meta-data değeri (`appid=…`).
 */
internal object HmsAppId {
    const val META_APP_ID = "com.herosignal.sdk.hms_app_id"
    const val META_HMS_APP_ID = "com.huawei.hms.client.appid"
    private const val AGC_APP_ID_PATH = "client/app_id"

    fun resolve(context: Context): String? {
        val meta = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
        @Suppress("DEPRECATION")
        normalize(meta?.get(META_APP_ID))?.let { return it }
        runCatching { AGConnectOptionsBuilder().build(context).getString(AGC_APP_ID_PATH) }.getOrNull()
            ?.let(::normalize)?.let { return it }
        @Suppress("DEPRECATION")
        return normalize(meta?.get(META_HMS_APP_ID))
    }

    /** Meta-data değeri sayı (`android:value="123"` → Integer) veya `appid=123` metni olabilir; boşsa null. */
    fun normalize(raw: Any?): String? =
        raw?.toString()?.trim()?.removePrefix("appid=")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
