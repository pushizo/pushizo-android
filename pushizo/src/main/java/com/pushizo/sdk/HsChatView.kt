package com.pushizo.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Hazır canlı sohbet arayüzü (ADR-0040): `Pushizo.Chat` verisini başlık, mesaj listesi ve mesaj alanıyla gösterir.
 * Pencereye eklendiğinde sohbet ayarını ve geçmişi yükler, canlı dinlemeyi başlatır ve gelen mesajları okundu
 * işaretler; ayrılınca dinleyicisini kaldırır ve (kendisi başlattıysa) canlı dinlemeyi durdurur. Yükleniyor, hata
 * (yeniden dene), sohbet kapalı, e-posta ön formu, çevrimdışı mesaj formu ve teşekkür durumları vardır.
 *
 * Renkler app ayarındaki temaya (`light`/`dark`/`auto` → sistem koyu teması) ve vurgu rengine uyar; düzen sağdan sola
 * dillerde aynalanır; düğmeler ve balonlar TalkBack etiketi taşır. Metinler `hs_chat_*` kaynaklarıyla ezilebilir.
 *
 * Tam ekran kullanım için [PushizoChat.show] ([HsChatActivity]). Kendi ekranınıza gömerken kapat düğmesi için
 * [onCloseRequested], dosya ekleme için [onAttachRequested] atanır ve seçilen dosya [sendAttachment] ile verilir.
 */
class HsChatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Kapat düğmesine basıldığında çağrılır; atanmazsa düğme gösterilmez. */
    var onCloseRequested: (() -> Unit)? = null
        set(value) {
            field = value
            close.visibility = if (value != null) View.VISIBLE else View.GONE
        }

    /**
     * Dosya ekle düğmesine basıldığında çağrılır (uygulama dosya seçiciyi açar ve sonucu [sendAttachment] ile verir).
     * Atanmazsa ya da app ayarında ekler kapalıysa düğme gösterilmez. Seçicide sunulacak türler: [attachmentMimeTypes].
     */
    var onAttachRequested: (() -> Unit)? = null
        set(value) {
            field = value
            updateComposer()
        }

    /** Dosya seçicide sunulacak MIME türleri (app ayarı; yüklenmeden önce sunucunun kabul ettiği tüm türler). */
    val attachmentMimeTypes: Array<String> get() = HsChatViewRules.attachmentTypes(config)

    private val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private var palette = paletteFor(systemDark)
    private var accent = palette.accent
    private var onAccent = Color.WHITE

    private var config: HsChatConfig? = null
    private var screen = HsChatViewRules.Screen.LOADING
    private var messages: List<HsChatMessage> = emptyList()
    private var agentReadSeq = 0L
    private var conversationStatus: String? = null
    private var hasConversation = false
    private var emailGiven = false
    private var offlineSent = false
    private var busy = false
    private val images = HashMap<String, Bitmap>()

    private var scope: CoroutineScope? = null
    private var loadJob: Job? = null
    private var startedListening = false
    private var bannerClear: Runnable? = null

    private val title = label("", 17f, Color.WHITE, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) }
    private val presence = label("", 12f, Color.WHITE)
    private val close = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = context.getString(R.string.hs_chat_close)
        visibility = View.GONE
        setOnClickListener { onCloseRequested?.invoke() }
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(16), dp(12), dp(8), dp(12))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(presence) }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
    private val scroll = ScrollView(context).apply { isFillViewport = true; addView(list) }
    private val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
    private val formScroll = ScrollView(context).apply { isFillViewport = true; addView(form) }
    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private val statusText = label("", 15f, palette.secondary).apply { gravity = Gravity.CENTER }
    private val retry = Button(context).apply { text = context.getString(R.string.hs_chat_retry); setOnClickListener { load() } }
    private val status = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(progress)
        addView(statusText, wrap().apply { topMargin = dp(8) })
        addView(retry, wrap().apply { topMargin = dp(12) })
    }
    private val body = FrameLayout(context).apply {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(formScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private val typing = label("", 12f, palette.secondary).apply {
        setPaddingRelative(dp(16), dp(2), dp(16), dp(2))
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val banner = label("", 13f, palette.error).apply {
        setPaddingRelative(dp(16), dp(6), dp(16), dp(6))
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
    }

    private val attach = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_upload)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = context.getString(R.string.hs_chat_attach)
        setOnClickListener { onAttachRequested?.invoke() }
    }
    private val input = EditText(context).apply {
        hint = context.getString(R.string.hs_chat_placeholder)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        maxLines = 4
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val hasText = !s.isNullOrBlank()
                send.isEnabled = hasText && !busy
                if (hasConversation) PushizoChat.setTyping(hasText)
            }
        })
    }
    private val send: Button = Button(context).apply {
        text = context.getString(R.string.hs_chat_send)
        isAllCaps = false
        isEnabled = false
        minWidth = dp(72)
        minimumWidth = dp(72)
        setOnClickListener { submitMessage(input.text.toString(), null) }
    }
    private val composer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPaddingRelative(dp(8), dp(8), dp(8), dp(8))
        addView(attach, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(8) })
        addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
    }

    private val listener = object : HsChatListener {
        override fun onMessage(message: HsChatMessage) = onIncoming(message)
        override fun onTyping(typing: Boolean, name: String?) = showTyping(typing, name)
        override fun onAgentRead(seq: Long) {
            if (seq > agentReadSeq) {
                agentReadSeq = seq
                renderMessages(keepPosition = true)
            }
        }
        override fun onConversationUpdated(conversationId: String, status: String, botActive: Boolean) {
            hasConversation = true
            if (conversationStatus != status) {
                conversationStatus = status
                renderMessages(keepPosition = true)
            }
        }
        override fun onError(code: String, message: String?) = showBanner(context.getString(R.string.hs_chat_error))
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(typing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(composer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        title.text = context.getString(R.string.hs_chat_title)
        applyColors()
        show(HsChatViewRules.Screen.LOADING)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        PushizoChat.addListener(listener)
        load()
    }

    override fun onDetachedFromWindow() {
        PushizoChat.removeListener(listener)
        if (hasConversation && input.text.isNotBlank()) PushizoChat.setTyping(false)
        if (startedListening) {
            PushizoChat.stopListening()
            startedListening = false
        }
        bannerClear?.let(::removeCallbacks)
        bannerClear = null
        scope?.cancel()
        scope = null
        loadJob = null
        super.onDetachedFromWindow()
    }

    /** Ayarı ve geçmişi yeniden yükler. Pencereye eklenmemişse eklendiğinde yüklenir. */
    fun refresh() = load()

    /**
     * Seçilen dosyayı ek olarak gönderir (`content://` veya `file://`). Boyut sınırı aşılırsa ya da gönderim başarısız
     * olursa görünümde hata satırı gösterilir.
     */
    fun sendAttachment(uri: Uri) {
        val s = scope ?: return
        if (screen != HsChatViewRules.Screen.CHAT || busy) return
        if (HsChatViewRules.attachmentTooLarge(sizeOf(uri), config)) {
            showBanner(tooLargeText())
            return
        }
        setBusy(true)
        s.launch {
            try {
                PushizoChat.sendAttachment(uri)
                hasConversation = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HsLog.w("Sohbet eki gönderilemedi", e)
                showBanner(context.getString(R.string.hs_chat_error))
            } finally {
                setBusy(false)
            }
        }
    }

    // --- Yükleme ---

    private fun load() {
        val s = scope ?: return
        loadJob?.cancel()
        show(HsChatViewRules.Screen.LOADING)
        loadJob = s.launch {
            try {
                val cfg = PushizoChat.getConfig()
                config = cfg
                applyConfig(cfg)
                if (!cfg.enabled) {
                    show(HsChatViewRules.Screen.UNAVAILABLE)
                    return@launch
                }
                val conversation = PushizoChat.start().conversation
                hasConversation = conversation != null
                conversationStatus = conversation?.status
                agentReadSeq = conversation?.agentLastReadSeq ?: 0L
                val next = HsChatViewRules.screen(cfg, hasConversation, emailGiven, offlineSent)
                if (next == HsChatViewRules.Screen.CHAT) openChat() else show(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HsLog.w("Sohbet yüklenemedi", e)
                show(HsChatViewRules.Screen.ERROR)
            }
        }
    }

    /** Geçmişi alır, canlı dinlemeyi başlatır ve sohbet ekranını gösterir. */
    private suspend fun openChat() {
        messages = HsChatViewRules.merge(messages, PushizoChat.getMessages().messages)
        if (!PushizoChat.isListening) {
            PushizoChat.startListening()
            startedListening = true
        }
        show(HsChatViewRules.Screen.CHAT)
        renderMessages(keepPosition = false)
        markRead()
    }

    private fun applyConfig(cfg: HsChatConfig) {
        palette = paletteFor(HsChatViewRules.isDark(cfg.theme, systemDark))
        accent = HsChatViewRules.color(cfg.color) ?: palette.accent
        onAccent = HsChatViewRules.color(cfg.textColor) ?: Color.WHITE
        title.text = cfg.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.hs_chat_title)
        presence.text = context.getString(if (cfg.online) R.string.hs_chat_online else R.string.hs_chat_offline)
        applyColors()
        updateComposer()
    }

    private fun applyColors() {
        setBackgroundColor(palette.background)
        header.setBackgroundColor(accent)
        title.setTextColor(onAccent)
        presence.setTextColor(onAccent)
        close.setColorFilter(onAccent)
        attach.setColorFilter(palette.secondary)
        statusText.setTextColor(palette.secondary)
        typing.setTextColor(palette.secondary)
        banner.setTextColor(palette.error)
        banner.setBackgroundColor(palette.surface)
        composer.setBackgroundColor(palette.surface)
        styleField(input)
        styleButton(send)
    }

    // --- Ekranlar ---

    private fun show(next: HsChatViewRules.Screen) {
        screen = next
        val chat = next == HsChatViewRules.Screen.CHAT
        val isForm = next == HsChatViewRules.Screen.PRECHAT || next == HsChatViewRules.Screen.OFFLINE_FORM
        scroll.visibility = if (chat) View.VISIBLE else View.GONE
        formScroll.visibility = if (isForm) View.VISIBLE else View.GONE
        status.visibility = if (chat || isForm) View.GONE else View.VISIBLE
        progress.visibility = if (next == HsChatViewRules.Screen.LOADING) View.VISIBLE else View.GONE
        retry.visibility = if (next == HsChatViewRules.Screen.ERROR) View.VISIBLE else View.GONE
        statusText.visibility = if (next == HsChatViewRules.Screen.LOADING) View.GONE else View.VISIBLE
        statusText.text = when (next) {
            HsChatViewRules.Screen.ERROR -> context.getString(R.string.hs_chat_error)
            HsChatViewRules.Screen.UNAVAILABLE -> context.getString(R.string.hs_chat_offline)
            HsChatViewRules.Screen.OFFLINE_THANKS -> context.getString(R.string.hs_chat_thanks)
            else -> null
        }
        if (!chat) typing.visibility = View.GONE
        updateComposer()
        when (next) {
            HsChatViewRules.Screen.PRECHAT -> buildPrechatForm()
            HsChatViewRules.Screen.OFFLINE_FORM -> buildOfflineForm()
            else -> form.removeAllViews()
        }
        if (next == HsChatViewRules.Screen.OFFLINE_THANKS || next == HsChatViewRules.Screen.ERROR) announceForAccessibility(statusText.text)
    }

    private fun updateComposer() {
        composer.visibility = if (screen == HsChatViewRules.Screen.CHAT) View.VISIBLE else View.GONE
        attach.visibility = if (config?.attachmentsEnabled == true && onAttachRequested != null) View.VISIBLE else View.GONE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        send.isEnabled = !value && input.text.isNotBlank()
        attach.isEnabled = !value
    }

    private fun buildPrechatForm() {
        form.removeAllViews()
        form.addView(label(context.getString(R.string.hs_chat_email_required), 15f, palette.primary))
        val name = field(R.string.hs_chat_name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val email = field(R.string.hs_chat_email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val error = errorLabel()
        val submit = Button(context).apply { text = context.getString(R.string.hs_chat_start); isAllCaps = false; styleButton(this) }
        form.addView(name, fieldParams())
        form.addView(email, fieldParams())
        form.addView(error, fieldParams())
        form.addView(submit, fieldParams())
        submit.setOnClickListener {
            val address = email.text.toString().trim()
            if (!HsChatViewRules.isValidEmail(address)) {
                showFormError(error, context.getString(R.string.hs_chat_invalid_email))
                email.requestFocus()
                return@setOnClickListener
            }
            val s = scope ?: return@setOnClickListener
            submit.isEnabled = false
            error.visibility = View.GONE
            s.launch {
                try {
                    val result = PushizoChat.start(name = name.text.toString().trim().ifEmpty { null }, email = address)
                    emailGiven = true
                    hasConversation = result.conversation != null
                    openChat()
                    input.requestFocus()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HsLog.w("Sohbet başlatılamadı", e)
                    submit.isEnabled = true
                    showFormError(error, context.getString(R.string.hs_chat_error))
                }
            }
        }
    }

    private fun buildOfflineForm() {
        form.removeAllViews()
        form.addView(label(config?.offlineMessage?.takeIf { it.isNotBlank() } ?: context.getString(R.string.hs_chat_offline_default), 15f, palette.primary))
        val name = field(R.string.hs_chat_name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val email = field(R.string.hs_chat_email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val message = field(R.string.hs_chat_message, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        val error = errorLabel()
        val submit = Button(context).apply { text = context.getString(R.string.hs_chat_leave); isAllCaps = false; styleButton(this) }
        form.addView(name, fieldParams())
        form.addView(email, fieldParams())
        form.addView(message, fieldParams())
        form.addView(error, fieldParams())
        form.addView(submit, fieldParams())
        submit.setOnClickListener {
            val address = email.text.toString().trim()
            if (!HsChatViewRules.isValidEmail(address)) {
                showFormError(error, context.getString(R.string.hs_chat_invalid_email))
                email.requestFocus()
                return@setOnClickListener
            }
            val content = message.text.toString().trim()
            if (content.isEmpty()) {
                message.requestFocus()
                return@setOnClickListener
            }
            val s = scope ?: return@setOnClickListener
            submit.isEnabled = false
            error.visibility = View.GONE
            s.launch {
                try {
                    PushizoChat.leaveOfflineMessage(address, content, name.text.toString().trim().ifEmpty { null })
                    offlineSent = true
                    show(HsChatViewRules.Screen.OFFLINE_THANKS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HsLog.w("Çevrimdışı sohbet mesajı bırakılamadı", e)
                    submit.isEnabled = true
                    showFormError(error, context.getString(R.string.hs_chat_error))
                }
            }
        }
    }

    private fun showFormError(view: TextView, text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }

    // --- Mesajlar ---

    private fun onIncoming(message: HsChatMessage) {
        messages = HsChatViewRules.merge(messages, listOf(message))
        if (message.id != null) hasConversation = true
        if (!message.isFromVisitor) showTyping(false, null)
        if (screen != HsChatViewRules.Screen.CHAT) return
        renderMessages(keepPosition = !message.isFromVisitor)
        if (!message.isFromVisitor) markRead()
    }

    /** Görünürken gelen temsilci/bot mesajları okundu işaretlenir. */
    private fun markRead() {
        if (!isShown) return
        val seq = messages.filter { !it.isFromVisitor }.maxOfOrNull { it.seq } ?: return
        if (seq <= 0) return
        scope?.launch {
            try {
                PushizoChat.markRead(seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HsLog.d("sohbet okundu bilgisi gönderilemedi")
            }
        }
    }

    private fun submitMessage(text: String, optionId: String?) {
        val content = text.trim()
        val s = scope ?: return
        if (content.isEmpty() || busy) return
        if (optionId == null) input.setText("")
        setBusy(true)
        s.launch {
            try {
                PushizoChat.sendMessage(content, optionId)
                hasConversation = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HsLog.w("Sohbet mesajı gönderilemedi", e)
                if (optionId == null && input.text.isEmpty()) input.setText(content)
                showBanner(context.getString(R.string.hs_chat_error))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun renderMessages(keepPosition: Boolean) {
        if (screen != HsChatViewRules.Screen.CHAT) return
        val atBottom = scroll.getChildAt(0).let { it.height - scroll.scrollY - scroll.height < dp(40) }
        list.removeAllViews()
        config?.greeting?.takeIf { it.isNotBlank() }?.let { list.addView(note(it)) }
        val lastMine = HsChatViewRules.lastVisitorIndex(messages)
        messages.forEachIndexed { index, message ->
            list.addView(messageView(message, index == lastMine, HsChatViewRules.showsQuickReplies(index, messages)))
        }
        if (HsChatViewRules.isResolved(conversationStatus)) list.addView(note(context.getString(R.string.hs_chat_resolved)))
        if (!keepPosition || atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun messageView(message: HsChatMessage, isLastMine: Boolean, quickReplies: Boolean): View {
        val mine = message.isFromVisitor
        val system = message.sender == HsChatMessage.SENDER_SYSTEM
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (system) Gravity.CENTER_HORIZONTAL else if (mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val sender = HsChatViewRules.senderLabel(message, context.getString(R.string.hs_chat_bot), context.getString(R.string.hs_chat_agent))
        sender?.let { column.addView(label(it, 12f, palette.secondary).apply { setPaddingRelative(dp(4), 0, dp(4), dp(2)) }, wrap()) }

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (system) Color.TRANSPARENT else if (mine) accent else palette.surface)
                if (!mine && !system) setStroke(dp(1), palette.border)
            }
        }
        val textColor = if (system) palette.secondary else if (mine) onAccent else palette.primary
        val content = HsChatViewRules.displayText(message)
        content?.let { bubble.addView(label(it, if (system) 13f else 15f, textColor).apply { setTextIsSelectable(!system) }) }
        for (attachment in message.attachments) bubble.addView(attachmentView(attachment, textColor))
        val delivery = if (mine && (isLastMine || message.id == null)) deliveryText(HsChatViewRules.delivery(message, agentReadSeq)) else null
        // TalkBack balonu tek öğe olarak okur: gönderen, metin, ek adları ve iletim durumu.
        bubble.contentDescription = listOfNotNull(
            sender ?: if (mine) context.getString(R.string.hs_chat_you) else null,
            content,
            message.attachments.joinToString(", ") { it.name }.ifEmpty { null },
            delivery,
        ).joinToString(". ")
        if (bubble.childCount > 0) column.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // Balon satırın tamamını kaplamaz: karşı kenarda boşluk kalır.
            if (mine) marginStart = dp(48) else marginEnd = dp(48)
        })

        if (quickReplies) {
            val chips = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (reply in message.quickReplies.orEmpty()) {
                val chip = Button(context).apply {
                    this.text = reply.label
                    isAllCaps = false
                    setTextColor(accent)
                    background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(palette.surface); setStroke(dp(1), accent) }
                    setPaddingRelative(dp(14), dp(6), dp(14), dp(6))
                    minHeight = dp(48)
                    minimumHeight = dp(48)
                    setOnClickListener { submitMessage(reply.label, reply.id) }
                }
                chips.addView(chip, wrap().apply { topMargin = dp(6) })
            }
            column.addView(chips, wrap())
        }
        delivery?.let {
            column.addView(label(it, 11f, palette.secondary).apply {
                setPaddingRelative(dp(4), dp(2), dp(4), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, wrap())
        }
        return column
    }

    private fun attachmentView(attachment: HsChatAttachment, textColor: Int): View {
        val open = View.OnClickListener { openAttachment(attachment) }
        if (attachment.type.startsWith("image/")) {
            val image = ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = dp(220)
                minimumHeight = dp(48)
                minimumWidth = dp(120)
                contentDescription = attachment.name
                isClickable = true
                isFocusable = true
                setOnClickListener(open)
            }
            val cached = images[attachment.id]
            if (cached != null) image.setImageBitmap(cached)
            else scope?.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching { HsImages.download(PushizoChat.attachmentUrl(attachment.id)) }.getOrNull() }
                if (bitmap != null) {
                    images[attachment.id] = bitmap
                    image.setImageBitmap(bitmap)
                }
            }
            return image
        }
        return label("${attachment.name} — ${context.getString(R.string.hs_chat_download)}", 14f, textColor).apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener(open)
        }
    }

    private fun openAttachment(attachment: HsChatAttachment) {
        try {
            val url = PushizoChat.attachmentUrl(attachment.id, download = !attachment.type.startsWith("image/"))
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: ActivityNotFoundException) {
            HsLog.w("Sohbet eki açılamadı", e)
            showBanner(context.getString(R.string.hs_chat_error))
        } catch (e: IllegalStateException) {
            HsLog.w("Sohbet eki açılamadı", e)
        }
    }

    private fun deliveryText(delivery: HsChatViewRules.Delivery): String = context.getString(
        when (delivery) {
            HsChatViewRules.Delivery.SENDING -> R.string.hs_chat_sending
            HsChatViewRules.Delivery.SENT -> R.string.hs_chat_sent
            HsChatViewRules.Delivery.DELIVERED -> R.string.hs_chat_delivered
            HsChatViewRules.Delivery.READ -> R.string.hs_chat_read
        },
    )

    private fun showTyping(active: Boolean, name: String?) {
        if (!active || screen != HsChatViewRules.Screen.CHAT) {
            typing.visibility = View.GONE
            return
        }
        typing.text = context.getString(R.string.hs_chat_typing)
            .replace("{name}", name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.hs_chat_agent))
        typing.visibility = View.VISIBLE
    }

    /** Geçici hata satırı (5 sn); TalkBack hemen okur. */
    private fun showBanner(text: String) {
        bannerClear?.let(::removeCallbacks)
        banner.text = text
        banner.visibility = View.VISIBLE
        val clear = Runnable { banner.visibility = View.GONE }
        bannerClear = clear
        postDelayed(clear, BANNER_MS)
    }

    private fun tooLargeText(): String =
        context.getString(R.string.hs_chat_too_large).replace("{mb}", HsChatViewRules.megabytes(HsChatViewRules.maxAttachmentBytes(config)))

    /** Dosya boyutu (ContentResolver; bilinmiyorsa null). */
    private fun sizeOf(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    // --- Görünüm yardımcıları ---

    private fun note(text: String) = label(text, 13f, palette.secondary).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun field(hintRes: Int, type: Int) = EditText(context).apply {
        hint = context.getString(hintRes)
        inputType = type
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPaddingRelative(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(48)
        styleField(this)
    }

    private fun errorLabel() = label("", 13f, palette.error).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
    }

    private fun fieldParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }

    private fun styleField(view: EditText) {
        view.setTextColor(palette.primary)
        view.setHintTextColor(palette.secondary)
        view.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(palette.background); setStroke(dp(1), palette.border) }
    }

    private fun styleButton(view: Button) {
        val states = arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf())
        view.setTextColor(ColorStateList(states, intArrayOf(onAccent, palette.secondary)))
        view.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            color = ColorStateList(states, intArrayOf(accent, palette.border))
        }
        view.minHeight = dp(48)
        view.minimumHeight = dp(48)
    }

    private fun label(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class Palette(val background: Int, val surface: Int, val primary: Int, val secondary: Int, val accent: Int, val border: Int, val error: Int)

    private companion object {
        const val BANNER_MS = 5_000L

        fun paletteFor(dark: Boolean) =
            if (dark) Palette(0xFF121317.toInt(), 0xFF1E1F24.toInt(), 0xFFF2F2F5.toInt(), 0xFFB4B6BE.toInt(), 0xFF3B6FE0.toInt(), 0xFF2C2E35.toInt(), 0xFFFF8A80.toInt())
            else Palette(0xFFF5F6F8.toInt(), Color.WHITE, 0xFF15171C.toInt(), 0xFF5B5F6B.toInt(), 0xFF2563EB.toInt(), 0xFFE3E5EA.toInt(), 0xFFC5221F.toInt())
    }
}
