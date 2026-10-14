package com.herosignal.sdk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Bildirim tıklaması bilgisi. */
data class HsClickEvent(val notification: HsNotification, val actionId: String?, val url: String?)

/**
 * Ön planda gelen bildirim; `preventDefault()` gösterimi engeller. Karar sonradan verilecekse
 * (ör. Flutter/Dart tarafı) önce `preventDefault()`, ardından gerekirse `display()` çağrılır.
 */
class HsForegroundEvent(val notification: HsNotification) {
    @Volatile var prevented: Boolean = false
        private set
    private val displayed = AtomicBoolean(false)

    fun preventDefault() { prevented = true }

    /** Bildirimi şimdi gösterir (yalnızca bir kez; `preventDefault()` sonrasında da çalışır). */
    fun display() {
        if (markDisplayed()) HeroSignal.displayNotification(notification)
    }

    /** Gösterim hakkını alır; daha önce gösterildiyse false. */
    internal fun markDisplayed(): Boolean = displayed.compareAndSet(false, true)
}

/** Abonelik durumu (gözlemciler için). */
data class HsPushSubscriptionState(val id: String?, val token: String?, val optedIn: Boolean, val permission: Boolean)

/**
 * HeroSignal Android SDK (eski sağlayıcı v5 yüzeyiyle uyumlu).
 *
 * ```kotlin
 * HeroSignal.initialize(this, "app_…", HeroSignal.Options(apiUrl = "https://api.example.com"))
 * HeroSignal.Notifications.requestPermission(activity)
 * HeroSignal.Notifications.addPermissionObserver { granted -> }
 * HeroSignal.login("user_42")
 * HeroSignal.User.addTag("plan", "pro")
 * HeroSignal.User.addEmail("ali@example.com")
 * HeroSignal.InAppMessages.addTrigger("level", "gold")
 * val page = HeroSignal.Inbox.list(limit = 20)
 * ```
 */
object HeroSignal {
    data class Options(
        val apiUrl: String = "https://api.herosignal.dev",
        /** KVKK/GDPR: onay verilene kadar ağ çağrısı yapılmaz. */
        val requiresPrivacyConsent: Boolean = false,
        val logLevel: HsLog.Level = HsLog.Level.WARN,
        /** Varsayılan bildirim kanalı adı (Android 8+). */
        val defaultChannelName: String = "Bildirimler",
        val defaultChannelId: String = "herosignal_default",
    )

    @Volatile internal var appContext: Context? = null
    @Volatile internal var appId: String = ""
    @Volatile internal var options: Options = Options()
    @Volatile internal var storage: HsStorage? = null
    @Volatile internal var api: HsApi? = null
    private var queue: HsQueue? = null
    private var errorReporter: HsErrorReporter? = null
    private val errorFlushScheduled = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clickListeners = CopyOnWriteArraySet<(HsClickEvent) -> Unit>()
    private val foregroundListeners = CopyOnWriteArraySet<(HsForegroundEvent) -> Unit>()
    private val subscriptionObservers = CopyOnWriteArraySet<(HsPushSubscriptionState) -> Unit>()
    private val permissionObservers = HsPermissionObservers { HsLog.w("izin gözlemcisi hata verdi", it) }
    @Volatile private var sessionTracker: HsSessionTracker? = null
    /** Arka plana geçişten 30 sn sonra oturumu kapatan iş (ön plana dönülürse iptal edilir). */
    @Volatile private var sessionEndJob: Job? = null
    /** Eşzamanlı kayıtlar (FCM servisi + başlatma) iki abonelik açmasın diye. */
    private val registrationLock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var identityVerificationRequired = false
    @Volatile private var pendingClick: HsClickEvent? = null
    @Volatile private var inApp: HsInAppController? = null

    /** In-app akışının SDK'ya bağlantısı (abonelik, API, kuyruk). */
    private val inAppHost = object : HsInAppController.Host {
        override val context: Context? get() = appContext
        override fun subscriptionId(): String? = engagementSubscriptionId()
        override fun ownershipTokenHash(subscriptionId: String): String? =
            storage?.let { ownershipToken(it, subscriptionId) }?.let(HsOwnership::tokenHash)
        override fun request(method: String, path: String, body: JSONObject?): JSONObject =
            api?.request(method, path, body) ?: throw HsApiException(0, "NOT_INITIALIZED", "SDK başlatılmadı")
        override fun enqueue(op: JSONObject) = HeroSignal.enqueue(op)
        override fun addTags(tags: Map<String, String>) = User.addTags(tags)
        override fun sendEvent(name: String, value: Double?) = Session.sendEvent(name, emptyMap(), value)
        override fun requestPushPermission(activity: Activity?) {
            if (activity == null) HsLog.w("push_prompt: ön planda Activity yok, izin istenemedi")
            else Notifications.requestPermission(activity)
        }
        override fun runInBackground(block: () -> Unit) { scope.launch { block() } }
        override fun reportError(code: String, message: String, error: Throwable?) = HeroSignal.reportError(code, message, error)
        override fun pendingInAppMessageIds(): Set<String> = storage?.queue.orEmpty()
            .filter { it.optString("type") == "in_app_event" }
            .mapNotNull { it.optString("message_id").ifBlank { null } }
            .toSet()
        override var lastInAppImpressionAt: Long?
            get() = storage?.lastInAppImpressionAt
            set(value) { storage?.lastInAppImpressionAt = value }
    }

    val isInitialized: Boolean get() = storage != null

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun initialize(context: Context, appId: String, options: Options = Options()) {
        require(appId.isNotBlank()) { "HeroSignal.initialize: appId gerekli" }
        // Yapılandırma kalıcı saklanır: uygulama kodu çalışmadan başlayan süreçlerde [ensureInitialized] kullanır.
        HsInitConfig.save(context, appId, options)
        if (this.appId == appId && storage != null) {
            // Aynı app yeniden başlatıldı (ör. yerelde otomatik başlatma sonrası Flutter/Dart initialize): seçenekler güncellenir.
            if (options != this.options) applyOptions(options)
            return
        }
        this.appContext = context.applicationContext
        this.appId = appId
        this.options = options
        HsLog.level = options.logLevel
        val store = HsStorage(context, appId)
        storage = store
        api = HsApi(options.apiUrl, appId, networkAllowed = ::networkAllowed)
        errorReporter = HsErrorReporter("android", BuildConfig.SDK_VERSION)
        queue = HsQueue(store, { op -> perform(op) }, { op, e ->
            reportError("operation_rejected", "kuyruk işlemi sunucu tarafından reddedildi", e, mapOf("operation" to op.optString("type")))
        }, canFlush = ::networkAllowed)
        sessionTracker = HsSessionTracker(store)
        inApp = HsInAppController(inAppHost).also {
            // Config gelene kadar son bilinen app geneli aralık uygulanır.
            it.globalMinIntervalSeconds = store.inAppMinIntervalSeconds
            it.attach()
        }
        // Lifecycle gözlemcisi ana iş parçacığında eklenmelidir (otomatik başlatma FCM servis iş parçacığından gelebilir).
        onMain { ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver) }
        // İzin gözlemcileri için temel değer (yalnızca sonraki değişiklikler bildirilir).
        permissionObservers.update(Notifications.permission)
        // Önceki süreçte arka planda biten oturum (süreç pencere içinde öldürüldüyse) süresiyle kapatılır.
        sessionTracker?.expire(System.currentTimeMillis())?.let(::enqueueSessionEnd)
        if (!networkAllowed()) {
            HsLog.i("gizlilik onayı bekleniyor")
            return
        }
        start()
    }

    /**
     * Uygulama kodu `initialize` çağırmadan başlayan süreçte (ör. Flutter/React Native uygulaması kapalıyken gelen FCM
     * mesajı, bildirim tıklaması/kapatılması, WorkManager flush'ı) SDK'yı son `initialize` yapılandırmasıyla yerelde
     * başlatır; böylece received/opened/dismissed geri bildirimi kaybolmaz. Hiç `initialize` yapılmadıysa false.
     */
    internal fun ensureInitialized(context: Context): Boolean {
        if (isInitialized) return true
        val (savedAppId, savedOptions) = HsInitConfig.load(context) ?: return false
        runCatching { initialize(context, savedAppId, savedOptions) }.onFailure { HsLog.w("SDK kayıtlı yapılandırmayla başlatılamadı", it) }
        return isInitialized
    }

    /** Aynı app için yeni seçenekler (API adresi, onay zorunluluğu, günlük seviyesi, kanal) uygulanır. */
    private fun applyOptions(newOptions: Options) {
        val wasAllowed = networkAllowed()
        options = newOptions
        HsLog.level = newOptions.logLevel
        api = HsApi(newOptions.apiUrl, appId, networkAllowed = ::networkAllowed)
        if (!wasAllowed && networkAllowed()) start()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** KVKK/GDPR: onay zorunlu değilse veya onay verildiyse ağ çağrısı yapılabilir. */
    private fun networkAllowed(): Boolean {
        val store = storage ?: return false
        return !options.requiresPrivacyConsent || store.consentGiven
    }

    /**
     * KVKK/GDPR onayı. Onay zorunluyken onay verilmeden hiçbir ağ çağrısı yapılmaz; işlemler (tag, olay, e-posta/SMS,
     * teslimat geri bildirimi) kalıcı kuyrukta bekler ve onay verilince gönderilir.
     */
    @JvmStatic
    fun setConsentGiven(given: Boolean) {
        val store = storage ?: return
        val wasAllowed = networkAllowed()
        store.consentGiven = given
        if (given) {
            start()
            return
        }
        queue?.clear()
        store.clearIdentity()
        appContext?.let { WorkManager.getInstance(it).cancelUniqueWork(FLUSH_WORK) }
        if (options.requiresPrivacyConsent && wasAllowed) closeSubscriptionAfterConsentRevoked(store)
    }

    /**
     * Onay geri alındı: eski sağlayıcı'deki gibi bundan sonra hiçbir veri gönderilmez (kuyruk boşaltıldı, ağ kapısı kapalı).
     * Tek istisna bilinçli olarak bu çağrıdır: onaydan önce kaydedilmiş push aboneliği bildirim almaya devam etmesin
     * diye sunucuya bir kez `opted_in: false` yazılır (yeni kişisel veri içermez, yalnızca sahiplik kanıtı). Kullanıcının
     * yerel tercihi (`optedIn`) korunur; onay yeniden verilince [reconcileSubscription] onu sunucuya geri yazar.
     */
    private fun closeSubscriptionAfterConsentRevoked(store: HsStorage) {
        val subId = store.subscriptionId ?: return
        val token = store.registeredToken
        val api = api ?: return
        scope.launch {
            runCatching {
                api.requestIgnoringConsent("PATCH", "/subscriptions/$subId", HsOwnership.withProof(JSONObject().put("opted_in", false), token))
                store.serverOptedIn = false
            }.onFailure { HsLog.w("onay geri alınınca abonelik kapatılamadı", it) }
            notifySubscription()
        }
    }

    private fun start() {
        scope.launch {
            if (!networkAllowed()) return@launch
            runCatching { applyConfig(api!!.request("GET", "/config")) }.onFailure {
                HsLog.w("config alınamadı", it)
                reportError("config_failed", "app yapılandırması alınamadı", it)
            }
            // Onay yokken yerelde yapılan login/logout ve izin/opt-in değişiklikleri sunucuya yansıtılır.
            runCatching { syncPendingIdentity() }.onFailure {
                HsLog.w("bekleyen kimlik eşitlenemedi", it)
                reportError("identity_sync_failed", "bekleyen login/logout eşitlenemedi", it)
            }
            runCatching { reconcileSubscription() }.onFailure {
                HsLog.w("abonelik durumu eşitlenemedi", it)
                reportError("subscription_update_failed", "abonelik durumu eşitlenemedi", it)
            }
            runCatching { refreshFcmToken() }.onFailure {
                HsLog.w("FCM token alınamadı", it)
                reportError("fcm_token_failed", "FCM token alınamadı", it)
            }
            if (queue?.flush() == false) scheduleFlush()
        }
    }

    /**
     * `/config` yanıtı: kimlik doğrulama zorunluluğu, app geneli in-app aralığı (kalıcı) ve dashboard'daki Android
     * bildirim kanalları (`android_channels` → `NotificationChannel`; ADR-0019 §7, §12).
     */
    private fun applyConfig(config: JSONObject) {
        identityVerificationRequired = config.optBoolean("identity_verification_required", false)
        val interval = config.optJSONObject("in_app")?.optLong("min_interval_seconds", 0L)?.coerceAtLeast(0L) ?: 0L
        storage?.inAppMinIntervalSeconds = interval
        inApp?.globalMinIntervalSeconds = interval
        appContext?.let { HsNotificationChannels.sync(it, config.optJSONArray("android_channels")) }
    }

    /** FCM token'ı alır; değiştiyse (veya kayıt yoksa) sunucuya kaydeder. */
    internal fun refreshFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> scope.launch { onNewToken(token) } }
            .addOnFailureListener { e ->
                HsLog.w("FCM token alınamadı", e)
                reportError("fcm_token_failed", "FCM token alınamadı", e)
            }
    }

    /** FCM yeni/yenilenen token: kayıtlı abonelik varsa PATCH ile güncellenir, yoksa kayıt oluşturulur. */
    internal fun onNewToken(token: String) {
        val store = storage ?: return
        if (token.isBlank()) return
        store.fcmToken = token
        if (options.requiresPrivacyConsent && !store.consentGiven) return
        runCatching { registerSubscription(token) }.onFailure {
            HsLog.w("abonelik kaydı başarısız", it)
            reportError("registration_failed", "push aboneliği kaydedilemedi", it)
        }
    }

    // --- SDK hata raporları ---

    /**
     * SDK iç hatasını toplu hata raporuna ekler (en iyi çaba; ağ hataları ve tekrarlar atlanır).
     * Mesaj ve bağlam token/kişisel veri içermemelidir (bkz. [HsErrorReporter]).
     */
    internal fun reportError(code: String, message: String, error: Throwable? = null, context: Map<String, Any?> = emptyMap(), level: String = "error") {
        val store = storage ?: return
        val reporter = errorReporter ?: return
        if (options.requiresPrivacyConsent && !store.consentGiven) return
        if (!reporter.record(code, message, error, store.subscriptionId, context, level)) return
        if (!errorFlushScheduled.compareAndSet(false, true)) return
        scope.launch {
            // Kısa bekleme: aynı anda oluşan hatalar tek istekte gider.
            delay(ERROR_FLUSH_DELAY_MS)
            errorFlushScheduled.set(false)
            while (true) {
                val batch = reporter.drain() ?: break
                // Rapor gönderilemezse yeniden raporlanmaz (özyineleme yok).
                runCatching { api?.request("POST", "/errors", batch) }.onFailure { HsLog.d("hata raporu gönderilemedi") }
            }
        }
    }

    private const val ERROR_FLUSH_DELAY_MS = 2_000L

    /** Token'ı sunucuyla eşitler (bkz. [HsTokenSync]); yeni token aynı aboneliğe taşınır. */
    private fun registerSubscription(token: String) = synchronized(registrationLock) { registerSubscriptionLocked(token) }

    private fun registerSubscriptionLocked(token: String) {
        val store = storage ?: return
        val ctx = appContext ?: return
        val api = api ?: return
        // Gizlilik onayı yokken kayıt yapılmaz; token saklanır, onay verilince start() kaydeder.
        if (!networkAllowed()) return
        val user = JSONObject()
        store.externalId?.let { user.put("external_id", it) }
        (store.pendingIdentityHash ?: store.userIdentityHash)?.let { user.put("identity_hash", it) }
        // Anonim cihazda önce e-posta/SMS eklendiyse push aboneliği de aynı kullanıcıya bağlanır.
        if (store.externalId == null) {
            (store.contacts("email").entries.firstOrNull() ?: store.contacts("sms").entries.firstOrNull())?.let { (address, subId) ->
                user.put("subscription_id", subId)
                // Bağlanan e-posta/SMS aboneliğinin sahiplik kanıtı: kanonik adresin özeti (ADR-0016).
                user.put("token_hash", HsOwnership.tokenHash(address))
            }
        }
        val sync = HsTokenSync { method, path, body -> api.request(method, path, body) }
        val device = deviceInfo(ctx)
        when (val result = sync.sync(store.subscriptionId, store.registeredToken, token, device, user)) {
            HsTokenSyncResult.Unchanged -> return
            HsTokenSyncResult.Updated -> {
                store.registeredToken = token
                store.serverPushCapable = device.optBoolean("push_capable")
            }
            is HsTokenSyncResult.Created -> {
                store.subscriptionId = result.subscriptionId
                store.userId = result.userId
                store.externalId = result.externalId
                store.registeredToken = token
                store.pendingIdentityHash = null
                store.identitySyncPending = false
                store.serverPushCapable = device.optBoolean("push_capable")
                // Yeni abonelik varsayılan olarak opted_in; kullanıcı daha önce optOut yaptıysa aşağıda PATCH edilir.
                store.serverOptedIn = true
                runCatching { reconcileSubscription() }.onFailure { HsLog.w("opt-out durumu yeni aboneliğe yazılamadı", it) }
                inApp?.onSubscriptionChanged()
            }
        }
        notifySubscription()
        if (queue?.flush() == false) scheduleFlush()
    }

    /** Uygulama sürümü (PackageInfo.versionName). */
    private fun appVersion(ctx: Context): String? =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()

    private fun deviceInfo(ctx: Context): JSONObject = HsEvents.deviceInfo(
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        osVersion = Build.VERSION.RELEASE,
        appVersion = appVersion(ctx),
        sdkVersion = BuildConfig.SDK_VERSION,
        locale = Locale.getDefault().toLanguageTag(),
        timezone = TimeZone.getDefault().id,
        pushCapable = Notifications.permission,
    )

    /**
     * Sunucudaki `device.push_capable` ve `opted_in` değerlerini yereldekiyle eşitler: yalnızca son başarıyla yazılan
     * değerden farklıysa `PATCH /subscriptions/{id}` gönderilir. İzin uygulama kapalıyken ayarlardan değiştirilse veya
     * değişiklik onay beklenirken olsa da sunucu (push_capable=false → `pending`) bir sonraki başlatmada güncellenir.
     */
    private fun reconcileSubscription() {
        val store = storage ?: return
        val ctx = appContext ?: return
        val api = api ?: return
        val subId = store.subscriptionId ?: return
        if (!networkAllowed()) return
        val pushCapable = Notifications.permission
        val optedIn = store.optedIn
        if (store.serverPushCapable == pushCapable && store.serverOptedIn == optedIn) return
        val body = JSONObject().put("device", deviceInfo(ctx)).put("opted_in", optedIn)
        api.request("PATCH", "/subscriptions/$subId", HsOwnership.withProof(body, store.registeredToken))
        store.serverPushCapable = pushCapable
        store.serverOptedIn = optedIn
    }

    /**
     * In-app ve bildirim kutusu çağrılarında kullanılan abonelik: push aboneliği, yoksa (FCM kullanılamıyorsa)
     * kayıtlı ilk e-posta/SMS aboneliği. Gizlilik onayı bekleniyorsa null.
     */
    internal fun engagementSubscriptionId(): String? {
        val store = storage ?: return null
        if (options.requiresPrivacyConsent && !store.consentGiven) return null
        return store.subscriptionId ?: store.contacts("email").values.firstOrNull() ?: store.contacts("sms").values.firstOrNull()
    }

    /** E-posta/SMS aboneliği oluşturur (kullanıcı kimliği varsa ona bağlanır). */
    private fun addContact(channel: String, address: String) {
        val store = storage ?: return
        val api = api ?: return
        if (store.contacts(channel).containsKey(address)) return
        val device = JSONObject()
            .put("sdk_version", BuildConfig.SDK_VERSION)
            .put("sdk_platform", "android")
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("timezone", TimeZone.getDefault().id)
        appContext?.let { appVersion(it) }?.let { device.put("app_version", it) }
        val body = JSONObject().put("channel", channel).put("token", address).put("device", device).put("ownership_proof", true)
        HsContacts.registrationUser(store.externalId, store.userIdentityHash, engagementSubscriptionId())?.let { user ->
            // Cihaz aboneliğine bağlamada hedef aboneliğin sahiplik kanıtı (ADR-0016).
            user.optString("subscription_id").takeIf { it.isNotEmpty() }
                ?.let { ownershipToken(store, it) }
                ?.let { user.put("token_hash", HsOwnership.tokenHash(it)) }
            body.put("user", user)
        }
        val result = api.request("POST", "/subscriptions", body)
        val hadEngagementSubscription = engagementSubscriptionId() != null
        store.putContact(channel, address, result.getString("subscription_id"))
        // Push aboneliği yoksa kullanıcı kimliği bu abonelikten alınır (inbox/in-app için).
        if (store.subscriptionId == null && store.userId == null) store.userId = result.optString("user_id").ifBlank { null }
        if (!hadEngagementSubscription) inApp?.onSubscriptionChanged()
    }

    /** Bu cihazdaki aboneliğin kayıtlı token'ı (push) veya kanonik adresi (e-posta/SMS); sahiplik kanıtı için. */
    private fun ownershipToken(store: HsStorage, subscriptionId: String): String? =
        if (subscriptionId == store.subscriptionId) store.registeredToken
        else listOf("email", "sms").firstNotNullOfOrNull { channel ->
            store.contacts(channel).entries.firstOrNull { it.value == subscriptionId }?.key
        }

    private fun removeContact(channel: String, address: String) {
        val store = storage ?: return
        val api = api ?: return
        val subId = store.contacts(channel)[address] ?: return
        try {
            // Kanıt: kanonik adresin özeti; kimlikli kullanıcıda identity_hash de (doğrulama zorunlu app'ler).
            api.request("DELETE", "/subscriptions/$subId" + HsOwnership.query(address, store.externalId?.let { store.userIdentityHash }))
        } catch (e: HsApiException) {
            if (e.status != 404) throw e // 404: abonelik zaten silinmiş
        }
        store.removeContact(channel, address)
    }

    /** Login sonrası e-posta/SMS abonelikleri de aynı kullanıcıya bağlanır (anonim kullanıcı birleştirilir). */
    private fun linkContacts(externalId: String, identityHash: String?): JSONObject? {
        val store = storage ?: return null
        val api = api ?: return null
        var first: JSONObject? = null
        for (channel in listOf("email", "sms")) {
            for ((address, subId) in store.contacts(channel)) {
                runCatching {
                    // Kanıt: kanonik adresin özeti (ADR-0016).
                    api.request("POST", "/subscriptions/$subId/login", HsOwnership.loginBody(externalId, identityHash, address))
                }.onSuccess { if (first == null) first = it }.onFailure {
                    HsLog.w("e-posta/SMS aboneliği kullanıcıya bağlanamadı", it)
                    reportError("contact_link_failed", "e-posta/SMS aboneliği kullanıcıya bağlanamadı", it)
                }
            }
        }
        return first
    }

    // --- Kuyruk ---

    internal fun enqueue(op: JSONObject) {
        val q = queue ?: return
        q.enqueue(op)
        // Gizlilik onayı yokken işlem yalnızca kalıcı kuyruğa yazılır; onay verilince start() gönderir.
        if (!networkAllowed()) return
        scope.launch { if (!q.flush()) scheduleFlush() }
    }

    /** Ağ gelince WorkManager ile ertelenmiş flush. */
    private fun scheduleFlush() {
        val ctx = appContext ?: return
        if (!networkAllowed()) return
        val request = OneTimeWorkRequestBuilder<FlushWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(FLUSH_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private const val FLUSH_WORK = "herosignal-flush"

    /** WorkManager flush'ı; onay yokken yapılacak iş yoktur (yeniden deneme planlanmaz). */
    internal fun flushQueue(): Boolean = if (!networkAllowed()) true else queue?.flush() ?: true

    private fun perform(op: JSONObject) {
        val store = storage ?: return
        val api = api ?: return
        when (op.getString("type")) {
            "tags", "properties" -> {
                val userId = store.userId ?: throw HsDeferredException("kullanıcı yok")
                val body = if (op.getString("type") == "tags") JSONObject().put("tags", op.getJSONObject("tags")) else JSONObject().put("properties", op.getJSONObject("properties"))
                store.userIdentityHash?.let { body.put("identity_hash", it) }
                val result = api.request("PATCH", "/users/$userId", body)
                val tags = result.optJSONObject("tags") ?: JSONObject()
                store.tags = tags.keys().asSequence().associateWith { tags.getString(it) }
            }
            "event", "event_batch" -> {
                // Abonelik yoksa olay atılmaz: kayıt tamamlanınca (registerSubscription → flush) gönderilir.
                val subId = engagementSubscriptionId() ?: throw HsDeferredException("abonelik yok")
                val ops = if (op.getString("type") == "event") listOf(op) else {
                    val arr = op.getJSONArray("events")
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                }
                // Sahiplik kanıtı (ADR-0016 Karar 8): kayıtlı token'ın/kanonik adresin özeti ve kimlikli kullanıcıda identity_hash.
                val tokenHash = ownershipToken(store, subId)?.let(HsOwnership::tokenHash)
                val events = HsEvents.items(ops, subId, tokenHash, store.externalId?.let { store.userIdentityHash })
                api.request("POST", "/events", JSONObject().put("events", events))
            }
            "session_start", "session_end" -> {
                val subId = engagementSubscriptionId() ?: throw HsDeferredException("abonelik yok")
                val body = JSONObject().put("subscription_id", subId).put("sdk_version", BuildConfig.SDK_VERSION)
                // Oturumda uygulama sürümü de güncellenir (docs/plan/06 "Token yaşam döngüsü").
                appContext?.let { ctx -> appVersion(ctx)?.let { body.put("app_version", it) } }
                if (op.has("duration_seconds")) body.put("duration_seconds", op.getInt("duration_seconds"))
                // Oturum cihaz sürümünü de yazdığı için sahiplik kanıtı eklenir (ADR-0016).
                api.request("POST", "/sessions", HsOwnership.withProof(body, ownershipToken(store, subId), store.externalId?.let { store.userIdentityHash }))
            }
            "alias_add" -> {
                val userId = store.userId ?: throw HsDeferredException("kullanıcı yok")
                val body = JSONObject().put("identity", op.getJSONObject("aliases"))
                store.userIdentityHash?.let { body.put("identity_hash", it) }
                api.request("PATCH", "/users/$userId/identity", body)
            }
            "alias_remove" -> {
                val userId = store.userId ?: throw HsDeferredException("kullanıcı yok")
                val label = URLEncoder.encode(op.getString("label"), "UTF-8")
                val query = store.userIdentityHash?.let { "?identity_hash=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
                api.request("DELETE", "/users/$userId/identity/$label$query")
            }
            "contact_add" -> addContact(op.getString("channel"), op.getString("address"))
            "contact_remove" -> removeContact(op.getString("channel"), op.getString("address"))
            "in_app_event" -> inApp?.send(op)
            "received" -> api.request("POST", "/deliveries/${op.getString("delivery_id")}/received", JSONObject())
            "dismissed" -> api.request("POST", "/deliveries/${op.getString("delivery_id")}/dismissed", JSONObject())
            "opened" -> {
                val body = JSONObject()
                op.optString("button_id").takeIf { it.isNotBlank() }?.let { body.put("button_id", it) }
                api.request("POST", "/deliveries/${op.getString("delivery_id")}/opened", body)
            }
        }
    }

    // --- Oturum ---

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            sessionEndJob?.cancel()
            sessionEndJob = null
            val now = System.currentTimeMillis()
            sessionTracker?.onForeground(now)?.let { transition ->
                transition.endedDurationSeconds?.let(::enqueueSessionEnd)
                if (transition.started) enqueue(JSONObject().put("type", "session_start").put("at", now))
            }
            inApp?.onSessionStart()
            // Dinleyici yoksa (ör. soğuk başlangıçta köprü henüz bağlanmadı) tıklama addClickListener'a kadar saklanır.
            if (clickListeners.isNotEmpty()) pendingClick?.let { click -> pendingClick = null; clickListeners.forEach { it(click) } }
            // Kullanıcı izni sistem ayarlarından değiştirmiş olabilir.
            checkPermissionChange()
        }
        override fun onStop(owner: LifecycleOwner) {
            inApp?.onSessionEnd()
            val tracker = sessionTracker ?: return
            if (!tracker.onBackground(System.currentTimeMillis())) return
            // Oturum arka planda 30 sn kalınca biter; süre (`usage_seconds`, ADR-0019 §8) `POST /sessions { duration_seconds }`
            // ile gider. Süreç bu arada ölürse bir sonraki başlatmada (initialize/onStart) kapatılır.
            sessionEndJob = scope.launch {
                delay(HsSessionTracker.WINDOW_MS)
                tracker.expire(System.currentTimeMillis())?.let(::enqueueSessionEnd)
            }
        }
    }

    private fun enqueueSessionEnd(durationSeconds: Int) =
        enqueue(JSONObject().put("type", "session_end").put("duration_seconds", durationSeconds).put("at", System.currentTimeMillis()))

    // --- Teslimat geri bildirimi (servis/receiver çağırır) ---

    internal fun reportReceived(deliveryId: String) = enqueue(JSONObject().put("type", "received").put("delivery_id", deliveryId).put("at", System.currentTimeMillis()))

    /** Kullanıcı bildirimi kaydırarak/temizleyerek kapattı (deleteIntent). */
    internal fun reportDismissed(deliveryId: String) = enqueue(JSONObject().put("type", "dismissed").put("delivery_id", deliveryId).put("at", System.currentTimeMillis()))

    internal fun reportOpened(notification: HsNotification, actionId: String?) {
        notification.deliveryId?.let { enqueue(JSONObject().put("type", "opened").put("delivery_id", it).put("button_id", actionId ?: "").put("at", System.currentTimeMillis())) }
        val event = HsClickEvent(notification, actionId, notification.urlFor(actionId))
        if (clickListeners.isEmpty()) pendingClick = event else clickListeners.forEach { it(event) }
    }

    /** Ön plan dinleyicilerine sorar; dönen olay `prevented` ise bildirim (şimdilik) gösterilmez. */
    internal fun dispatchForeground(notification: HsNotification): HsForegroundEvent {
        val event = HsForegroundEvent(notification)
        foregroundListeners.forEach { it(event) }
        return event
    }

    /** Ertelenmiş gösterim (HsForegroundEvent.display); resim indirme nedeniyle arka planda çalışır. */
    internal fun displayNotification(notification: HsNotification) {
        val ctx = appContext ?: return
        scope.launch {
            runCatching { HeroSignalMessagingService.show(ctx, notification) }.onFailure {
                HsLog.w("bildirim gösterilemedi", it)
                reportError("display_failed", "bildirim gösterilemedi", it)
            }
        }
    }

    private fun notifySubscription() {
        val s = User.pushSubscription.state
        subscriptionObservers.forEach { it(s) }
    }

    /**
     * Etkin izni yeniden ölçer; değiştiyse izin gözlemcileri çağrılır, sunucudaki cihaz bilgisi (`push_capable`)
     * güncellenir ve abonelik gözlemcileri bilgilendirilir. Uygulama ön plana geldiğinde çalışır.
     */
    private fun checkPermissionChange() {
        if (storage == null) return
        val granted = Notifications.permission
        if (permissionObservers.update(granted)) {
            syncPermissionState(granted)
            notifySubscription()
        }
    }

    /** İzin durumunu sunucuya yansıtır: kayıt varsa cihaz bilgisi PATCH edilir, yoksa izin verildiyse kayıt oluşturulur. */
    private fun syncPermissionState(granted: Boolean) {
        scope.launch {
            val store = storage ?: return@launch
            if (!networkAllowed()) return@launch
            val subId = store.subscriptionId
            runCatching {
                // Kayıt varsa `push_capable` sunucudakinden farklıysa PATCH edilir (false → abonelik `pending`).
                if (subId != null) reconcileSubscription()
                else if (granted) store.fcmToken?.let { registerSubscription(it) }
            }.onFailure {
                HsLog.w("izin sonrası abonelik güncellenemedi", it)
                reportError("subscription_update_failed", "izin sonrası abonelik güncellenemedi", it)
            }
            notifySubscription()
        }
    }

    // --- Public API ---

    @JvmStatic
    @JvmOverloads
    fun login(externalId: String, identityHash: String? = null) {
        val store = storage ?: error("HeroSignal.initialize önce çağrılmalı")
        require(externalId.isNotBlank()) { "externalId gerekli" }
        if (identityVerificationRequired && identityHash == null) error("Bu app için identityHash zorunlu")
        scope.launch {
            if (!networkAllowed()) {
                // Gizlilik onayı yokken ağ çağrısı yapılmaz: kimlik yerelde tutulur, onay verilince start() eşitler.
                store.externalId = externalId
                store.pendingIdentityHash = identityHash
                store.userIdentityHash = identityHash
                store.identitySyncPending = true
                inApp?.reset()
                return@launch
            }
            val subId = store.subscriptionId
            if (subId != null) {
                runCatching { loginSubscription(store, subId, externalId, identityHash) }.onFailure {
                    HsLog.e("login başarısız", it)
                    reportError("login_failed", "login başarısız", it)
                }
                linkContacts(externalId, identityHash)
            } else {
                store.externalId = externalId
                store.pendingIdentityHash = identityHash
                store.userIdentityHash = identityHash
                // Push aboneliği yoksa kullanıcı e-posta/SMS aboneliği üzerinden bağlanır.
                linkContacts(externalId, identityHash)?.let { result ->
                    store.userId = result.getString("user_id")
                    val tags = result.optJSONObject("tags") ?: JSONObject()
                    store.tags = tags.keys().asSequence().associateWith { tags.getString(it) }
                }
                store.fcmToken?.let { token ->
                    runCatching { registerSubscription(token) }.onFailure {
                        HsLog.w("abonelik kaydı başarısız", it)
                        reportError("registration_failed", "push aboneliği kaydedilemedi", it)
                    }
                }
            }
            inApp?.reset()
            inApp?.onSubscriptionChanged()
            queue?.flush()
        }
    }

    @JvmStatic
    fun logout() {
        val store = storage ?: return
        scope.launch {
            if (!networkAllowed()) {
                // Onay yokken yalnızca yerel kimlik temizlenir; onay verilince start() sunucudaki aboneliği ayırır.
                store.clearIdentity()
                store.identitySyncPending = true
                inApp?.reset()
                return@launch
            }
            val subId = store.subscriptionId
            if (subId != null) {
                runCatching { logoutSubscription(store, subId) }.onFailure {
                    HsLog.e("logout başarısız", it)
                    reportError("logout_failed", "logout başarısız", it)
                }
            } else store.clearIdentity()
            inApp?.reset()
            inApp?.onSubscriptionChanged()
        }
    }

    /** `POST /subscriptions/{id}/login`; hata fırlatır (çağıran raporlar). */
    private fun loginSubscription(store: HsStorage, subId: String, externalId: String, identityHash: String?) {
        val body = HsOwnership.loginBody(externalId, identityHash, store.registeredToken)
        val result = api!!.request("POST", "/subscriptions/$subId/login", body)
        store.userId = result.getString("user_id")
        store.externalId = result.optString("external_id").ifBlank { null }
        store.userIdentityHash = identityHash
        val tags = result.optJSONObject("tags") ?: JSONObject()
        store.tags = tags.keys().asSequence().associateWith { tags.getString(it) }
    }

    /** `POST /subscriptions/{id}/logout`; hata fırlatır (çağıran raporlar). */
    private fun logoutSubscription(store: HsStorage, subId: String) {
        val proof = HsOwnership.withProof(JSONObject(), store.registeredToken, store.externalId?.let { store.userIdentityHash })
        val result = api!!.request("POST", "/subscriptions/$subId/logout", proof)
        store.clearIdentity()
        store.userId = result.getString("user_id")
    }

    /**
     * Onay beklenirken yapılan login/logout'u sunucuya yansıtır. Abonelik henüz yoksa kimlik ilk kayıtta
     * (registerSubscription `user.external_id`) gönderildiğinden ayrıca istek gerekmez. Başarısızlıkta bekleme sürer.
     */
    private fun syncPendingIdentity() {
        val store = storage ?: return
        if (!store.identitySyncPending || !networkAllowed()) return
        val subId = store.subscriptionId
        if (subId != null) {
            val externalId = store.externalId
            if (externalId != null) {
                loginSubscription(store, subId, externalId, store.userIdentityHash)
                linkContacts(externalId, store.userIdentityHash)
            } else {
                logoutSubscription(store, subId)
            }
            inApp?.reset()
            inApp?.onSubscriptionChanged()
        }
        store.identitySyncPending = false
    }

    object User {
        @JvmStatic fun addTag(key: String, value: String) = addTags(mapOf(key to value))
        @JvmStatic fun addTags(tags: Map<String, String>) = enqueue(JSONObject().put("type", "tags").put("tags", JSONObject(tags)).put("at", System.currentTimeMillis()))
        @JvmStatic fun removeTag(key: String) = removeTags(listOf(key))
        @JvmStatic fun removeTags(keys: List<String>) {
            val json = JSONObject()
            keys.forEach { json.put(it, JSONObject.NULL) }
            enqueue(JSONObject().put("type", "tags").put("tags", json).put("at", System.currentTimeMillis()))
        }
        @JvmStatic fun getTags(): Map<String, String> = storage?.tags ?: emptyMap()

        /** Kullanıcıya alias ekler (ör. `addAlias("crm_id", "42")`); çevrimdışıyken kuyrukta bekler. */
        @JvmStatic fun addAlias(label: String, id: String) = addAliases(mapOf(label to id))

        /** Birden çok alias'ı tek istekte ekler (en fazla 20; etiketler küçük harf/rakam/_). */
        @JvmStatic fun addAliases(aliases: Map<String, String>) {
            val normalized = HsAliases.normalize(aliases)
            enqueue(JSONObject().put("type", "alias_add").put("aliases", JSONObject(normalized)).put("at", System.currentTimeMillis()))
        }

        @JvmStatic fun removeAlias(label: String) {
            HsAliases.requireLabel(label)
            enqueue(JSONObject().put("type", "alias_remove").put("label", label).put("at", System.currentTimeMillis()))
        }
        @JvmStatic fun setLanguage(code: String) = enqueue(JSONObject().put("type", "properties").put("properties", JSONObject().put("language", code)).put("at", System.currentTimeMillis()))

        /** E-posta aboneliği ekler (küçük harfe çevrilir); geçersiz adreste `IllegalArgumentException`. Çevrimdışıyken kuyrukta bekler. */
        @JvmStatic fun addEmail(email: String) = contactOp("contact_add", "email", requireNotNull(HsContacts.normalizeEmail(email)) { INVALID_EMAIL })

        /** E-posta aboneliğini siler (`DELETE /subscriptions/{id}`). */
        @JvmStatic fun removeEmail(email: String) = contactOp("contact_remove", "email", requireNotNull(HsContacts.normalizeEmail(email)) { INVALID_EMAIL })

        /** SMS aboneliği ekler; numara E.164'e normalize edilir (`+90 (532) 123-45-67` → `+905321234567`, `00` → `+`). */
        @JvmStatic fun addSms(phoneNumber: String) = contactOp("contact_add", "sms", requireNotNull(HsContacts.normalizePhone(phoneNumber)) { INVALID_PHONE })

        /** SMS aboneliğini siler. */
        @JvmStatic fun removeSms(phoneNumber: String) = contactOp("contact_remove", "sms", requireNotNull(HsContacts.normalizePhone(phoneNumber)) { INVALID_PHONE })

        private fun contactOp(type: String, channel: String, address: String) =
            enqueue(JSONObject().put("type", type).put("channel", channel).put("address", address).put("at", System.currentTimeMillis()))

        private const val INVALID_EMAIL = "Geçersiz e-posta adresi."
        private const val INVALID_PHONE = "Geçersiz telefon numarası (E.164 bekleniyor, ör. +905321234567)."

        /** Bu cihazda kaydedilmiş e-posta adresleri. */
        val emails: List<String> get() = storage?.contacts("email")?.keys?.toList().orEmpty()
        /** Bu cihazda kaydedilmiş SMS numaraları. */
        val smsNumbers: List<String> get() = storage?.contacts("sms")?.keys?.toList().orEmpty()

        val externalId: String? get() = storage?.externalId
        val id: String? get() = storage?.userId

        // --- Tercihler (ADR-0019 §4): abonelik sahiplik kanıtıyla `GET/PUT /preferences` ---

        /** Kanıt: etkileşim aboneliği + kayıtlı token'ın (veya kanonik adresin) özeti; kimlikli kullanıcıda identity_hash. */
        private fun preferenceProof(): Triple<String, String?, String?> {
            val store = storage ?: throw IllegalStateException("HeroSignal.initialize önce çağrılmalı")
            val subId = engagementSubscriptionId() ?: throw IllegalStateException("Abonelik henüz kaydedilmedi")
            return Triple(subId, ownershipToken(store, subId)?.let(HsOwnership::tokenHash), store.externalId?.let { store.userIdentityHash })
        }

        private fun getPreferencesBlocking(): HsPreferencesResult {
            val api = api ?: throw IllegalStateException("HeroSignal.initialize önce çağrılmalı")
            val (subId, tokenHash, identityHash) = preferenceProof()
            val query = listOfNotNull(
                "subscription_id=${URLEncoder.encode(subId, "UTF-8")}",
                tokenHash?.let { "token_hash=$it" },
                identityHash?.let { "identity_hash=${URLEncoder.encode(it, "UTF-8")}" },
            ).joinToString("&")
            return HsPreferencesParser.parseResult(api.request("GET", "/preferences?$query"))
        }

        private fun setPreferencesBlocking(preferences: List<HsPreferenceInput>): HsPreferencesResult {
            HsPreferencesParser.validate(preferences)
            val api = api ?: throw IllegalStateException("HeroSignal.initialize önce çağrılmalı")
            val (subId, tokenHash, identityHash) = preferenceProof()
            val body = JSONObject().put("subscription_id", subId).put("preferences", HsPreferencesParser.toJson(preferences))
            tokenHash?.let { body.put("token_hash", it) }
            identityHash?.let { body.put("identity_hash", it) }
            return HsPreferencesParser.parseResult(api.request("PUT", "/preferences", body))
        }

        /** Kullanıcının kategori/kanal tercihleri ve app kategorileri. Abonelik yoksa `IllegalStateException`. */
        suspend fun getPreferences(): HsPreferencesResult = withContext(Dispatchers.IO) { getPreferencesBlocking() }

        /** Tercihleri yazar (1-100 kayıt; `channel` null = tüm kanallar); güncel listeyi döndürür. */
        suspend fun setPreferences(preferences: List<HsPreferenceInput>): HsPreferencesResult = withContext(Dispatchers.IO) { setPreferencesBlocking(preferences) }

        /** Java/köprü sürümü (geri çağrı arka plan iş parçacığında). */
        @JvmStatic fun getPreferences(callback: HsCallback<HsPreferencesResult>) { scope.launch { callback.onResult(runCatching { getPreferencesBlocking() }) } }

        /** Java/köprü sürümü; sonuç istenmiyorsa `callback` null verilir (hata günlüğe yazılır). */
        @JvmStatic
        fun setPreferences(preferences: List<HsPreferenceInput>, callback: HsCallback<HsPreferencesResult>?) {
            scope.launch {
                val result = runCatching { setPreferencesBlocking(preferences) }
                result.exceptionOrNull()?.let { HsLog.w("tercihler kaydedilemedi", it) }
                callback?.onResult(result)
            }
        }

        object pushSubscription {
            val id: String? get() = storage?.subscriptionId
            val token: String? get() = storage?.fcmToken
            val optedIn: Boolean get() = (storage?.optedIn ?: false) && Notifications.permission
            val state: HsPushSubscriptionState get() = HsPushSubscriptionState(id, token, optedIn, Notifications.permission)

            /** Tercih yerelde saklanır; onay yokken sunucuya yazılmaz, onay verilince eşitlenir. */
            @JvmStatic fun optIn() {
                val store = storage ?: return
                store.optedIn = true
                scope.launch {
                    runCatching {
                        if (store.subscriptionId != null) reconcileSubscription() else store.fcmToken?.let { registerSubscription(it) }
                    }.onFailure { HsLog.w("opt-in sunucuya yazılamadı", it) }
                    notifySubscription()
                }
            }

            @JvmStatic fun optOut() {
                val store = storage ?: return
                store.optedIn = false
                scope.launch {
                    runCatching { reconcileSubscription() }.onFailure { HsLog.w("opt-out sunucuya yazılamadı", it) }
                    notifySubscription()
                }
            }

            @JvmStatic fun addObserver(observer: (HsPushSubscriptionState) -> Unit) { subscriptionObservers.add(observer) }
            @JvmStatic fun removeObserver(observer: (HsPushSubscriptionState) -> Unit) { subscriptionObservers.remove(observer) }
        }
    }

    object Notifications {
        const val PERMISSION_REQUEST_CODE = 0x4853

        /**
         * İzin gözlemcisi (Java/köprüler için); `granted` yeni etkin izin durumudur. Ana iş parçacığında çağrılır.
         * Kotlin'de `addPermissionObserver { granted -> }` biçimi de kullanılabilir.
         */
        fun interface PermissionObserver {
            fun onChange(granted: Boolean)
        }

        /**
         * Bildirim izni var mı (Android 13+ POST_NOTIFICATIONS; önceki sürümlerde kanal/sistem ayarı); eşzamanlı okunur.
         * Java: `HeroSignal.Notifications.INSTANCE.getPermission()`.
         */
        val permission: Boolean
            get() {
                val ctx = appContext ?: return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) return false
                return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            }

        /** Android 13+ sistem iznini ister; eski sürümlerde no-op (izin yoksa abonelik `pending` kalır). */
        @JvmStatic fun requestPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permission) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
            }
        }

        /**
         * Activity `onRequestPermissionsResult` içinden çağrılır; izin verildiyse (veya durum değiştiyse) abonelik
         * güncellenir ve durum değiştiyse izin gözlemcileri çağrılır.
         */
        @JvmStatic fun onPermissionResult() {
            val granted = permission
            val changed = permissionObservers.update(granted)
            // Kayıt varsa yalnızca cihaz bilgisi (push_capable) güncellenir; yoksa kayıt oluşturulur.
            if (granted || changed) syncPermissionState(granted)
            notifySubscription()
        }

        /**
         * Etkin bildirim izni değiştiğinde çağrılır: [requestPermission] sonucu ([onPermissionResult]) ve uygulama
         * ön plana döndüğünde (izin sistem ayarlarından değiştirilmiş olabilir). İlk durum bildirilmez; güncel değer
         * için [permission] okunur.
         */
        @JvmStatic fun addPermissionObserver(observer: PermissionObserver) = permissionObservers.add(observer)
        @JvmStatic fun removePermissionObserver(observer: PermissionObserver) = permissionObservers.remove(observer)

        /** Kotlin lambda sürümü (Java'da [PermissionObserver] sürümü görünür; aşırı yükleme belirsizliği olmaz). */
        @JvmSynthetic fun addPermissionObserver(observer: (Boolean) -> Unit) = permissionObservers.add(observer)
        @JvmSynthetic fun removePermissionObserver(observer: (Boolean) -> Unit) = permissionObservers.remove(observer)

        @JvmStatic fun addClickListener(listener: (HsClickEvent) -> Unit) {
            clickListeners.add(listener)
            pendingClick?.let { pendingClick = null; listener(it) }
        }
        @JvmStatic fun removeClickListener(listener: (HsClickEvent) -> Unit) { clickListeners.remove(listener) }
        @JvmStatic fun addForegroundLifecycleListener(listener: (HsForegroundEvent) -> Unit) { foregroundListeners.add(listener) }
        @JvmStatic fun removeForegroundLifecycleListener(listener: (HsForegroundEvent) -> Unit) { foregroundListeners.remove(listener) }

        /**
         * Tepsideki tüm HeroSignal bildirimlerini (grup özetleri dahil) kaldırır. Android'de başlatıcı rozeti/noktası
         * görünen bildirimlerden hesaplandığından rozet de sıfırlanır. Uygulamanın kendi bildirimlerine dokunulmaz.
         */
        @JvmStatic fun clearAll() {
            appContext?.let { HsNotificationTray.clearAll(it) }
        }

        /** Tek bir HeroSignal bildirimini kaldırır ([HsNotification.androidNotificationId]). */
        @JvmStatic fun removeNotification(androidNotificationId: Int) {
            appContext?.let { HsNotificationTray.remove(it, androidNotificationId) }
        }

        /** Aynı grup anahtarına (`android_group`) sahip tüm bildirimleri ve grup özetini kaldırır. */
        @JvmStatic fun removeGroupedNotifications(group: String) {
            appContext?.let { HsNotificationTray.removeGroup(it, group) }
        }
    }

    object Session {
        @JvmStatic
        @JvmOverloads
        fun sendEvent(name: String, properties: Map<String, Any?> = emptyMap(), value: Double? = null) {
            val op = JSONObject().put("type", "event").put("name", name).put("properties", JSONObject(properties))
                .put("occurred_at", java.time.Instant.now().toString()).put("at", System.currentTimeMillis())
            value?.let { op.put("value", it) }
            enqueue(op)
            inApp?.onEvent(name)
        }
    }

    /**
     * In-app mesajlar: liste oturum başında ve en fazla 5 dakikada bir yenilenir; tetikler yerelde değerlendirilir
     * (`session_start`, `Session.sendEvent` adı, [setScreen], [addTrigger] koşulları). Aynı anda tek mesaj gösterilir.
     */
    object InAppMessages {
        @JvmStatic fun addTrigger(key: String, value: String) = addTriggers(mapOf(key to value))

        @JvmStatic fun addTriggers(triggers: Map<String, String>) {
            triggers.forEach { (key, value) ->
                require(key.isNotBlank() && key.length <= 64) { "Trigger anahtarı 1-64 karakter olmalı." }
                require(value.length <= 200) { "Trigger değeri en fazla 200 karakter olabilir." }
            }
            inApp?.addTriggers(triggers)
        }

        @JvmStatic fun removeTrigger(key: String) = removeTriggers(listOf(key))
        @JvmStatic fun removeTriggers(keys: Collection<String>) { inApp?.removeTriggers(keys) }
        @JvmStatic fun clearTriggers() { inApp?.clearTriggers() }
        @JvmStatic fun getTriggers(): Map<String, String> = inApp?.getTriggers() ?: emptyMap()

        /** Geçerli ekran adı (`screen` tetikleri); ekrandan çıkarken `null`. */
        @JvmStatic fun setScreen(name: String?) { inApp?.setScreen(name?.takeIf { it.isNotBlank() }) }

        /** true iken yeni mesaj gösterilmez (tetikler kaydedilmeye devam eder). */
        @JvmStatic var paused: Boolean
            get() = inApp?.paused ?: false
            set(value) { inApp?.paused = value }

        @JvmStatic fun addClickListener(listener: (HsInAppClickEvent) -> Unit) { inApp?.clickListeners?.add(listener) }
        @JvmStatic fun removeClickListener(listener: (HsInAppClickEvent) -> Unit) { inApp?.clickListeners?.remove(listener) }
        @JvmStatic fun addLifecycleListener(listener: HsInAppLifecycleListener) { inApp?.lifecycleListeners?.add(listener) }
        @JvmStatic fun removeLifecycleListener(listener: HsInAppLifecycleListener) { inApp?.lifecycleListeners?.remove(listener) }

        /** Özel çizim katmanı (ör. Flutter köprüsü); `null` varsayılan Dialog çizimine döner. */
        @JvmStatic fun setRenderer(renderer: HsInAppRenderer?) { inApp?.setRenderer(renderer) }
    }

    /**
     * Bildirim kutusu (`add_to_inbox` mesajları). Login'de `identityHash` verildiyse isteklere eklenir.
     * Kotlin'de `suspend` sürümler, Java ve köprüler için [HsCallback] sürümleri kullanılır.
     */
    object Inbox {
        /** Kanıt: etkileşim aboneliğinin kayıtlı token'ı (veya kanonik adresi) özeti ve login'deki identity_hash (ADR-0016). */
        private fun ctx(): HsInboxAuth {
            val store = storage ?: throw IllegalStateException("HeroSignal.initialize önce çağrılmalı")
            val subId = engagementSubscriptionId() ?: throw IllegalStateException("Abonelik henüz kaydedilmedi")
            val userId = store.userId ?: throw IllegalStateException("Kullanıcı henüz kaydedilmedi")
            return HsInboxAuth(userId, subId, ownershipToken(store, subId)?.let(HsOwnership::tokenHash), store.userIdentityHash)
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun base(c: HsInboxAuth) = c.basePath
        private fun query(c: HsInboxAuth) = c.query()
        private fun body(c: HsInboxAuth) = c.body()
        private fun call(method: String, path: String, body: JSONObject? = null): JSONObject =
            api?.request(method, path, body) ?: throw IllegalStateException("HeroSignal.initialize önce çağrılmalı")

        private fun listBlocking(limit: Int, cursor: String?): HsInboxPage {
            require(limit in 1..200) { "limit 1-200 olmalı." }
            val c = ctx()
            val path = "${base(c)}?${query(c)}&limit=$limit" + (cursor?.let { "&cursor=${enc(it)}" } ?: "")
            return HsInboxParser.parsePage(call("GET", path))
        }

        private fun markReadBlocking(id: String): HsInboxItem {
            val c = ctx()
            return HsInboxParser.parseItem(call("POST", "${base(c)}/${enc(id)}/read", body(c)))
        }

        private fun markAllReadBlocking(): Int {
            val c = ctx()
            return call("POST", "${base(c)}/read-all", body(c)).optInt("unread_count", 0)
        }

        private fun archiveBlocking(id: String) {
            val c = ctx()
            call("DELETE", "${base(c)}/${enc(id)}?${query(c)}")
        }

        /** Öğeler (yeniden eskiye), okunmamış sayısı ve sonraki sayfa imleci (`limit` 1-200). */
        suspend fun list(limit: Int = 50, cursor: String? = null): HsInboxPage = withContext(Dispatchers.IO) { listBlocking(limit, cursor) }
        /** Okundu işaretler; güncel öğeyi döndürür. */
        suspend fun markRead(id: String): HsInboxItem = withContext(Dispatchers.IO) { markReadBlocking(id) }
        /** Tümünü okundu işaretler; kalan okunmamış sayısını döndürür. */
        suspend fun markAllRead(): Int = withContext(Dispatchers.IO) { markAllReadBlocking() }
        /** Öğeyi kutudan kaldırır (arşivler). */
        suspend fun archive(id: String): Unit = withContext(Dispatchers.IO) { archiveBlocking(id) }
        /** Okunmamış öğe sayısı. */
        suspend fun unreadCount(): Int = withContext(Dispatchers.IO) { listBlocking(1, null).unreadCount }

        @JvmStatic @JvmOverloads
        fun list(limit: Int = 50, cursor: String? = null, callback: HsCallback<HsInboxPage>) = async(callback) { listBlocking(limit, cursor) }
        @JvmStatic fun markRead(id: String, callback: HsCallback<HsInboxItem>) = async(callback) { markReadBlocking(id) }
        @JvmStatic fun markAllRead(callback: HsCallback<Int>) = async(callback) { markAllReadBlocking() }
        @JvmStatic fun archive(id: String, callback: HsCallback<Unit>) = async(callback) { archiveBlocking(id) }
        @JvmStatic fun unreadCount(callback: HsCallback<Int>) = async(callback) { listBlocking(1, null).unreadCount }

        private fun <T> async(callback: HsCallback<T>, block: () -> T) {
            scope.launch { callback.onResult(runCatching(block)) }
        }
    }

    object Debug {
        @JvmStatic fun setLogLevel(level: HsLog.Level) { HsLog.level = level }
    }
}
