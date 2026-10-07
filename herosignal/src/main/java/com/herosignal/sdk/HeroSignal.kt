package com.herosignal.sdk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    @Volatile private var sessionStartedAt: Long = 0
    @Volatile private var identityVerificationRequired = false
    @Volatile private var pendingClick: HsClickEvent? = null
    @Volatile private var inApp: HsInAppController? = null

    /** In-app akışının SDK'ya bağlantısı (abonelik, API, kuyruk). */
    private val inAppHost = object : HsInAppController.Host {
        override val context: Context? get() = appContext
        override fun subscriptionId(): String? = engagementSubscriptionId()
        override fun request(method: String, path: String, body: JSONObject?): JSONObject =
            api?.request(method, path, body) ?: throw HsApiException(0, "NOT_INITIALIZED", "SDK başlatılmadı")
        override fun enqueue(op: JSONObject) = HeroSignal.enqueue(op)
        override fun addTags(tags: Map<String, String>) = User.addTags(tags)
        override fun runInBackground(block: () -> Unit) { scope.launch { block() } }
        override fun reportError(code: String, message: String, error: Throwable?) = HeroSignal.reportError(code, message, error)
        override fun pendingInAppMessageIds(): Set<String> = storage?.queue.orEmpty()
            .filter { it.optString("type") == "in_app_event" }
            .mapNotNull { it.optString("message_id").ifBlank { null } }
            .toSet()
    }

    val isInitialized: Boolean get() = storage != null

    @JvmStatic
    @JvmOverloads
    fun initialize(context: Context, appId: String, options: Options = Options()) {
        require(appId.isNotBlank()) { "HeroSignal.initialize: appId gerekli" }
        if (this.appId == appId && storage != null) return
        this.appContext = context.applicationContext
        this.appId = appId
        this.options = options
        HsLog.level = options.logLevel
        val store = HsStorage(context, appId)
        storage = store
        api = HsApi(options.apiUrl, appId)
        errorReporter = HsErrorReporter("android", BuildConfig.SDK_VERSION)
        queue = HsQueue(store, { op -> perform(op) }) { op, e ->
            reportError("operation_rejected", "kuyruk işlemi sunucu tarafından reddedildi", e, mapOf("operation" to op.optString("type")))
        }
        inApp = HsInAppController(inAppHost).also { it.attach() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        if (options.requiresPrivacyConsent && !store.consentGiven) {
            HsLog.i("gizlilik onayı bekleniyor")
            return
        }
        start()
    }

    /** KVKK/GDPR onayı. */
    @JvmStatic
    fun setConsentGiven(given: Boolean) {
        val store = storage ?: return
        store.consentGiven = given
        if (given) start() else { queue?.clear(); store.clearIdentity() }
    }

    private fun start() {
        scope.launch {
            runCatching {
                val config = api!!.request("GET", "/config")
                identityVerificationRequired = config.optBoolean("identity_verification_required", false)
            }.onFailure {
                HsLog.w("config alınamadı", it)
                reportError("config_failed", "app yapılandırması alınamadı", it)
            }
            runCatching { refreshFcmToken() }.onFailure {
                HsLog.w("FCM token alınamadı", it)
                reportError("fcm_token_failed", "FCM token alınamadı", it)
            }
            queue?.flush()
        }
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
    private fun registerSubscription(token: String) {
        val store = storage ?: return
        val ctx = appContext ?: return
        val api = api ?: return
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
        when (val result = sync.sync(store.subscriptionId, store.registeredToken, token, deviceInfo(ctx), user)) {
            HsTokenSyncResult.Unchanged -> return
            HsTokenSyncResult.Updated -> store.registeredToken = token
            is HsTokenSyncResult.Created -> {
                store.subscriptionId = result.subscriptionId
                store.userId = result.userId
                store.externalId = result.externalId
                store.registeredToken = token
                store.pendingIdentityHash = null
                if (!store.optedIn) api.request("PATCH", "/subscriptions/${result.subscriptionId}", HsOwnership.withProof(JSONObject().put("opted_in", false), token))
                inApp?.onSubscriptionChanged()
            }
        }
        notifySubscription()
        queue?.flush()
    }

    /** Uygulama sürümü (PackageInfo.versionName). */
    private fun appVersion(ctx: Context): String? =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()

    private fun deviceInfo(ctx: Context): JSONObject {
        val appVersion = appVersion(ctx)
        return JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("os", "Android")
            .put("os_version", Build.VERSION.RELEASE)
            .put("app_version", appVersion ?: JSONObject.NULL)
            .put("sdk_version", BuildConfig.SDK_VERSION)
            .put("sdk_platform", "android")
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("timezone", TimeZone.getDefault().id)
            .put("push_capable", Notifications.permission)
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
        scope.launch { if (!q.flush()) scheduleFlush() }
    }

    /** Ağ gelince WorkManager ile ertelenmiş flush. */
    private fun scheduleFlush() {
        val ctx = appContext ?: return
        val request = OneTimeWorkRequestBuilder<FlushWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("herosignal-flush", ExistingWorkPolicy.KEEP, request)
    }

    internal fun flushQueue(): Boolean = queue?.flush() ?: true

    private fun perform(op: JSONObject) {
        val store = storage ?: return
        val api = api ?: return
        when (op.getString("type")) {
            "tags", "properties" -> {
                val userId = store.userId ?: throw HsApiException(0, "NO_USER", "kullanıcı yok")
                val body = if (op.getString("type") == "tags") JSONObject().put("tags", op.getJSONObject("tags")) else JSONObject().put("properties", op.getJSONObject("properties"))
                store.userIdentityHash?.let { body.put("identity_hash", it) }
                val result = api.request("PATCH", "/users/$userId", body)
                val tags = result.optJSONObject("tags") ?: JSONObject()
                store.tags = tags.keys().asSequence().associateWith { tags.getString(it) }
            }
            "event", "event_batch" -> {
                val subId = store.subscriptionId ?: return
                val ops = if (op.getString("type") == "event") listOf(op) else {
                    val arr = op.getJSONArray("events")
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                }
                val events = org.json.JSONArray()
                for (e in ops) {
                    val event = JSONObject()
                        .put("name", e.getString("name"))
                        .put("properties", e.optJSONObject("properties") ?: JSONObject())
                        .put("occurred_at", e.getString("occurred_at"))
                        .put("subscription_id", subId)
                    if (e.has("value")) event.put("value", e.getDouble("value"))
                    events.put(event)
                }
                api.request("POST", "/events", JSONObject().put("events", events))
            }
            "session_start", "session_end" -> {
                val subId = store.subscriptionId ?: return
                val body = JSONObject().put("subscription_id", subId).put("sdk_version", BuildConfig.SDK_VERSION)
                // Oturumda uygulama sürümü de güncellenir (docs/plan/06 "Token yaşam döngüsü").
                appContext?.let { ctx -> appVersion(ctx)?.let { body.put("app_version", it) } }
                if (op.has("duration_seconds")) body.put("duration_seconds", op.getInt("duration_seconds"))
                // Oturum cihaz sürümünü de yazdığı için sahiplik kanıtı eklenir (ADR-0016).
                api.request("POST", "/sessions", HsOwnership.withProof(body, store.registeredToken, store.externalId?.let { store.userIdentityHash }))
            }
            "alias_add" -> {
                val userId = store.userId ?: throw HsApiException(0, "NO_USER", "kullanıcı yok")
                val body = JSONObject().put("identity", op.getJSONObject("aliases"))
                store.userIdentityHash?.let { body.put("identity_hash", it) }
                api.request("PATCH", "/users/$userId/identity", body)
            }
            "alias_remove" -> {
                val userId = store.userId ?: throw HsApiException(0, "NO_USER", "kullanıcı yok")
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
            sessionStartedAt = System.currentTimeMillis()
            enqueue(JSONObject().put("type", "session_start").put("at", sessionStartedAt))
            inApp?.onSessionStart()
            pendingClick?.let { click -> pendingClick = null; clickListeners.forEach { it(click) } }
        }
        override fun onStop(owner: LifecycleOwner) {
            if (sessionStartedAt == 0L) return
            val duration = ((System.currentTimeMillis() - sessionStartedAt) / 1000).toInt()
            sessionStartedAt = 0
            enqueue(JSONObject().put("type", "session_end").put("duration_seconds", duration).put("at", System.currentTimeMillis()))
        }
    }

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

    // --- Public API ---

    @JvmStatic
    @JvmOverloads
    fun login(externalId: String, identityHash: String? = null) {
        val store = storage ?: error("HeroSignal.initialize önce çağrılmalı")
        require(externalId.isNotBlank()) { "externalId gerekli" }
        if (identityVerificationRequired && identityHash == null) error("Bu app için identityHash zorunlu")
        scope.launch {
            val subId = store.subscriptionId
            if (subId != null) {
                runCatching {
                    val body = HsOwnership.loginBody(externalId, identityHash, store.registeredToken)
                    val result = api!!.request("POST", "/subscriptions/$subId/login", body)
                    store.userId = result.getString("user_id")
                    store.externalId = result.optString("external_id").ifBlank { null }
                    store.userIdentityHash = identityHash
                    val tags = result.optJSONObject("tags") ?: JSONObject()
                    store.tags = tags.keys().asSequence().associateWith { tags.getString(it) }
                }.onFailure {
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
            val subId = store.subscriptionId
            if (subId != null) {
                runCatching {
                    val proof = HsOwnership.withProof(JSONObject(), store.registeredToken, store.externalId?.let { store.userIdentityHash })
                    val result = api!!.request("POST", "/subscriptions/$subId/logout", proof)
                    store.clearIdentity()
                    store.userId = result.getString("user_id")
                }.onFailure {
                    HsLog.e("logout başarısız", it)
                    reportError("logout_failed", "logout başarısız", it)
                }
            } else store.clearIdentity()
            inApp?.reset()
            inApp?.onSubscriptionChanged()
        }
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

        object pushSubscription {
            val id: String? get() = storage?.subscriptionId
            val token: String? get() = storage?.fcmToken
            val optedIn: Boolean get() = (storage?.optedIn ?: false) && Notifications.permission
            val state: HsPushSubscriptionState get() = HsPushSubscriptionState(id, token, optedIn, Notifications.permission)

            @JvmStatic fun optIn() {
                val store = storage ?: return
                store.optedIn = true
                scope.launch {
                    store.subscriptionId?.let { runCatching { api!!.request("PATCH", "/subscriptions/$it", HsOwnership.withProof(JSONObject().put("opted_in", true), store.registeredToken)) } }
                        ?: store.fcmToken?.let { runCatching { registerSubscription(it) } }
                    notifySubscription()
                }
            }

            @JvmStatic fun optOut() {
                val store = storage ?: return
                store.optedIn = false
                scope.launch {
                    store.subscriptionId?.let { runCatching { api!!.request("PATCH", "/subscriptions/$it", HsOwnership.withProof(JSONObject().put("opted_in", false), store.registeredToken)) } }
                    notifySubscription()
                }
            }

            @JvmStatic fun addObserver(observer: (HsPushSubscriptionState) -> Unit) { subscriptionObservers.add(observer) }
            @JvmStatic fun removeObserver(observer: (HsPushSubscriptionState) -> Unit) { subscriptionObservers.remove(observer) }
        }
    }

    object Notifications {
        const val PERMISSION_REQUEST_CODE = 0x4853

        /** Bildirim izni var mı (Android 13+ POST_NOTIFICATIONS; önceki sürümlerde kanal/sistem ayarı). */
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

        /** Activity `onRequestPermissionsResult` içinden çağrılır; izin verildiyse abonelik güncellenir. */
        @JvmStatic fun onPermissionResult() {
            if (permission) scope.launch {
                val store = storage ?: return@launch
                val ctx = appContext ?: return@launch
                val subId = store.subscriptionId
                runCatching {
                    // Kayıt varsa yalnızca cihaz bilgisi (push_capable) güncellenir; yoksa kayıt oluşturulur.
                    if (subId != null) api!!.request("PATCH", "/subscriptions/$subId", HsOwnership.withProof(JSONObject().put("device", deviceInfo(ctx)), store.registeredToken))
                    else store.fcmToken?.let { registerSubscription(it) }
                }.onFailure {
                    HsLog.w("izin sonrası abonelik güncellenemedi", it)
                    reportError("subscription_update_failed", "izin sonrası abonelik güncellenemedi", it)
                }
                notifySubscription()
            }
            notifySubscription()
        }

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
        private class Ctx(val userId: String, val subscriptionId: String, val identityHash: String?)

        private fun ctx(): Ctx {
            val store = storage ?: throw IllegalStateException("HeroSignal.initialize önce çağrılmalı")
            val subId = engagementSubscriptionId() ?: throw IllegalStateException("Abonelik henüz kaydedilmedi")
            val userId = store.userId ?: throw IllegalStateException("Kullanıcı henüz kaydedilmedi")
            return Ctx(userId, subId, store.userIdentityHash)
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun base(c: Ctx) = "/users/${enc(c.userId)}/inbox"
        private fun query(c: Ctx) = "subscription_id=${enc(c.subscriptionId)}" + (c.identityHash?.let { "&identity_hash=${enc(it)}" } ?: "")
        private fun body(c: Ctx) = JSONObject().put("subscription_id", c.subscriptionId).apply { c.identityHash?.let { put("identity_hash", it) } }
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
