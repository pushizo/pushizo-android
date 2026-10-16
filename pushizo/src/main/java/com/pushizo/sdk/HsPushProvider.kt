package com.pushizo.sdk

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

/**
 * Push sağlayıcısı: FCM çekirdekte yerleşiktir, Huawei HMS Push Kit `pushizo-hms` modülüyle gelir. Uygulama kodunun
 * bu arayüzü uygulaması gerekmez; eklenti modülleri için açıktır.
 */
interface HsPushProvider {
    /** Abonelik kanalı (`android_push` | `hms_push`). */
    val channel: String

    /** Sağlayıcının hizmeti bu cihazda kullanılabilir mi (ör. HMS Core kurulu). */
    fun isAvailable(context: Context): Boolean

    /**
     * Push token'ını ister. Arka plan iş parçacığında çağrılır, engelleyebilir. Token bu çağrıda gelmezse (ör. HMS'in eski
     * EMUI sürümlerinde) sağlayıcının servisi daha sonra [HsPushBridge.onNewToken] ile bildirir.
     */
    fun requestToken(context: Context, onToken: (String) -> Unit, onError: (Throwable) -> Unit)
}

/** Sağlayıcı seçimi: bir cihazda yalnızca bir push aboneliği (`android_push` veya `hms_push`) bulunur. */
internal object HsPushProviders {
    const val CHANNEL_FCM = "android_push"
    const val CHANNEL_HMS = "hms_push"

    /** `pushizo-hms` modülünün sağlayıcı sınıfı; modül eklenmişse yansıma ile bulunur (çekirdek HMS'e bağımlı değildir). */
    const val HMS_PROVIDER_CLASS = "com.pushizo.sdk.hms.HmsPushProvider"

    /**
     * Kanal seçimi: HMS modülü eklenmiş, Google Play Services kullanılamıyor ve HMS Core kullanılabiliyorsa `hms_push`;
     * diğer tüm durumlarda (GMS olan Huawei cihazları dahil) `android_push`.
     */
    fun selectChannel(hmsModulePresent: Boolean, gmsAvailable: Boolean, hmsAvailable: Boolean): String =
        if (hmsModulePresent && !gmsAvailable && hmsAvailable) CHANNEL_HMS else CHANNEL_FCM

    /**
     * Google Play Services kurulu mu: güncelleme gerektiren veya güncellenmekte olan GMS de kurulu sayılır; böylece geçici
     * durumlarda sağlayıcı (ve abonelik) gidip gelmez. Yalnızca eksik/devre dışı/geçersiz GMS'te HMS'e geçilir.
     */
    fun gmsAvailable(context: Context): Boolean = runCatching {
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) in GMS_INSTALLED_RESULTS
    }.getOrDefault(false)

    private val GMS_INSTALLED_RESULTS = setOf(
        ConnectionResult.SUCCESS,
        ConnectionResult.SERVICE_UPDATING,
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
    )

    /** HMS modülünün sağlayıcısı; modül eklenmemişse null. */
    fun hmsProvider(): HsPushProvider? = runCatching {
        Class.forName(HMS_PROVIDER_CLASS).getDeclaredConstructor().newInstance() as HsPushProvider
    }.getOrNull()

    /** Bu cihazda kullanılacak sağlayıcı. */
    fun resolve(context: Context): HsPushProvider {
        val hms = hmsProvider()
        val channel = selectChannel(
            hmsModulePresent = hms != null,
            gmsAvailable = hms == null || gmsAvailable(context),
            hmsAvailable = hms != null && runCatching { hms.isAvailable(context) }.getOrDefault(false),
        )
        return if (channel == CHANNEL_HMS && hms != null) hms else HsFcmPushProvider
    }
}

/** Yerleşik FCM sağlayıcısı (müşteri `google-services.json` ekler). */
internal object HsFcmPushProvider : HsPushProvider {
    override val channel: String = HsPushProviders.CHANNEL_FCM

    override fun isAvailable(context: Context): Boolean = HsPushProviders.gmsAvailable(context)

    override fun requestToken(context: Context, onToken: (String) -> Unit, onError: (Throwable) -> Unit) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> onToken(token) }
            .addOnFailureListener { e -> onError(e) }
    }
}

/**
 * Push sağlayıcı modüllerinin (ör. `pushizo-hms`) çekirdeğe giriş noktası: token yenileme, mesaj alma ve veri
 * dönüşümü. Uygulama kodunun çağırması gerekmez.
 */
object HsPushBridge {
    const val CHANNEL_FCM: String = HsPushProviders.CHANNEL_FCM
    const val CHANNEL_HMS: String = HsPushProviders.CHANNEL_HMS

    /** Sağlayıcı servisinden gelen yeni/yenilenen token; SDK gerekirse kayıtlı yapılandırmayla başlatılır. */
    @JvmStatic
    fun onNewToken(context: Context, channel: String, token: String) {
        if (Pushizo.ensureInitialized(context.applicationContext)) Pushizo.onNewToken(token, channel)
    }

    /** Sağlayıcının bildirdiği token hatası (mesaj token/kişisel veri içermez). */
    @JvmStatic
    fun onTokenError(context: Context, channel: String, error: Throwable) {
        HsLog.w("$channel token alınamadı", error)
        if (Pushizo.ensureInitialized(context.applicationContext)) {
            Pushizo.reportError(Pushizo.tokenErrorCode(channel), "$channel token alınamadı", error)
        }
    }

    /**
     * Veri mesajı (FCM data map'iyle aynı anahtarlar): `received` bildirimi, ön plan dinleyicileri, bildirim oluşturma ve
     * açılma izleme FCM yoluyla aynıdır.
     */
    @JvmStatic
    fun onMessageReceived(context: Context, data: Map<String, String>) {
        PushizoMessagingService.handle(context.applicationContext, data)
    }

    /**
     * HMS `RemoteMessage.getData()` JSON metnini FCM data map'ine çevirir: metin değerler aynen, sayı/mantıksal değerler
     * metne, iç içe nesne/dizi (ör. `hs`, `hs_actions`) JSON metnine çevrilir; `null` değerler atlanır. Geçersiz/boş
     * girdide boş map.
     */
    @JvmStatic
    fun dataFromJson(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            if (obj.isNull(key)) continue
            // JSONObject/JSONArray.toString() geçerli JSON üretir (HsNotification.fromFcm `hs`/`hs_actions` metnini ayrıştırır).
            result[key] = obj.get(key).toString()
        }
        return result
    }
}
