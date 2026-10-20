package com.pushizo.sdk

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Canlı sohbet (ADR-0040; `Pushizo.Chat`). Ziyaretçi kimliği cihazda üretilip saklanır; mesajlar sıra numaralıdır,
 * gerçek zamanlı iletim SSE (`chat/stream`) + POST'tur. Akış koparsa üstel geri çekilmeyle yeniden bağlanılır ve arada
 * `after_seq` yoklamasıyla eksikler tamamlanır. Uygulama kapalıyken gelen temsilci mesajı push bildirimiyle
 * (`pushizo_chat`) gelir; okunmamış sayısı yenilenir, bildirime tıklama [addOpenListener] dinleyicilerine iletilir.
 *
 * ```kotlin
 * Pushizo.Chat.start()
 * Pushizo.Chat.addListener(object : HsChatListener { override fun onMessage(message: HsChatMessage) { … } })
 * Pushizo.Chat.startListening()
 * val page = Pushizo.Chat.getMessages()
 * Pushizo.Chat.sendMessage("Merhaba")
 * Pushizo.Chat.markRead(page.messages.last().seq)
 * ```
 *
 * Kotlin'de `suspend` sürümler, Java ve köprüler için [HsCallback] sürümleri (geri çağrı arka plan iş parçacığında)
 * kullanılır. Dinleyiciler ana iş parçacığında çağrılır.
 */
object PushizoChat {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArraySet<HsChatListener>()
    private val openListeners = CopyOnWriteArraySet<HsChatOpenListener>()
    private val seen = HsChatSeen()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()

    @Volatile private var pendingOpen: HsChatPushInfo? = null
    @Volatile private var streamJob: Job? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var restartRequested = false
    @Volatile private var conversationId: String? = null
    @Volatile private var typingSentAt = 0L
    @Volatile private var typingState = false
    @Volatile private var deliveredAckJob: Job? = null

    private const val TYPING_REPEAT_MS = 3_000L
    private const val DELIVERED_ACK_DELAY_MS = 500L
    private const val OP_SEND = "chat_send"
    private const val OP_OFFLINE = "chat_offline"

    private fun store(): HsStorage = Pushizo.storage ?: throw IllegalStateException("Pushizo.initialize önce çağrılmalı")
    private fun api(): HsApi = Pushizo.api ?: throw IllegalStateException("Pushizo.initialize önce çağrılmalı")
    private fun visitor(): HsChatVisitor = HsChatVisitor.ensure(store())

    // --- Durum ---

    /** Bu cihazın ziyaretçi kimliği (henüz sohbet kullanılmadıysa null). */
    @JvmStatic val visitorId: String? get() = Pushizo.storage?.chatVisitorId

    /** Son bilinen okunmamış (temsilci/bot) mesaj sayısı; güncel değer için [refreshUnreadCount]. */
    @JvmStatic val unreadCount: Int get() = Pushizo.storage?.chatUnreadCount ?: 0

    /** Canlı dinleme açık mı ([startListening]). */
    @JvmStatic val isListening: Boolean get() = streamJob?.isActive == true

    // --- Dinleyiciler ---

    @JvmStatic fun addListener(listener: HsChatListener) { listeners.add(listener) }
    @JvmStatic fun removeListener(listener: HsChatListener) { listeners.remove(listener) }

    /**
     * Sohbet bildirimine tıklama dinleyicisi. Dinleyici yokken (soğuk başlangıç) gelen tıklama saklanır ve ilk eklenen
     * dinleyiciye iletilir.
     */
    @JvmStatic fun addOpenListener(listener: HsChatOpenListener) {
        openListeners.add(listener)
        pendingOpen?.let { info -> pendingOpen = null; onMain { listener.onOpen(info) } }
    }
    @JvmStatic fun removeOpenListener(listener: HsChatOpenListener) { openListeners.remove(listener) }

    private fun emit(block: (HsChatListener) -> Unit) {
        if (listeners.isEmpty()) return
        onMain { listeners.forEach { runCatching { block(it) }.onFailure { e -> HsLog.w("sohbet dinleyicisi hata verdi", e) } } }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // --- Kimlik ---

    /**
     * Sohbeti etkinleştirir ve ziyaretçiyi sunucuya tanıtır: giriş yapılmışsa kullanıcıya (`external_id` + kimlik
     * kanıtı), push aboneliği varsa ona (sahiplik kanıtıyla; çevrimdışı mesaj bildirimi için) bağlar. Bundan sonra
     * login/logout ve push aboneliği değişimlerinde bağlama SDK tarafından yenilenir.
     */
    private fun startBlocking(name: String?, email: String?, language: String?): HsChatIdentifyResult {
        val store = store()
        store.chatEnabled = true
        return identifyBlocking(name, email, language, force = true)!!
    }

    /** [force] false iken girdi son başarılı çağrıyla aynıysa istek atılmaz (null döner). */
    private fun identifyBlocking(name: String?, email: String?, language: String?, force: Boolean): HsChatIdentifyResult? {
        val store = store()
        val visitor = visitor()
        val externalId = store.externalId
        val body = HsChatRequests.identifyBody(
            visitor,
            externalId = externalId,
            identityHash = externalId?.let { store.userIdentityHash },
            subscriptionId = store.subscriptionId,
            registeredToken = store.registeredToken,
            name = name,
            email = email,
            language = language,
        )
        val key = HsChatRequests.identifyKey(body)
        if (!force && key == store.chatIdentifyKey) return null
        val result = HsChatParser.identify(api().request("POST", "/chat/identify", body))
        store.chatIdentifyKey = key
        result.conversation?.let { onConversation(it) }
        return result
    }

    /**
     * Ziyaretçi henüz hiç tanıtılmadıysa (ör. logout sonrası yeni ziyaretçi, `start` çağrılmadan gönderim) önce bağlanır;
     * böylece push aboneliği çevrimdışı mesaj bildirimi için bağlı olur. Hata gönderimi engellemez.
     */
    private fun ensureIdentified() {
        if (store().chatIdentifyKey != null) return
        runCatching { identifyBlocking(null, null, null, force = false) }.onFailure { HsLog.d("sohbet ziyaretçisi bağlanamadı") }
    }

    /** Login, logout sonrası veya push aboneliği/token değişince (Pushizo çekirdeği çağırır); sohbet hiç başlatılmadıysa no-op. */
    internal fun onIdentityChanged() {
        val store = Pushizo.storage ?: return
        if (!store.chatEnabled) return
        scope.launch {
            runCatching { identifyBlocking(null, null, null, force = false) }
                .onFailure { HsLog.w("sohbet ziyaretçisi yeniden bağlanamadı", it) }
        }
    }

    /**
     * Logout veya gizlilik onayının geri alınması: canlı dinleme durur, ziyaretçi kimliği silinir (cihazı kullanan
     * sonraki kişi önceki sohbeti görmez). Sohbet etkinse yeni ziyaretçi ilk kullanımda üretilir.
     */
    internal fun onLogout() {
        val store = Pushizo.storage ?: return
        stopListening()
        conversationId = null
        seen.clear()
        val hadUnread = store.chatUnreadCount != 0
        store.clearChatVisitor()
        if (hadUnread) emit { it.onUnreadCountChanged(0) }
    }

    // --- Okuma ---

    private fun configBlocking(language: String?): HsChatConfig {
        val query = language?.trim()?.takeIf { it.isNotEmpty() }?.let { "?language=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return HsChatParser.config(api().request("GET", "/chat/config$query"))
    }

    private fun conversationBlocking(): HsChatConversationState {
        val state = HsChatParser.conversationState(api().request("GET", "/chat/conversation?${visitor().query()}"))
        state.conversation?.let { onConversation(it) }
        setUnread(state.unreadCount)
        return state
    }

    private fun messagesBlocking(afterSeq: Long?, beforeSeq: Long?, limit: Int): HsChatMessagePage {
        val query = HsChatRequests.messagesQuery(visitor(), afterSeq, beforeSeq, limit)
        val page = HsChatParser.page(api().request("GET", "/chat/messages?$query"))
        page.messages.forEach { seen.mark(it.id) }
        // Geçmiş sayfası dışındaki (en yeni / eksik tamamlama) sayfalar akışın kaldığı yeri ilerletir.
        if (beforeSeq == null) page.messages.maxOfOrNull { it.seq }?.let(::advanceLastSeq)
        scheduleDeliveredAck(page.messages)
        return page
    }

    // --- Gönderim ---

    private fun sendBlocking(text: String, optionId: String?, name: String?, email: String?, language: String?): HsChatMessage {
        val clean = HsChatRequests.requireText(text)
        val store = store()
        store.chatEnabled = true
        ensureIdentified()
        val visitor = visitor()
        val op = JSONObject().put("type", OP_SEND).put("visitor_id", visitor.id)
            .put("client_message_id", HsChatVisitor.newClientMessageId()).put("text", clean).put("at", System.currentTimeMillis())
        optionId?.let { op.put("option_id", it) }
        name?.let { op.put("name", it) }
        email?.let { op.put("email", it) }
        language?.let { op.put("language", it) }
        return sendOrQueue(op) { performSend(op) }
    }

    private fun offlineBlocking(email: String, text: String, name: String?, language: String?): HsChatMessage {
        val clean = HsChatRequests.requireText(text)
        val normalized = requireNotNull(HsContacts.normalizeEmail(email)) { "Geçersiz e-posta adresi." }
        val store = store()
        store.chatEnabled = true
        ensureIdentified()
        val visitor = visitor()
        val op = JSONObject().put("type", OP_OFFLINE).put("visitor_id", visitor.id)
            .put("client_message_id", HsChatVisitor.newClientMessageId()).put("text", clean).put("email", normalized)
            .put("at", System.currentTimeMillis())
        name?.let { op.put("name", it) }
        language?.let { op.put("language", it) }
        return sendOrQueue(op) { performOffline(op) }
    }

    /**
     * Doğrudan gönderir; geçici hatada (ağ, 429, 5xx, onay bekleniyor) işlem SDK'nın kalıcı kuyruğuna yazılır ve aynı
     * `client_message_id` ile yeniden denenir (sunucu tekilleştirir). Bu durumda `pending` yerel mesaj döner.
     */
    private fun sendOrQueue(op: JSONObject, send: () -> HsChatMessage): HsChatMessage = try {
        send()
    } catch (e: HsApiException) {
        if (!e.retryable) throw e
        HsLog.i("sohbet mesajı çevrimdışı kuyruğa alındı (${e.code})")
        Pushizo.enqueue(op)
        val pending = HsChatMessage(
            id = null, seq = 0L, clientMessageId = op.getString("client_message_id"), sender = HsChatMessage.SENDER_VISITOR,
            authorName = op.optStringOrNull("name"), text = op.getString("text"), attachments = emptyList(), quickReplies = null,
            status = HsChatMessage.STATUS_PENDING, createdAt = op.optLong("at"),
        )
        emit { it.onMessage(pending) }
        pending
    }

    /** Kuyruktaki sohbet işlemi (Pushizo kuyruğu çağırır). Ziyaretçi değiştiyse (logout) işlem atılır. */
    internal fun performQueued(op: JSONObject) {
        val store = Pushizo.storage ?: return
        if (op.optString("visitor_id") != store.chatVisitorId) {
            HsLog.d("önceki ziyaretçinin bekleyen sohbet mesajı atıldı")
            return
        }
        when (op.getString("type")) {
            OP_SEND -> performSend(op)
            OP_OFFLINE -> performOffline(op)
        }
    }

    private fun performSend(op: JSONObject): HsChatMessage {
        val body = HsChatRequests.sendBody(
            visitor(), op.getString("client_message_id"), op.getString("text"), op.optStringOrNull("option_id"),
            op.optStringOrNull("name"), op.optStringOrNull("email"), op.optStringOrNull("language"),
        )
        return onSendResult(api().request("POST", "/chat/messages", body))
    }

    private fun performOffline(op: JSONObject): HsChatMessage {
        val body = HsChatRequests.offlineBody(
            visitor(), op.getString("client_message_id"), op.getString("email"), op.getString("text"),
            op.optStringOrNull("name"), op.optStringOrNull("language"),
        )
        return onSendResult(api().request("POST", "/chat/offline", body))
    }

    private fun attachmentBlocking(bytes: ByteArray, fileName: String, mimeType: String, text: String?): HsChatMessage {
        require(bytes.isNotEmpty()) { "Dosya boş olamaz." }
        require(bytes.size <= HsChatLimits.MAX_ATTACHMENT_BYTES) { "Dosya ${HsChatLimits.MAX_ATTACHMENT_BYTES / 1024 / 1024} MB sınırını aşıyor." }
        val mime = mimeType.substringBefore(';').trim().lowercase()
        require(mime in HsChatLimits.ATTACHMENT_TYPES) { "Desteklenmeyen dosya türü: $mime (PNG, JPEG, WebP, GIF, PDF, düz metin)." }
        store().chatEnabled = true
        ensureIdentified()
        val visitor = visitor()
        val multipart = HsMultipart()
            .field("visitor_id", visitor.id)
            .field("visitor_token", visitor.token)
            .field("client_message_id", HsChatVisitor.newClientMessageId())
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { multipart.field("text", HsChatRequests.requireText(it)) }
        multipart.file("file", fileName.ifBlank { "file" }, mime, bytes)
        val body = multipart.build()
        return onSendResult(api().requestRaw("POST", "/chat/attachments", multipart.contentType, body))
    }

    private fun attachmentFromUri(context: Context, uri: Uri, name: String?, mimeType: String?, text: String?): HsChatMessage {
        val resolver = context.contentResolver
        val mime = mimeType ?: resolver.getType(uri) ?: throw IllegalArgumentException("Dosya türü belirlenemedi; mimeType verin.")
        val fileName = name ?: runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            // Sınırın bir bayt fazlası okunur: büyük dosya belleğe tamamen alınmadan reddedilir.
            val limit = HsChatLimits.MAX_ATTACHMENT_BYTES.toInt() + 1
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (buffer.size() < limit) {
                val n = input.read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
                if (n < 0) break
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        } ?: throw IOException("Dosya açılamadı: $uri")
        return attachmentBlocking(bytes, fileName, mime, text)
    }

    private fun onSendResult(json: JSONObject): HsChatMessage {
        val message = HsChatParser.message(json.getJSONObject("message"))
        val hadConversation = conversationId != null
        HsChatParser.conversation(json.optJSONObject("conversation"))?.let { onConversation(it) }
        if (seen.mark(message.id)) emit { it.onMessage(message) }
        // İlk mesaj konuşmayı açtı: konuşmasız akış yalnızca kalp atışı taşır, yeniden kurulur.
        if (!hadConversation && conversationId != null) restartStream()
        return message
    }

    // --- Okundu / teslim / yazıyor ---

    private fun markReadBlocking(seq: Long): Int {
        require(seq >= 0) { "seq negatif olamaz." }
        val state = HsChatParser.conversationState(api().request("POST", "/chat/ack", HsChatRequests.ackBody(visitor(), seq, null)))
        state.conversation?.let { onConversation(it) }
        setUnread(state.unreadCount)
        return state.unreadCount
    }

    /** Temsilci/bot mesajları cihaza ulaştı: teslim işaretçisi kısa gecikmeyle ve yalnızca ileri giderek gönderilir. */
    private fun scheduleDeliveredAck(messages: List<HsChatMessage>) {
        val max = messages.filter { !it.isFromVisitor }.maxOfOrNull { it.seq } ?: return
        val store = Pushizo.storage ?: return
        if (max <= store.chatDeliveredSeq) return
        synchronized(lock) {
            deliveredAckJob?.cancel()
            deliveredAckJob = scope.launch {
                delay(DELIVERED_ACK_DELAY_MS)
                val target = maxOf(max, store.chatDeliveredSeq)
                runCatching {
                    api().request("POST", "/chat/ack", HsChatRequests.ackBody(visitor(), null, target))
                    if (target > store.chatDeliveredSeq) store.chatDeliveredSeq = target
                }.onFailure { HsLog.d("sohbet teslim bilgisi gönderilemedi") }
            }
        }
    }

    private fun typingBlocking(typing: Boolean) {
        if (conversationId == null) return
        val now = System.currentTimeMillis()
        // "Yazıyor" en sık 3 sn'de bir tekrarlanır; "durdu" yalnızca önce "yazıyor" gönderildiyse gider.
        if (typing && typingState && now - typingSentAt < TYPING_REPEAT_MS) return
        if (!typing && !typingState) return
        typingState = typing
        typingSentAt = now
        api().request("POST", "/chat/typing", HsChatRequests.typingBody(visitor(), typing))
    }

    // --- Okunmamış ---

    private fun setUnread(count: Int) {
        val store = Pushizo.storage ?: return
        val value = count.coerceAtLeast(0)
        if (store.chatUnreadCount == value) return
        store.chatUnreadCount = value
        emit { it.onUnreadCountChanged(value) }
    }

    private fun onConversation(conversation: HsChatConversation) {
        conversationId = conversation.id
        setUnread(conversation.unreadCount)
    }

    private fun advanceLastSeq(seq: Long) {
        val store = Pushizo.storage ?: return
        synchronized(lock) { if (seq > store.chatLastSeq) store.chatLastSeq = seq }
    }

    // --- Push ---

    /** Push alındı (servis çağırır): sohbet bildirimiyse okunmamış sayısı arka planda yenilenir. */
    internal fun onPushReceived(data: Map<String, String>) {
        HsChatPush.parse(data) ?: return
        val store = Pushizo.storage ?: return
        if (store.chatVisitorId == null) return
        scope.launch { runCatching { conversationBlocking() }.onFailure { HsLog.d("sohbet okunmamış sayısı yenilenemedi") } }
    }

    /** Bildirime tıklandı (çekirdek çağırır): sohbet bildirimiyse açma dinleyicilerine iletilir. */
    internal fun onNotificationOpened(notification: HsNotification) {
        val info = HsChatPush.parse(notification.data) ?: return
        if (openListeners.isEmpty()) {
            pendingOpen = info
            return
        }
        onMain { openListeners.forEach { it.onOpen(info) } }
    }

    // --- Canlı akış ---

    /**
     * Canlı dinlemeyi başlatır (SSE). Kopan bağlantı 1 sn'den 30 sn'ye kadar üstel geri çekilmeyle yeniden kurulur;
     * her denemeden önce `after_seq` yoklamasıyla kaçırılan mesajlar alınır. Kalıcı hatada (403/404) durur ve
     * [HsChatListener.onError] çağrılır. Uygulama arka plana geçince [stopListening] önerilir (temsilci mesajı push ile gelir).
     */
    @JvmStatic
    fun startListening() {
        synchronized(lock) {
            if (streamJob?.isActive == true) return
            store().chatEnabled = true
            streamJob = scope.launch { listenLoop() }
        }
    }

    /** Canlı dinlemeyi durdurur (açık bağlantı kapatılır). */
    @JvmStatic
    fun stopListening() {
        synchronized(lock) {
            streamJob?.cancel()
            streamJob = null
        }
        activeConnection?.disconnect()
        activeConnection = null
    }

    private fun restartStream() {
        if (streamJob?.isActive != true) return
        restartRequested = true
        activeConnection?.disconnect()
    }

    private suspend fun listenLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var connected = false
            try {
                val store = store()
                val lastSeq = store.chatLastSeq.takeIf { it > 0 }
                val connection = api().openStream("/chat/stream?${visitor().query()}", lastSeq)
                activeConnection = connection
                connected = true
                failures = 0
                emit { it.onConnectionChanged(true) }
                readStream(connection)
            } catch (e: HsApiException) {
                if (!e.retryable) {
                    HsLog.w("sohbet akışı durdu: ${e.code}")
                    emit { it.onError(e.code, e.message) }
                    break
                }
            } catch (e: IOException) {
                HsLog.d("sohbet akışı koptu")
            } catch (e: IllegalStateException) {
                // SDK başlatılmadı/sıfırlandı.
                break
            } finally {
                activeConnection?.disconnect()
                activeConnection = null
                if (connected) emit { it.onConnectionChanged(false) }
            }
            if (restartRequested) {
                restartRequested = false
                continue
            }
            if (!currentCoroutineContext().isActive) break
            // Yoklama yedeği: akış yokken kaçırılan mesajlar `after_seq` ile alınır.
            runCatching { pollMissed() }
            failures++
            delay(HsChatBackoff.delayMs(failures))
        }
    }

    private fun readStream(connection: HttpURLConnection) {
        val parser = HsSseParser()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                parser.feed(line)?.let(::handleEvent)
            }
        }
    }

    private fun handleEvent(event: HsSseEvent) {
        val json = runCatching { JSONObject(event.data) }.getOrNull() ?: return
        when (event.event) {
            "message" -> onIncoming(listOf(HsChatParser.message(json)))
            "typing" -> emit { it.onTyping(json.optBoolean("typing", false), json.optStringOrNull("name")) }
            "read" -> json.optLong("seq", 0L).takeIf { it > 0 }?.let { seq -> emit { it.onAgentRead(seq) } }
            "conversation" -> {
                val id = json.optStringOrNull("id") ?: return
                conversationId = id
                if (json.has("unread_count")) setUnread(json.optInt("unread_count", 0))
                val status = json.optStringOrNull("status") ?: "open"
                val botActive = json.optBoolean("bot_active", false)
                emit { it.onConversationUpdated(id, status, botActive) }
            }
        }
    }

    /** Akıştan/yoklamadan gelen mesajlar: sıra ilerletilir, yeni olanlar bildirilir, temsilci mesajları okunmamışa eklenir. */
    private fun onIncoming(messages: List<HsChatMessage>) {
        val fresh = messages.sortedBy { it.seq }.filter { seen.mark(it.id) }
        messages.maxOfOrNull { it.seq }?.let(::advanceLastSeq)
        if (fresh.isEmpty()) return
        fresh.forEach { m -> emit { it.onMessage(m) } }
        val incomingFromAgent = fresh.count { !it.isFromVisitor && it.sender != HsChatMessage.SENDER_SYSTEM }
        if (incomingFromAgent > 0) setUnread(unreadCount + incomingFromAgent)
        scheduleDeliveredAck(fresh)
    }

    private fun pollMissed() {
        if (conversationId == null) return
        var guard = 0
        while (guard++ < 10) {
            val after = store().chatLastSeq
            val page = HsChatParser.page(api().request("GET", "/chat/messages?${HsChatRequests.messagesQuery(visitor(), after, null, 100)}"))
            onIncoming(page.messages)
            if (!page.hasMore || page.messages.isEmpty()) break
        }
    }

    // --- Ek indirme ---

    /**
     * Ek dosyasının adresi (yalnızca bu ziyaretçinin konuşmasındaki ekler; görseller satır içi). Adres ziyaretçi
     * belirtecini sorguda taşır: yalnızca görsel yükleyiciye verin, loglamayın veya paylaşmayın.
     */
    @JvmStatic
    @JvmOverloads
    fun attachmentUrl(attachmentId: String, download: Boolean = false): String {
        val api = api()
        val appId = URLEncoder.encode(Pushizo.appId, "UTF-8")
        val id = URLEncoder.encode(attachmentId, "UTF-8")
        return "${api.apiUrl}/sdk/v1/apps/$appId/chat/attachments/$id?${visitor().query()}" + if (download) "&download=true" else ""
    }

    // --- Public API (suspend) ---

    /** Sohbeti etkinleştirir ve ziyaretçiyi bağlar (kullanıcı, push aboneliği); konuşma varsa döner. */
    suspend fun start(name: String? = null, email: String? = null, language: String? = null): HsChatIdentifyResult =
        withContext(Dispatchers.IO) { startBlocking(name, email, language) }

    /** Ziyaretçi bilgisini (ad, e-posta, dil) günceller ve bağlamayı yeniler. */
    suspend fun identify(name: String? = null, email: String? = null, language: String? = null): HsChatIdentifyResult =
        withContext(Dispatchers.IO) { startBlocking(name, email, language) }

    /** Widget ayarları, çevrimiçi durumu, çevrimdışı metni ve ek sınırları. */
    suspend fun getConfig(language: String? = null): HsChatConfig = withContext(Dispatchers.IO) { configBlocking(language) }

    /** Konuşma (yoksa null) ve okunmamış sayısı. */
    suspend fun getConversation(): HsChatConversationState = withContext(Dispatchers.IO) { conversationBlocking() }

    /**
     * Mesajlar (kronolojik): parametresiz en yeni [limit] mesaj; [afterSeq] ile sonraki mesajlar, [beforeSeq] ile
     * daha eski geçmiş (`limit` 1-100).
     */
    suspend fun getMessages(afterSeq: Long? = null, beforeSeq: Long? = null, limit: Int = 50): HsChatMessagePage =
        withContext(Dispatchers.IO) { messagesBlocking(afterSeq, beforeSeq, limit) }

    /**
     * Mesaj gönderir (ilk mesaj konuşmayı açar). [optionId] bot sorusunda seçilen hızlı yanıttır. Ağ yoksa mesaj
     * kuyruğa alınır ve `pending` yerel mesaj döner; gönderilince dinleyicilere sunucu kopyası gelir.
     */
    suspend fun sendMessage(text: String, optionId: String? = null): HsChatMessage =
        withContext(Dispatchers.IO) { sendBlocking(text, optionId, null, null, null) }

    /** Ek gönderir (PNG/JPEG/WebP/GIF/PDF/düz metin, en çok 10 MB); [text] isteğe bağlı açıklamadır. */
    suspend fun sendAttachment(bytes: ByteArray, fileName: String, mimeType: String, text: String? = null): HsChatMessage =
        withContext(Dispatchers.IO) { attachmentBlocking(bytes, fileName, mimeType, text) }

    /** `content://` veya `file://` adresinden ek gönderir; ad/tür verilmezse ContentResolver'dan okunur. */
    suspend fun sendAttachment(uri: Uri, fileName: String? = null, mimeType: String? = null, text: String? = null): HsChatMessage =
        withContext(Dispatchers.IO) { attachmentFromUri(requireContext(), uri, fileName, mimeType, text) }

    /** [seq] numarasına kadar okundu işaretler; kalan okunmamış sayısını döndürür. */
    suspend fun markRead(seq: Long): Int = withContext(Dispatchers.IO) { markReadBlocking(seq) }

    /** Okunmamış sayısını sunucudan yeniler. */
    suspend fun refreshUnreadCount(): Int = withContext(Dispatchers.IO) { conversationBlocking().unreadCount }

    /** Çevrimdışı / çalışma saatleri dışında e-postayla mesaj bırakır. */
    suspend fun leaveOfflineMessage(email: String, text: String, name: String? = null): HsChatMessage =
        withContext(Dispatchers.IO) { offlineBlocking(email, text, name, null) }

    /** Yazıyor göstergesi (konuşma yoksa ve tekrarlarda istek atılmaz); hata yalnızca günlüğe yazılır. */
    @JvmStatic
    fun setTyping(typing: Boolean) {
        if (Pushizo.storage == null) return
        scope.launch { runCatching { typingBlocking(typing) }.onFailure { HsLog.d("yazıyor bilgisi gönderilemedi") } }
    }

    private fun requireContext(): Context = Pushizo.appContext ?: throw IllegalStateException("Pushizo.initialize önce çağrılmalı")

    // --- Java / köprü sürümleri (geri çağrı arka plan iş parçacığında) ---

    @JvmStatic @JvmOverloads
    fun start(name: String? = null, email: String? = null, language: String? = null, callback: HsCallback<HsChatIdentifyResult>) =
        async(callback) { startBlocking(name, email, language) }

    @JvmStatic @JvmOverloads
    fun getConfig(language: String? = null, callback: HsCallback<HsChatConfig>) = async(callback) { configBlocking(language) }

    @JvmStatic
    fun getConversation(callback: HsCallback<HsChatConversationState>) = async(callback) { conversationBlocking() }

    @JvmStatic @JvmOverloads
    fun getMessages(afterSeq: Long? = null, beforeSeq: Long? = null, limit: Int = 50, callback: HsCallback<HsChatMessagePage>) =
        async(callback) { messagesBlocking(afterSeq, beforeSeq, limit) }

    @JvmStatic @JvmOverloads
    fun sendMessage(text: String, optionId: String? = null, callback: HsCallback<HsChatMessage>) =
        async(callback) { sendBlocking(text, optionId, null, null, null) }

    @JvmStatic @JvmOverloads
    fun sendAttachment(bytes: ByteArray, fileName: String, mimeType: String, text: String? = null, callback: HsCallback<HsChatMessage>) =
        async(callback) { attachmentBlocking(bytes, fileName, mimeType, text) }

    @JvmStatic @JvmOverloads
    fun sendAttachment(uri: Uri, fileName: String? = null, mimeType: String? = null, text: String? = null, callback: HsCallback<HsChatMessage>) =
        async(callback) { attachmentFromUri(requireContext(), uri, fileName, mimeType, text) }

    @JvmStatic
    fun markRead(seq: Long, callback: HsCallback<Int>) = async(callback) { markReadBlocking(seq) }

    @JvmStatic
    fun refreshUnreadCount(callback: HsCallback<Int>) = async(callback) { conversationBlocking().unreadCount }

    @JvmStatic @JvmOverloads
    fun leaveOfflineMessage(email: String, text: String, name: String? = null, callback: HsCallback<HsChatMessage>) =
        async(callback) { offlineBlocking(email, text, name, null) }

    private fun <T> async(callback: HsCallback<T>, block: () -> T) {
        scope.launch { callback.onResult(runCatching(block)) }
    }
}
