package com.pushizo.sdk

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-app mesajı çizen katman. Varsayılan: geçerli Activity üstünde Dialog ([HsInAppDialogRenderer]).
 * Özel çizim (ör. Flutter köprüsü) [Pushizo.InAppMessages.setRenderer] ile kaydedilir. Ana iş parçacığında çağrılır.
 * HTML içerikli mesajlar ([HsInAppDisplay.isHtml]) özel çizime verilmez: yalıtım ve köprü doğrulaması (ADR-0027)
 * yalnızca varsayılan çizimin WebView'ında uygulandığından her zaman varsayılan çizimle gösterilir.
 */
interface HsInAppRenderer {
    /** Mesajı gösterir; etkileşimler [handle] ile bildirilir. false → gösterilemedi (mesaj atlanır). */
    fun show(message: HsInAppMessage, handle: HsInAppHandle): Boolean

    /** SDK mesajın kapatılmasını ister (düğme aksiyonu sonrası). Kapanınca [HsInAppHandle.closed] çağrılmalıdır. */
    fun close(messageId: String)
}

/**
 * Gösterilen mesajın etkileşim kanalı. Çizim katmanı sırasıyla: görünür olunca [displayed], düğmede [buttonClicked],
 * kullanıcı/otomatik kapatmada [dismissed], görünüm tamamen kalkınca [closed] çağırır. Tekrarlanan çağrılar yok sayılır.
 */
class HsInAppHandle internal constructor(private val controller: HsInAppController, val message: HsInAppMessage) {
    private val impressionSent = AtomicBoolean(false)
    private val interacted = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    fun displayed() { if (impressionSent.compareAndSet(false, true)) controller.onDisplayed(this) }

    fun buttonClicked(buttonId: String) {
        if (finished.get() || !interacted.compareAndSet(false, true)) return
        val button = message.display.buttons.firstOrNull { it.id == buttonId } ?: return
        controller.onButtonClicked(this, button)
    }

    fun dismissed() {
        if (finished.get() || !interacted.compareAndSet(false, true)) return
        controller.onDismissed(this)
    }

    fun closed() {
        if (finished.compareAndSet(false, true)) controller.onClosed(this)
    }

    /** HTML köprüsü `click(actionId, url)`: tıklama olayı (`button_id` = [actionId]), dinleyiciler, adres açma, kapanış. */
    internal fun htmlClicked(actionId: String, url: String?) {
        if (finished.get() || !interacted.compareAndSet(false, true)) return
        controller.onHtmlClicked(this, actionId, url)
    }

    /** HTML köprüsü `addTag`; mesaj açık kalır. */
    internal fun htmlAddTag(key: String, value: String) {
        if (finished.get() || !allowSideEffect()) return
        controller.onHtmlAddTag(key, value)
    }

    /** HTML köprüsü `trackEvent`; mesaj açık kalır. */
    internal fun htmlTrackEvent(name: String, properties: Map<String, Any?>) {
        if (finished.get() || !allowSideEffect()) return
        controller.onHtmlTrackEvent(name, properties)
    }

    /** Döngüdeki bir HTML betiği kuyruğu doldurmasın: tek gösterimde en fazla [MAX_HTML_SIDE_EFFECTS] tag/olay. */
    private val sideEffects = AtomicInteger(0)

    private fun allowSideEffect(): Boolean {
        if (sideEffects.incrementAndGet() <= MAX_HTML_SIDE_EFFECTS) return true
        HsLog.w("HTML in-app köprüsü: gösterim başına tag/olay sınırı aşıldı, istek yok sayıldı")
        return false
    }

    private companion object {
        const val MAX_HTML_SIDE_EFFECTS = 50
    }

    /** willDismiss bir kez; kapatma isteği çizim katmanına iletilir. */
    internal fun requestClose() {
        if (closing.compareAndSet(false, true)) controller.onWillDismiss(this)
    }
}

/**
 * In-app akışı: listeyi oturum başında ve en fazla 5 dakikada bir yeniler, tetikleri değerlendirir ([HsInAppRules]),
 * `session_duration` eşikleri için zamanlayıcı kurar, app geneli gösterim aralığını uygular, aynı anda tek mesaj
 * gösterir, gösterim/tıklama/kapatma olaylarını (varyant kimliğiyle) çevrimdışı kuyruğa yazar ve aksiyonları uygular.
 */
internal class HsInAppController(private val host: Host) {
    interface Host {
        val context: Context?
        /** In-app/inbox çağrılarında kullanılacak abonelik (push, yoksa e-posta/SMS). */
        fun subscriptionId(): String?
        /** Aboneliğin sahiplik kanıtı özeti (ADR-0016; push: kayıtlı token, e-posta/SMS: kanonik adres); yoksa null. */
        fun ownershipTokenHash(subscriptionId: String): String?
        fun request(method: String, path: String, body: JSONObject? = null): JSONObject
        fun enqueue(op: JSONObject)
        fun addTags(tags: Map<String, String>)
        /** `outcome` aksiyonu: olay SDK'nın olay API'siyle gönderilir. */
        fun sendEvent(name: String, value: Double?)
        /** HTML köprüsü `trackEvent`: olay özellikleriyle SDK'nın olay API'sine gönderilir. */
        fun trackEvent(name: String, properties: Map<String, Any?>)
        /** `push_prompt` aksiyonu: yerel bildirim izni istemi (Activity yoksa istenemez). */
        fun requestPushPermission(activity: Activity?)
        fun runInBackground(block: () -> Unit)
        fun reportError(code: String, message: String, error: Throwable?)
        /** Kuyrukta henüz gönderilmemiş in-app olayı (gösterim/tıklama/kapatma) bulunan mesaj kimlikleri. */
        fun pendingInAppMessageIds(): Set<String>
        /** Herhangi bir in-app mesajın bu cihazdaki son gösterim zamanı (kalıcı; app geneli aralık için). */
        var lastInAppImpressionAt: Long?
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var messages: List<HsInAppMessage> = emptyList()
    private var loaded = false
    private var fetchedAt = 0L
    private val fetching = AtomicBoolean(false)
    private val locals = HashMap<String, HsLocalImpressions>()
    private var screen: String? = null
    private val triggers = LinkedHashMap<String, String>()
    private var sessionStartAt = 0L
    /** Ön plan oturumunun başlangıcı (`session_duration` için); arka planda 0. */
    private var sessionBeganAt = 0L
    private var current: HsInAppHandle? = null

    @Volatile var paused = false
    /** `in_app.min_interval_seconds` (config): herhangi bir gösterimden sonra bu süre dolmadan yeni mesaj gösterilmez. */
    @Volatile var globalMinIntervalSeconds = 0L
    @Volatile private var customRenderer: HsInAppRenderer? = null
    private var defaultRenderer: HsInAppDialogRenderer? = null
    val clickListeners = CopyOnWriteArraySet<(HsInAppClickEvent) -> Unit>()
    val lifecycleListeners = CopyOnWriteArraySet<HsInAppLifecycleListener>()

    fun setRenderer(renderer: HsInAppRenderer?) { customRenderer = renderer }

    /** HTML mesajı her zaman varsayılan (yalıtılmış WebView) çizimle gösterilir; diğerleri varsa özel çizimle. */
    private fun renderer(message: HsInAppMessage): HsInAppRenderer? =
        if (message.display.isHtml) defaultRenderer() else customRenderer ?: defaultRenderer()

    /** Varsayılan çizim katmanı; özel çizimde de Activity takibi (aksiyon intent'leri, izin istemi) için kurulur. */
    private fun defaultRenderer(): HsInAppDialogRenderer? {
        val ctx = host.context?.applicationContext as? Application ?: return null
        return defaultRenderer ?: HsInAppDialogRenderer(ctx).also { defaultRenderer = it }
    }

    /** Uygulama başlarken çağrılır: Activity takibi için varsayılan çizim katmanı erkenden kurulur. */
    fun attach() { main.post { defaultRenderer() } }

    // --- Durum ---

    fun addTriggers(values: Map<String, String>) {
        if (values.isEmpty()) return
        synchronized(lock) { triggers.putAll(values) }
        evaluate(HsInAppCause.TriggerChange(values.keys))
    }

    fun removeTriggers(keys: Collection<String>) {
        val removed = synchronized(lock) { keys.filter { triggers.remove(it) != null }.toSet() }
        if (removed.isNotEmpty()) evaluate(HsInAppCause.TriggerChange(removed))
    }

    fun clearTriggers() {
        val removed = synchronized(lock) { triggers.keys.toSet().also { triggers.clear() } }
        if (removed.isNotEmpty()) evaluate(HsInAppCause.TriggerChange(removed))
    }

    fun getTriggers(): Map<String, String> = synchronized(lock) { LinkedHashMap(triggers) }

    fun setScreen(name: String?) {
        val changed = synchronized(lock) { (screen != name).also { screen = name } }
        if (changed && name != null) evaluate(HsInAppCause.Screen(name))
    }

    fun onEvent(name: String) = evaluate(HsInAppCause.Event(name))

    // --- Liste ---

    fun onSessionStart() {
        synchronized(lock) {
            sessionStartAt = System.currentTimeMillis()
            sessionBeganAt = sessionStartAt
        }
        refresh(minAgeMs = SESSION_REFRESH_MIN_AGE_MS)
        scheduleSessionDurationTimer()
    }

    /** Uygulama arka plana geçti: oturum süresi sıfırlanır, `session_duration` zamanlayıcısı iptal edilir. */
    fun onSessionEnd() {
        synchronized(lock) {
            sessionStartAt = 0
            sessionBeganAt = 0
        }
        main.removeCallbacks(sessionDurationRunnable)
    }

    /** Geçerli ön plan oturumunun süresi (saniye); arka planda 0. */
    private fun sessionSeconds(): Long {
        val began = synchronized(lock) { sessionBeganAt }
        return if (began == 0L) 0 else (System.currentTimeMillis() - began) / 1000
    }

    private val sessionDurationRunnable = Runnable {
        evaluate(HsInAppCause.SessionDuration)
        scheduleSessionDurationTimer()
    }

    /** Mesajlardaki bir sonraki `session_duration` eşiği için ana iş parçacığında zamanlayıcı kurar (yoksa iptal). */
    private fun scheduleSessionDurationTimer() {
        main.removeCallbacks(sessionDurationRunnable)
        val (began, list) = synchronized(lock) { sessionBeganAt to messages }
        if (began == 0L) return
        val next = HsInAppRules.nextSessionDurationSeconds(list, (System.currentTimeMillis() - began) / 1000) ?: return
        val delay = began + next * 1000 - System.currentTimeMillis()
        main.postDelayed(sessionDurationRunnable, maxOf(delay, 0L))
    }

    /** Abonelik ilk kez oluştuğunda veya kullanıcı değiştiğinde (login/logout) liste hemen yenilenir. */
    fun onSubscriptionChanged() = refresh(minAgeMs = 0)

    private fun refresh(minAgeMs: Long) {
        if (System.currentTimeMillis() - fetchedAt < minAgeMs) { runPendingSessionStart(); return }
        val subId = host.subscriptionId() ?: return
        if (!fetching.compareAndSet(false, true)) return
        host.runInBackground {
            try {
                val json = host.request("GET", listPath(subId, host.ownershipTokenHash(subId)))
                val list = HsInAppParser.parseList(json)
                synchronized(lock) {
                    messages = list
                    loaded = true
                    fetchedAt = System.currentTimeMillis()
                    // Sunucu gönderilmiş olayları (sayaç, etkileşim) içerir; yalnızca kuyrukta olayı bekleyen mesajların yerel durumu korunur.
                    val pending = host.pendingInAppMessageIds()
                    locals.keys.retainAll(pending)
                }
                HsLog.d("in-app listesi yenilendi: ${list.size} mesaj")
                main.post { scheduleSessionDurationTimer() }
            } catch (e: Exception) {
                HsLog.w("in-app listesi alınamadı", e)
                if (e !is HsApiException || !e.retryable) host.reportError("in_app_fetch_failed", "in-app listesi alınamadı", e)
            } finally {
                fetching.set(false)
            }
            runPendingSessionStart()
        }
    }

    /** Oturum başı değerlendirmesi liste yüklendikten sonra (en geç 60 sn içinde) bir kez yapılır. */
    private fun runPendingSessionStart() {
        val run = synchronized(lock) {
            val pending = sessionStartAt > 0 && loaded
            val fresh = System.currentTimeMillis() - sessionStartAt <= SESSION_START_WINDOW_MS
            if (pending) sessionStartAt = 0
            pending && fresh
        }
        if (run) evaluate(HsInAppCause.SessionStart)
    }

    private fun evaluate(cause: HsInAppCause) {
        if (System.currentTimeMillis() - fetchedAt > REFRESH_INTERVAL_MS) refresh(minAgeMs = REFRESH_INTERVAL_MS)
        if (paused) return
        val state = HsInAppState(screen = synchronized(lock) { screen }, triggers = getTriggers(), sessionSeconds = sessionSeconds())
        val selected = synchronized(lock) {
            if (current != null) return
            HsInAppRules.select(
                messages, cause, state, HashMap(locals), System.currentTimeMillis(),
                globalMinIntervalSeconds, host.lastInAppImpressionAt,
            )
        } ?: return
        main.post { present(selected) }
    }

    private fun present(message: HsInAppMessage) {
        if (paused) return
        val handle = synchronized(lock) {
            if (current != null) return
            HsInAppHandle(this, message).also { current = it }
        }
        val renderer = renderer(message)
        lifecycleListeners.forEach { it.onWillDisplay(HsInAppLifecycleEvent(message)) }
        val shown = renderer != null && runCatching { renderer.show(message, handle) }.onFailure {
            HsLog.w("in-app mesajı gösterilemedi", it)
            host.reportError("in_app_render_failed", "in-app mesajı gösterilemedi", it)
        }.getOrDefault(false)
        if (!shown) synchronized(lock) { if (current === handle) current = null }
    }

    // --- Handle geri çağrıları ---

    fun onDisplayed(handle: HsInAppHandle) {
        val id = handle.message.id
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val prev = locals[id] ?: HsLocalImpressions()
            locals[id] = prev.copy(count = prev.count + 1, lastAt = now)
        }
        // App geneli aralık herhangi bir mesajın son gösteriminden ölçülür; uygulama yeniden başlasa da korunur.
        host.lastInAppImpressionAt = now
        record(handle.message, "impression", null)
        main.post { lifecycleListeners.forEach { it.onDidDisplay(HsInAppLifecycleEvent(handle.message)) } }
    }

    /** Tıklama/kapatma yerelde işaretlenir (`stop_after_interaction`). */
    private fun markInteracted(id: String) {
        synchronized(lock) { locals[id] = (locals[id] ?: HsLocalImpressions()).copy(interacted = true) }
    }

    fun onButtonClicked(handle: HsInAppHandle, button: HsInAppBlock.Button) {
        val message = handle.message
        markInteracted(message.id)
        record(message, "click", button.id)
        val url = when (val a = button.action) {
            is HsInAppAction.Url -> a.url
            is HsInAppAction.Deeplink -> a.url
            else -> null
        }
        main.post {
            clickListeners.forEach { runCatching { it(HsInAppClickEvent(message.id, button.id, url)) } }
            when (val a = button.action) {
                is HsInAppAction.Url -> open(a.url, inApp = false)
                is HsInAppAction.Deeplink -> open(a.url, inApp = true)
                is HsInAppAction.Tags -> if (a.tags.isNotEmpty()) host.addTags(a.tags)
                is HsInAppAction.Outcome -> host.sendEvent(a.name, a.value)
                HsInAppAction.PushPrompt -> host.requestPushPermission(defaultRenderer?.currentActivity)
                HsInAppAction.Dismiss -> Unit
            }
            close(handle)
        }
    }

    /** HTML köprüsü tıklaması: düğme tıklamasıyla aynı olay ve dinleyiciler; http(s) tarayıcıda, diğer şema deeplink. */
    fun onHtmlClicked(handle: HsInAppHandle, actionId: String, url: String?) {
        val message = handle.message
        markInteracted(message.id)
        record(message, "click", actionId)
        main.post {
            clickListeners.forEach { runCatching { it(HsInAppClickEvent(message.id, actionId, url)) } }
            if (url != null) open(url, inApp = !HsHtmlBridge.isWebUrl(url))
            close(handle)
        }
    }

    fun onHtmlAddTag(key: String, value: String) {
        main.post { host.addTags(mapOf(key to value)) }
    }

    fun onHtmlTrackEvent(name: String, properties: Map<String, Any?>) {
        main.post { host.trackEvent(name, properties) }
    }

    fun onDismissed(handle: HsInAppHandle) {
        markInteracted(handle.message.id)
        record(handle.message, "dismiss", null)
        main.post { close(handle) }
    }

    private fun close(handle: HsInAppHandle) {
        handle.requestClose()
        val renderer = renderer(handle.message) ?: return handle.closed()
        runCatching { renderer.close(handle.message.id) }.onFailure { handle.closed() }
    }

    fun onWillDismiss(handle: HsInAppHandle) {
        lifecycleListeners.forEach { it.onWillDismiss(HsInAppLifecycleEvent(handle.message)) }
    }

    fun onClosed(handle: HsInAppHandle) {
        synchronized(lock) { if (current === handle) current = null }
        main.post {
            lifecycleListeners.forEach { it.onDidDismiss(HsInAppLifecycleEvent(handle.message)) }
            // Mesaj açıkken eşiği dolan `session_duration` tetikleri (zamanlayıcı o anda atlandı) yeniden değerlendirilir.
            evaluate(HsInAppCause.SessionDuration)
        }
    }

    private fun record(message: HsInAppMessage, type: String, buttonId: String?) {
        val op = JSONObject().put("type", "in_app_event").put("message_id", message.id).put("event", type).put("at", System.currentTimeMillis())
        buttonId?.let { op.put("button_id", it) }
        // Sunucunun seçtiği A/B varyantı olayla birlikte geri gönderilir.
        message.variantId?.let { op.put("variant_id", it) }
        host.enqueue(op)
    }

    /** url → tarayıcı; deeplink → önce uygulamanın kendisi, yoksa şemayı işleyen uygulama. */
    private fun open(url: String, inApp: Boolean) {
        val ctx = defaultRenderer?.currentActivity ?: host.context ?: return
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val candidates = if (inApp) listOf(Intent(base).setPackage(ctx.packageName), base) else listOf(base)
        for (intent in candidates) {
            try {
                ctx.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                HsLog.d("in-app adresi bu hedefle açılamadı")
            }
        }
        HsLog.w("in-app adresi açılamadı: şemayı işleyen uygulama yok")
    }

    /** Kuyruktaki in-app olayı (Pushizo.perform). */
    fun send(op: JSONObject) {
        val subId = host.subscriptionId() ?: return
        val body = eventBody(op, subId, host.ownershipTokenHash(subId))
        host.request("POST", "/in-app/${URLEncoder.encode(op.getString("message_id"), "UTF-8")}/events", body)
    }

    /** Logout/login: kullanıcı değişti; yerel sayaçlar ve liste sıfırlanır. */
    fun reset() {
        synchronized(lock) {
            messages = emptyList()
            loaded = false
            fetchedAt = 0
            locals.clear()
        }
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60_000L
        const val SESSION_REFRESH_MIN_AGE_MS = 30_000L
        const val SESSION_START_WINDOW_MS = 60_000L

        /** SDK'nın çizebildiği içerik yetenekleri (ADR-0027); bildirilmezse sunucu HTML mesaj ve carousel göndermez. */
        internal const val SUPPORTS = "html,carousel"

        /** `GET /in-app` yolu; sahiplik kanıtı (ADR-0016) varsa `token_hash` eklenir. */
        internal fun listPath(subscriptionId: String, tokenHash: String?): String =
            "/in-app?subscription_id=${URLEncoder.encode(subscriptionId, "UTF-8")}" +
                (tokenHash?.let { "&token_hash=${URLEncoder.encode(it, "UTF-8")}" } ?: "") +
                "&supports=$SUPPORTS"

        /** Kuyruktaki olaydan `POST /in-app/{id}/events` gövdesi; kanıt gönderim anındaki abonelikten çözülür. */
        internal fun eventBody(op: JSONObject, subscriptionId: String, tokenHash: String?): JSONObject {
            val body = JSONObject().put("subscription_id", subscriptionId).put("type", op.getString("event"))
            tokenHash?.let { body.put("token_hash", it) }
            op.optString("button_id").takeIf { it.isNotBlank() }?.let { body.put("button_id", it) }
            op.optString("variant_id").takeIf { it.isNotBlank() }?.let { body.put("variant_id", it) }
            return body
        }
    }
}
