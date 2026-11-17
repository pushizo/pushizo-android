package com.pushizo.sdk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
 * Hazır içerik kartı akışı (ADR-0082): `Pushizo.ContentCards` verisini dikey liste olarak gösterir. Pencereye
 * eklendiğinde akışı yükler; yükleniyor, boş ve hata (yeniden dene) durumları vardır. Kartın en az yarısı ekranda
 * göründüğünde gösterim bir kez kaydedilir; tıklama kaydedilir ve bağlantı açılır ([onCardClick] true dönerse
 * uygulama işler); × kartı kapatır. Renkler sistem koyu temasına uyar. Metinler `hs_cards_*` kaynaklarıyla ezilebilir.
 */
class HsContentCardsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Bir yüklemede istenen kart sayısı (1-100). */
    var limit: Int = 50
        set(value) {
            require(value in 1..100) { "limit 1-100 olmalı." }
            field = value
        }

    /** Kart tıklandığında çağrılır; true dönerse varsayılan bağlantı açma yapılmaz. */
    var onCardClick: ((HsContentCard) -> Boolean)? = null

    /** Akış her yüklendiğinde görülmemiş kart sayısıyla çağrılır (rozet vb. için). */
    var onFeedLoaded: ((HsContentCardFeed) -> Unit)? = null

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val palette = if (dark) Palette(0xFF1E1F24.toInt(), 0xFFF2F2F5.toInt(), 0xFFB4B6BE.toInt(), 0xFF8AB4FF.toInt(), 0xFF2C2E35.toInt())
    else Palette(Color.WHITE, 0xFF15171C.toInt(), 0xFF5B5F6B.toInt(), 0xFF2563EB.toInt(), 0xFFE3E5EA.toInt())

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
    private val scroll = ScrollView(context).apply { isFillViewport = true; addView(list) }
    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private val message = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(palette.secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }
    private val retry = Button(context).apply { text = context.getString(R.string.hs_cards_retry); setOnClickListener { refresh() } }
    private val status = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(progress)
        addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private var scope: CoroutineScope? = null
    private var loadJob: Job? = null
    private var loaded = false
    /** Son yüklenen akış: ayrılıp yeniden eklenince (sekme, ViewPager, fragment yığını) iptal edilen görseller için. */
    private var lastFeed: HsContentCardFeed? = null
    private val impressed = HashSet<String>()
    private val cardViews = LinkedHashMap<String, Pair<View, HsContentCard>>()
    private val visibleRect = Rect()
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { checkImpressions() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { checkImpressions() }

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setBackgroundColor(if (dark) 0xFF121317.toInt() else 0xFFF5F6F8.toInt())
        show(HsContentCardsState.LOADING)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        // Ayrılırken görsel indirmeleri kapsamla birlikte iptal edildi: yüklüyse kartlar yeniden çizilir (görseller
        // yeniden istenir), değilse akış yüklenir.
        val feed = lastFeed
        if (loaded && feed != null) render(feed) else refresh()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        scope?.cancel()
        scope = null
        loadJob = null
        super.onDetachedFromWindow()
    }

    /** Akışı yeniden yükler (ör. aşağı çekerek yenileme). Pencereye eklenmemişse eklendiğinde yüklenir. */
    fun refresh() {
        val s = scope ?: run { loaded = false; return }
        loadJob?.cancel()
        if (cardViews.isEmpty()) show(HsContentCardsState.LOADING)
        loadJob = s.launch {
            try {
                val feed = Pushizo.ContentCards.list(limit)
                loaded = true
                lastFeed = feed
                render(feed)
                onFeedLoaded?.invoke(feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HsLog.w("İçerik kartları yüklenemedi", e)
                if (cardViews.isEmpty()) show(HsContentCardsState.ERROR)
            }
        }
    }

    private fun render(feed: HsContentCardFeed) {
        list.removeAllViews()
        cardViews.clear()
        for (card in feed.cards) {
            val view = cardView(card)
            cardViews[card.id] = view to card
            list.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        show(if (feed.cards.isEmpty()) HsContentCardsState.EMPTY else HsContentCardsState.CONTENT)
        post { checkImpressions() }
    }

    private fun show(state: HsContentCardsState) {
        scroll.visibility = if (state == HsContentCardsState.CONTENT) View.VISIBLE else View.GONE
        status.visibility = if (state == HsContentCardsState.CONTENT) View.GONE else View.VISIBLE
        progress.visibility = if (state == HsContentCardsState.LOADING) View.VISIBLE else View.GONE
        retry.visibility = if (state == HsContentCardsState.ERROR) View.VISIBLE else View.GONE
        message.visibility = if (state == HsContentCardsState.EMPTY || state == HsContentCardsState.ERROR) View.VISIBLE else View.GONE
        message.text = when (state) {
            HsContentCardsState.EMPTY -> context.getString(R.string.hs_cards_empty)
            HsContentCardsState.ERROR -> context.getString(R.string.hs_cards_error)
            else -> null
        }
    }

    private fun checkImpressions() {
        val s = scope ?: return
        if (scroll.visibility != View.VISIBLE) return
        for ((id, entry) in cardViews) {
            val (view, card) = entry
            if (id in impressed || card.viewed) continue
            val visible = if (view.isShown && view.getLocalVisibleRect(visibleRect)) visibleRect.height() else 0
            if (!HsContentCardsLayout.countsAsImpression(visible, view.height)) continue
            impressed += id
            s.launch {
                try {
                    Pushizo.ContentCards.logImpression(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    impressed -= id
                    HsLog.w("Kart gösterimi kaydedilemedi", e)
                }
            }
        }
    }

    private fun click(card: HsContentCard) {
        scope?.launch {
            try {
                Pushizo.ContentCards.logClick(card.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HsLog.w("Kart tıklaması kaydedilemedi", e)
            }
        }
        if (onCardClick?.invoke(card) == true) return
        val url = card.url ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: ActivityNotFoundException) {
            HsLog.w("Kart bağlantısı açılamadı: $url", e)
        }
    }

    private fun dismiss(card: HsContentCard) {
        val entry = cardViews.remove(card.id) ?: return
        list.removeView(entry.first)
        // Yeniden eklenmede yeniden çizim kapatılan kartı geri getirmesin.
        lastFeed = lastFeed?.let { f -> f.copy(cards = f.cards.filter { it.id != card.id }) }
        if (cardViews.isEmpty()) show(HsContentCardsState.EMPTY)
        scope?.launch {
            try {
                Pushizo.ContentCards.dismiss(card.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HsLog.w("Kart kapatılamadı", e)
            }
        }
    }

    private fun cardView(card: HsContentCard): View {
        val parts = HsContentCardsLayout.parts(card)
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(12).toFloat()
                if (card.pinned) setStroke(dp(2), palette.accent) else setStroke(dp(1), palette.border)
            }
            clipToOutline = true
            isClickable = true
            isFocusable = true
            contentDescription = card.title ?: card.body
            setOnClickListener { click(card) }
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        container.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        if (HsContentCardsLayout.Part.IMAGE in parts) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                setBackgroundColor(palette.border)
                contentDescription = if (HsContentCardsLayout.Part.TEXT in parts) null else card.title ?: card.body
            }
            column.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (HsContentCardsLayout.Part.TEXT in parts) 160 else 200)))
            card.imageUrl?.let { url ->
                scope?.launch {
                    val bitmap = withContext(Dispatchers.IO) { HsImages.download(url) }
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap)
                        if (HsContentCardsLayout.Part.TEXT !in parts) {
                            image.layoutParams = image.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                        }
                    }
                }
            }
        }
        if (HsContentCardsLayout.Part.TEXT in parts) {
            val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(16), dp(14), dp(40), dp(14)) }
            if (card.pinned) text.addView(label(context.getString(R.string.hs_cards_pinned), 12f, palette.accent, bold = true))
            card.title?.let { text.addView(label(it, 16f, palette.primary, bold = true)) }
            card.body?.let { text.addView(label(it, 14f, palette.secondary).apply { setPadding(0, dp(4), 0, 0) }) }
            if (HsContentCardsLayout.Part.BUTTON in parts) {
                text.addView(label(card.buttonText.orEmpty(), 14f, palette.accent, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            }
            column.addView(text)
        }
        val close = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (dark) 0x99000000.toInt() else 0x99FFFFFF.toInt()) }
            contentDescription = context.getString(R.string.hs_cards_dismiss)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { dismiss(card) }
        }
        container.addView(close, LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); marginEnd = dp(6) })
        return container
    }

    private fun label(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class Palette(val surface: Int, val primary: Int, val secondary: Int, val accent: Int, val border: Int)
}

/** Görünüm durumu. */
internal enum class HsContentCardsState { LOADING, EMPTY, ERROR, CONTENT }

/** Görünümden bağımsız (birim testli) yerleşim kuralları. */
internal object HsContentCardsLayout {
    enum class Part { IMAGE, TEXT, BUTTON }

    /**
     * Kart türüne göre gösterilecek parçalar: klasik metin (+düğme), görselli kartta görsel üstte; yalnız görsel
     * kartta metin yoktur. Görseli olmayan görsel kartı metne düşer (boş kart gösterilmez).
     */
    fun parts(card: HsContentCard): Set<Part> {
        val hasImage = !card.imageUrl.isNullOrBlank()
        val hasText = !card.title.isNullOrBlank() || !card.body.isNullOrBlank()
        val result = LinkedHashSet<Part>()
        when (card.type) {
            HsContentCardType.IMAGE_ONLY -> if (hasImage) result += Part.IMAGE else result += Part.TEXT
            HsContentCardType.CAPTIONED_IMAGE -> {
                if (hasImage) result += Part.IMAGE
                if (hasText || !hasImage) result += Part.TEXT
            }
            HsContentCardType.CLASSIC -> result += Part.TEXT
        }
        if (Part.TEXT in result && !card.buttonText.isNullOrBlank()) result += Part.BUTTON
        return result
    }

    /** Kartın en az yarısı görünürse gösterim sayılır. */
    fun countsAsImpression(visibleHeight: Int, totalHeight: Int): Boolean = totalHeight > 0 && visibleHeight * 2 >= totalHeight
}
