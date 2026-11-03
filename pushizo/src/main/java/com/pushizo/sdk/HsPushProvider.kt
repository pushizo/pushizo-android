package com.pushizo.sdk

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
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

/**
 * Ana push aboneliğinin yanında ikincil kanal kaydı yapan eklenti modülü (ör. `pushizo-adm`: Fire OS ADM). Çekirdek
 * modülü yansıma ile bulur ve ağ çağrısına izin verildiğinde (gizlilik onayı) her başlatmada [start] çağırır. Uygulama
 * kodunun bu arayüzü uygulaması gerekmez.
 */
interface HsPushRegistrar {
    /** Kaydı başlatır veya mevcut kayıt kimliğini [HsPushBridge] üzerinden bildirir; engellememeli, hata fırlatmamalı. */
    fun start(context: Context)
}

/** Sağlayıcı seçimi: bir cihazda yalnızca bir push aboneliği (`android_push` veya `hms_push`) bulunur. */
internal object HsPushProviders {
    const val CHANNEL_FCM = "android_push"
    const val CHANNEL_HMS = "hms_push"
    /** Fire OS ADM: ana abonelikten ayrı ikincil push aboneliği (`Pushizo.User.registerAdm`). */
    const val CHANNEL_ADM = "adm_push"

    /** `pushizo-adm` modülünün kayıt sınıfı; modül eklenmişse yansıma ile bulunur (çekirdek Amazon ADM'ye bağımlı değildir). */
    const val ADM_REGISTRAR_CLASS = "com.pushizo.sdk.adm.AdmRegistrar"

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

    /** Eklenmiş ikincil kayıt modülleri (şu an yalnızca `pushizo-adm`); modül yoksa boş liste. */
    fun registrars(): List<HsPushRegistrar> = listOfNotNull(
        runCatching {
            Class.forName(ADM_REGISTRAR_CLASS).getDeclaredConstructor().newInstance() as HsPushRegistrar
        }.getOrNull(),
    )

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
        if (HsFcmRegistration.fidEnabled(context)) {
            requestInstallationId(onToken, onError)
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> onToken(token) }
            .addOnFailureListener { e -> onError(e) }
    }

    /** Firebase Installation ID kipi: önce FCM kaydı tamamlanır, kimlik sonra okunur; kaydsız kimliğe teslimat yapılamaz. */
    private fun requestInstallationId(onToken: (String) -> Unit, onError: (Throwable) -> Unit) {
        val registration = try {
            HsFcmRegistration.invokeRegister(FirebaseMessaging.getInstance())
        } catch (e: ReflectiveOperationException) {
            onError(IllegalStateException(HsFcmRegistration.MISSING_REGISTER, e))
            return
        }
        registration
            .addOnSuccessListener {
                FirebaseInstallations.getInstance().id
                    .addOnSuccessListener { id -> onToken(id) }
                    .addOnFailureListener { e -> onError(e) }
            }
            .addOnFailureListener { e -> onError(e) }
    }
}

/**
 * Firebase Installation ID (FID) kaydı. `firebase_messaging_installation_id_enabled` bildirimi uygulama genelinde
 * `getToken()`'ı kapatır; bayrağı başka bir SDK da manifest birleşmesiyle ekleyebilir, bu yüzden her istekte okunur.
 * SDK firebase-messaging 24.x ile derlenir ve `register()` 25.1.2'de geldiği için yansımayla çağrılır.
 */
internal object HsFcmRegistration {
    const val FID_META_KEY = "firebase_messaging_installation_id_enabled"
    const val MISSING_REGISTER = "Firebase Installation ID kipi firebase-messaging 25.1.2 veya üstünü gerektirir."

    /** Manifest değeri `android:value="true"` için Boolean, kaynak üzerinden gelirse metin olabilir. */
    fun isFidValue(value: Any?): Boolean = value == true || (value as? String)?.equals("true", ignoreCase = true) == true

    fun fidEnabled(context: Context): Boolean = try {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        @Suppress("DEPRECATION")
        isFidValue(info.metaData?.get(FID_META_KEY))
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    @Suppress("UNCHECKED_CAST")
    fun invokeRegister(messaging: Any): Task<Any?> = messaging.javaClass.getMethod("register").invoke(messaging) as Task<Any?>
}

/**
 * Push sağlayıcı modüllerinin (ör. `pushizo-hms`) çekirdeğe giriş noktası: token yenileme, mesaj alma ve veri
 * dönüşümü. Uygulama kodunun çağırması gerekmez.
 */
object HsPushBridge {
    const val CHANNEL_FCM: String = HsPushProviders.CHANNEL_FCM
    const val CHANNEL_HMS: String = HsPushProviders.CHANNEL_HMS
    const val CHANNEL_ADM: String = HsPushProviders.CHANNEL_ADM

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
     * Fire OS ADM kayıt kimliği (`pushizo-adm` modülü): `adm_push` aboneliği olarak kaydedilir (bkz.
     * [Pushizo.User.registerAdm]); önceki kimlik varsa aboneliği silinir. SDK gerekirse kayıtlı yapılandırmayla başlatılır.
     */
    @JvmStatic
    fun onAdmRegistered(context: Context, registrationId: String) {
        if (Pushizo.ensureInitialized(context.applicationContext)) Pushizo.onAdmRegistered(registrationId)
    }

    /** ADM kaydı silindi (uygulama veya Amazon tarafından): ilgili `adm_push` aboneliği sunucudan silinir. */
    @JvmStatic
    fun onAdmUnregistered(context: Context, registrationId: String?) {
        if (Pushizo.ensureInitialized(context.applicationContext)) Pushizo.onAdmUnregistered(registrationId)
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
