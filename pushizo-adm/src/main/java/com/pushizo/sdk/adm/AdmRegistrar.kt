package com.pushizo.sdk.adm

import android.content.Context
import com.amazon.device.messaging.ADM
import com.pushizo.sdk.HsLog
import com.pushizo.sdk.HsPushRegistrar

/**
 * Fire OS ADM kaydı. Çekirdek SDK bu sınıfı yansıma ile bulur (modül eklendiyse) ve her başlatmada [start] çağırır;
 * uygulama kodunun çağırması gerekmez. Kayıt kimliği varsa hemen bildirilir, yoksa `ADM.startRegister()` ile istenir ve
 * sonuç [PushizoAdmMessageReceiver] üzerinden gelir. ADM sınıfları yoksa (Fire OS dışı cihaz) modül sessizce devre
 * dışı kalır.
 */
class AdmRegistrar internal constructor(
    private val admPresent: () -> Boolean,
    private val clientFactory: (Context) -> AdmClient,
) : HsPushRegistrar {

    constructor() : this({ AdmClasses.isPresent(AdmClasses.ADM) }, ::AmazonAdmClient)

    override fun start(context: Context) {
        if (!admPresent()) {
            HsLog.d("Amazon ADM bulunamadı (Fire OS dışı cihaz); ADM modülü devre dışı")
            return
        }
        try {
            val client = clientFactory(context)
            if (!client.isSupported) {
                HsLog.d("ADM bu cihazda desteklenmiyor; ADM modülü devre dışı")
                return
            }
            val existing = client.registrationId?.trim()
            if (existing.isNullOrEmpty()) {
                // Sonuç ADM yayınıyla gelir: PushizoAdmMessageReceiver → onRegistered / onRegistrationError.
                client.startRegister()
            } else {
                AdmDispatcher.onRegistered(context, existing)
            }
        } catch (e: Exception) {
            HsLog.w("ADM kaydı başlatılamadı", e)
        } catch (e: LinkageError) {
            // ADM sınıfı var ama beklenen imza yok (uyumsuz ADM kütüphanesi): uygulama çökmez.
            HsLog.w("ADM kütüphanesi uyumsuz; ADM modülü devre dışı", e)
        }
    }
}

/** ADM istemcisi soyutlaması (birim testinde sahte uygulanır). */
internal interface AdmClient {
    val isSupported: Boolean
    val registrationId: String?
    fun startRegister()
}

/** Amazon `com.amazon.device.messaging.ADM` sarmalayıcısı; yalnızca ADM sınıfı varken oluşturulur. */
internal class AmazonAdmClient(context: Context) : AdmClient {
    private val adm = ADM(context.applicationContext ?: context)
    override val isSupported: Boolean get() = adm.isSupported
    override val registrationId: String? get() = adm.registrationId
    override fun startRegister() = adm.startRegister()
}

/** Çalışma anında ADM sınıflarının varlığı (cihaz kütüphanesi `amazon:enable-feature` ile yüklenir). */
internal object AdmClasses {
    const val ADM = "com.amazon.device.messaging.ADM"

    /** ADM 1.1.0+ JobScheduler işleyicisi; yoksa yalnızca eski IntentService işleyicisi kullanılır. */
    const val JOB_BASE = "com.amazon.device.messaging.ADMMessageHandlerJobBase"

    fun isPresent(name: String, loader: ClassLoader? = AdmClasses::class.java.classLoader): Boolean = try {
        Class.forName(name, false, loader)
        true
    } catch (e: ClassNotFoundException) {
        false
    } catch (e: LinkageError) {
        false
    }
}
