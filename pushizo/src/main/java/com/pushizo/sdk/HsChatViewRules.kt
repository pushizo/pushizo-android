package com.pushizo.sdk

/**
 * Hazır sohbet arayüzünün ([HsChatView]) ekran ve çizim kuralları. Android'e bağımlı değildir (JVM birim testi);
 * Web SDK sohbet penceresiyle aynı kararları verir.
 */
internal object HsChatViewRules {
    /** Görünümün o an gösterdiği ekran. */
    enum class Screen { LOADING, ERROR, UNAVAILABLE, OFFLINE_FORM, OFFLINE_THANKS, PRECHAT, CHAT }

    /** Ziyaretçi mesajının iletim durumu (son ziyaretçi mesajının altında gösterilir). */
    enum class Delivery { SENDING, SENT, DELIVERED, READ }

    private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * Yapılandırma yüklendikten sonraki ekran: sohbet kapalıysa [Screen.UNAVAILABLE]; çevrimdışıyken konuşma yoksa ve
     * app e-posta topluyorsa çevrimdışı form (gönderildiyse teşekkür); e-posta zorunluysa ve henüz konuşma/e-posta
     * yoksa ön form; aksi halde sohbet.
     */
    fun screen(config: HsChatConfig, hasConversation: Boolean, emailGiven: Boolean, offlineSent: Boolean): Screen = when {
        !config.enabled -> Screen.UNAVAILABLE
        offlineSent -> Screen.OFFLINE_THANKS
        !config.online && !hasConversation && config.collectEmailOffline -> Screen.OFFLINE_FORM
        config.requireEmail && !hasConversation && !emailGiven -> Screen.PRECHAT
        else -> Screen.CHAT
    }

    /**
     * Mesaj listesine yenileri ekler: aynı sunucu kimliği tek kez yer alır (yenisi eskisinin yerine geçer), sunucu
     * kopyası gelen bekleyen yerel mesaj (`client_message_id` eşleşmesi) kaldırılır. Sıra: sunucu mesajları sıra
     * numarasına göre, bekleyen yerel mesajlar oluşturulma anına göre sonda.
     */
    fun merge(current: List<HsChatMessage>, incoming: List<HsChatMessage>): List<HsChatMessage> {
        val byKey = LinkedHashMap<String, HsChatMessage>()
        for (message in current + incoming) {
            val clientId = message.clientMessageId
            if (message.id != null) {
                if (clientId != null) byKey.remove("c:$clientId")
                byKey["s:${message.id}"] = message
            } else if (clientId != null) {
                // Sunucu kopyası zaten varsa bekleyen yerel mesaj eklenmez.
                if (byKey.values.none { it.id != null && it.clientMessageId == clientId }) byKey["c:$clientId"] = message
            }
        }
        val (sent, pending) = byKey.values.partition { it.id != null }
        return sent.sortedBy { it.seq } + pending.sortedBy { it.createdAt ?: Long.MAX_VALUE }
    }

    /** Son ziyaretçi mesajının sırası (durum satırı yalnızca onun altında gösterilir); yoksa -1. */
    fun lastVisitorIndex(messages: List<HsChatMessage>): Int = messages.indexOfLast { it.isFromVisitor }

    /** Hızlı yanıtlar yalnızca listedeki son mesajda ve ziyaretçiye ait değilse gösterilir (eski bot sorularına tıklanmaz). */
    fun showsQuickReplies(index: Int, messages: List<HsChatMessage>): Boolean {
        val message = messages.getOrNull(index) ?: return false
        return index == messages.lastIndex && !message.isFromVisitor && !message.quickReplies.isNullOrEmpty()
    }

    fun delivery(message: HsChatMessage, agentReadSeq: Long): Delivery = when {
        message.id == null || message.status == HsChatMessage.STATUS_PENDING -> Delivery.SENDING
        message.status == HsChatMessage.STATUS_READ || (message.seq in 1..agentReadSeq) -> Delivery.READ
        message.status == HsChatMessage.STATUS_DELIVERED -> Delivery.DELIVERED
        else -> Delivery.SENT
    }

    /** Karşı tarafın görünen adı: yazar adı, yoksa bot/temsilci etiketi; ziyaretçi ve sistem mesajında null. */
    fun senderLabel(message: HsChatMessage, botLabel: String, agentLabel: String): String? = when (message.sender) {
        HsChatMessage.SENDER_VISITOR, HsChatMessage.SENDER_SYSTEM -> null
        HsChatMessage.SENDER_BOT -> message.authorName?.takeIf { it.isNotBlank() } ?: botLabel
        else -> message.authorName?.takeIf { it.isNotBlank() } ?: agentLabel
    }

    /** Balon metni; yalnızca ek adını tekrarlayan metin gösterilmez (ek satırı zaten adı taşır). */
    fun displayText(message: HsChatMessage): String? {
        val text = message.text?.takeIf { it.isNotBlank() } ?: return null
        return if (message.attachments.firstOrNull()?.name == text) null else text
    }

    /** Yalnızca `#rrggbb` kabul edilir; opak ARGB tam sayısı döner, geçersizse null. */
    fun color(value: String?): Int? {
        val hex = value?.trim()?.takeIf { HEX_COLOR.matches(it) } ?: return null
        return (0xFF000000L or hex.substring(1).toLong(16)).toInt()
    }

    /** Arayüz koyu mu: `dark` her zaman, `light` hiçbir zaman, `auto` (ve bilinmeyen) sistem temasına göre. */
    fun isDark(theme: String?, systemDark: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    fun isResolved(status: String?): Boolean = status == "resolved" || status == "closed"

    fun isValidEmail(value: String): Boolean = HsContacts.normalizeEmail(value) != null

    /** Ek boyutu sınırı: app ayarı ile SDK üst sınırının küçüğü. */
    fun maxAttachmentBytes(config: HsChatConfig?): Long =
        minOf(config?.attachmentMaxBytes?.takeIf { it > 0 } ?: HsChatLimits.MAX_ATTACHMENT_BYTES, HsChatLimits.MAX_ATTACHMENT_BYTES)

    /** Boyutu bilinmeyen (`null`) dosya burada reddedilmez; okuma sırasında sınır yine uygulanır. */
    fun attachmentTooLarge(sizeBytes: Long?, config: HsChatConfig?): Boolean = sizeBytes != null && sizeBytes > maxAttachmentBytes(config)

    /** `{mb}` yer tutucusu için tam megabayt (en az 1). */
    fun megabytes(maxBytes: Long): String = maxOf(1L, maxBytes / (1024 * 1024)).toString()

    /** Dosya seçicide sunulacak türler: app ayarındakiler, boşsa sunucunun kabul ettiklerinin tümü. */
    fun attachmentTypes(config: HsChatConfig?): Array<String> =
        (config?.attachmentTypes?.filter { it in HsChatLimits.ATTACHMENT_TYPES }?.takeIf { it.isNotEmpty() } ?: HsChatLimits.ATTACHMENT_TYPES.toList()).toTypedArray()
}
