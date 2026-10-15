package com.herosignal.sdk

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Varsayılan in-app çizimi: geçerli (resumed) Activity üstünde yerel View'larla kurulan Dialog (Compose gerekmez).
 * Görseller (carousel kartlarınınkiler dahil) gösterimden önce indirilir. HTML içerik yalıtılmış WebView'da çizilir
 * ([HsHtmlInAppView], ADR-0027). Activity durdurulursa (arka plan, döndürme) mesaj sessizce kaldırılır ve sonraki
 * Activity'de yeniden gösterilir (yeni gösterim sayılmaz).
 */
internal class HsInAppDialogRenderer(app: Application) : HsInAppRenderer, Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var resumed: WeakReference<Activity>? = null
    private var active: Active? = null

    private class Active(val message: HsInAppMessage, val handle: HsInAppHandle) {
        var images: Map<String, Bitmap?>? = null
        var dialog: Dialog? = null
        var host: WeakReference<Activity>? = null
        var closing = false
        var autoDismiss: Runnable? = null
        /** HTML içerikte o anki WebView; görünüm kaldırılınca yok edilir. */
        var web: HsHtmlInAppView? = null
    }

    init {
        app.registerActivityLifecycleCallbacks(this)
    }

    /** Ön plandaki Activity (aksiyon intent'leri için). */
    val currentActivity: Activity? get() = resumed?.get()?.takeIf { !it.isFinishing }

    override fun show(message: HsInAppMessage, handle: HsInAppHandle): Boolean {
        if (active != null) return false
        val a = Active(message, handle)
        active = a
        scope.launch {
            val urls = imageUrls(message.display)
            val images = withContext(Dispatchers.IO) { urls.associateWith { HsImages.download(it) } }
            if (active !== a) return@launch
            a.images = images
            attach(a)
        }
        return true
    }

    override fun close(messageId: String) {
        val a = active ?: return
        if (a.message.id != messageId) return
        a.closing = true
        val dialog = a.dialog
        if (dialog == null) finish(a) else runCatching { dialog.dismiss() }.onFailure { finish(a) }
    }

    private fun finish(a: Active) {
        a.autoDismiss?.let(main::removeCallbacks)
        a.autoDismiss = null
        a.web?.destroy()
        a.web = null
        if (active === a) active = null
        a.handle.closed()
    }

    private fun attach(a: Active) {
        if (a.dialog != null || a.images == null || a.closing) return
        val activity = currentActivity ?: return // onActivityResumed yeniden dener
        val dialog = runCatching { build(activity, a) }.getOrElse {
            HsLog.w("in-app görünümü oluşturulamadı", it)
            a.web?.destroy()
            a.web = null
            a.handle.dismissed()
            return
        }
        a.dialog = dialog
        a.host = WeakReference(activity)
        try {
            dialog.show()
        } catch (e: WindowManager.BadTokenException) {
            // Activity pencere belirteci geçersiz (kapanıyor): sonraki Activity'de denenir.
            a.dialog = null
            a.host = null
        }
    }

    /** Activity durunca görünüm olay üretmeden kaldırılır. */
    private fun detach(a: Active) {
        val dialog = a.dialog ?: return
        a.dialog = null
        a.host = null
        a.autoDismiss?.let(main::removeCallbacks)
        a.autoDismiss = null
        dialog.setOnDismissListener(null)
        runCatching { dialog.dismiss() }
        a.web?.destroy()
        a.web = null
    }

    // --- Görünüm ---

    private fun build(activity: Activity, a: Active): Dialog {
        val display = a.message.display
        val layout = display.layout
        val banner = layout == HsInAppLayout.BANNER_TOP || layout == HsInAppLayout.BANNER_BOTTOM
        val fullscreen = layout == HsInAppLayout.FULLSCREEN
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors = HsInAppStyle.resolve(display.backgroundColor, display.textColor, dark)
        val metrics = activity.resources.displayMetrics
        fun dp(v: Int): Int = (v * metrics.density).roundToInt()
        // Kapatılamaz ama düğmesiz ve otomatik kapanmayan mesajda kullanıcı mahsur kalmasın.
        val showClose = display.dismissible || (display.buttons.isEmpty() && display.autoDismissSeconds == null)
        val title = display.blocks.filterIsInstance<HsInAppBlock.Title>().firstOrNull()?.text
        val padding = dp(if (banner) 16 else 24)
        // Kartın dış genişliği (banner: kök dolgusu 12dp, modal: en fazla 420dp); carousel kart genişliği buradan çıkar.
        val cardWidth = when {
            banner -> metrics.widthPixels - dp(24)
            fullscreen -> metrics.widthPixels
            else -> min(metrics.widthPixels - dp(48), dp(420))
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (fullscreen) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
            val top = if (showClose && display.blocks.firstOrNull() !is HsInAppBlock.Image) dp(40) else padding
            setPadding(padding, top, padding, padding)
        }
        fun spaced(view: View, bottom: Int = 12) = content.addView(
            view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) },
        )
        for (block in display.blocks) when (block) {
            is HsInAppBlock.Image -> {
                val bitmap = a.images?.get(block.url) ?: continue
                spaced(ImageView(activity).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    maxHeight = dp(if (banner) 96 else 280)
                    if (block.alt.isNullOrBlank()) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    else contentDescription = block.alt
                })
            }
            is HsInAppBlock.Title -> spaced(TextView(activity).apply {
                text = block.text
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (banner) 17f else 20f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = if (banner) Gravity.START else Gravity.CENTER_HORIZONTAL
                ViewCompat.setAccessibilityHeading(this, true)
            }, bottom = 8)
            is HsInAppBlock.Body -> spaced(TextView(activity).apply {
                text = block.text
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (banner) 14f else 16f)
                gravity = if (banner) Gravity.START else Gravity.CENTER_HORIZONTAL
                setLineSpacing(0f, 1.15f)
            }, bottom = 16)
            is HsInAppBlock.Button -> spaced(button(activity, block, a, colors, ::dp), bottom = 8)
            is HsInAppBlock.Carousel -> spaced(carousel(activity, block, a, colors, cardWidth - 2 * padding, banner, ::dp), bottom = 16)
        }

        val card = FrameLayout(activity).apply {
            background = rounded(colors.background, if (fullscreen) 0f else dp(16).toFloat())
            elevation = dp(8).toFloat()
            isClickable = true // karta dokunma arka plana geçmez
            ViewCompat.setAccessibilityPaneTitle(this, title ?: activity.getString(R.string.hs_in_app_message))
        }
        if (display.isHtml) {
            val web = htmlView(activity, a, fullscreen, metrics.heightPixels, ::dp)
            val initial = dp(display.htmlHeight ?: if (banner) DEFAULT_BANNER_HTML_HEIGHT else DEFAULT_MODAL_HTML_HEIGHT)
            card.addView(web.webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else min(initial, maxHtmlHeight(metrics.heightPixels)),
            ))
            web.load()
        } else {
            val scroll = ScrollView(activity).apply { isFillViewport = fullscreen; addView(content) }
            card.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (showClose) {
            card.addView(TextView(activity).apply {
                text = "✕"
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                contentDescription = activity.getString(R.string.hs_in_app_close)
                isClickable = true
                isFocusable = true
                background = RippleDrawable(ColorStateList.valueOf(0x33000000), null, ColorDrawable(Color.WHITE))
                setOnClickListener { a.handle.dismissed() }
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        }

        val dialog: Dialog
        if (banner) {
            dialog = Dialog(activity)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val root = FrameLayout(activity).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
            root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(if (layout == HsInAppLayout.BANNER_TOP) Gravity.TOP else Gravity.BOTTOM)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                // Banner uygulamayı engellemez: dışarıdaki dokunuşlar ve klavye Activity'ye gider.
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        } else {
            dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
            val root = FrameLayout(activity)
            if (fullscreen) {
                root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                root.setBackgroundColor(SCRIM)
                root.setOnClickListener { if (display.dismissible) a.handle.dismissed() }
                root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val width = min(metrics.widthPixels - dp(48), dp(420))
                root.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    topMargin = dp(24)
                    bottomMargin = dp(24)
                })
            }
            dialog.setContentView(root)
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP && display.dismissible) a.handle.dismissed()
                true
            }
        }
        // Kapatma yalnızca SDK üzerinden (olay sırası: dismiss → willDismiss → kapanış → didDismiss).
        dialog.setCancelable(false)
        dialog.setOnShowListener {
            a.handle.displayed()
            display.autoDismissSeconds?.let { seconds ->
                val task = Runnable { a.handle.dismissed() }
                a.autoDismiss = task
                main.postDelayed(task, seconds * 1000L)
            }
        }
        dialog.setOnDismissListener { if (a.dialog === dialog) { a.dialog = null; finish(a) } }
        return dialog
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun button(activity: Activity, block: HsInAppBlock.Button, a: Active, colors: HsInAppStyle.Colors, dp: (Int) -> Int): Button {
        val (bg, fg) = HsInAppStyle.buttonColors(block, colors)
        return Button(activity).apply {
            text = block.text
            isAllCaps = false
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minHeight = dp(48)
            minimumHeight = dp(48)
            stateListAnimator = null
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(bg, dp(10).toFloat()), null)
            setOnClickListener { a.handle.buttonClicked(block.id) }
        }
    }

    /**
     * Carousel: yatay kaydırmalı kartlar. Birden fazla kart varsa kart genişliği alanın %85'idir (sonraki kart görünür,
     * kaydırılabildiği anlaşılır). Her kart ekran okuyucuda tek öğedir ("Kart 1/3: başlık"); düğmesi ayrıca odaklanır.
     */
    private fun carousel(
        activity: Activity,
        block: HsInAppBlock.Carousel,
        a: Active,
        colors: HsInAppStyle.Colors,
        available: Int,
        banner: Boolean,
        dp: (Int) -> Int,
    ): View {
        val total = block.cards.size
        val width = if (total > 1) (available * 0.85f).roundToInt() else available
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        block.cards.forEachIndexed { index, card ->
            val view = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(colors.background)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), (colors.text and 0x00FFFFFF) or 0x33000000)
                }
                val label = activity.getString(R.string.hs_in_app_carousel_card, index + 1, total, card.title)
                contentDescription = if (card.body.isNullOrBlank()) label else "$label. ${card.body}"
                ViewCompat.setScreenReaderFocusable(this, true)
            }
            fun add(child: View, bottom: Int, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) = view.addView(
                child,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, weight).apply { bottomMargin = dp(bottom) },
            )
            card.imageUrl?.let { a.images?.get(it) }?.let { bitmap ->
                add(ImageView(activity).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, bottom = 8, height = dp(if (banner) 80 else 140))
            }
            add(TextView(activity).apply {
                text = card.title
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (banner) 15f else 17f)
                setTypeface(typeface, Typeface.BOLD)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, bottom = 4)
            card.body?.takeIf { it.isNotBlank() }?.let { body ->
                add(TextView(activity).apply {
                    text = body
                    setTextColor(colors.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (banner) 13f else 15f)
                    setLineSpacing(0f, 1.15f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, bottom = 8)
            }
            card.button?.let { b ->
                // Düğmeler kart altına hizalanır (kartlar aynı yükseklikte).
                add(View(activity), bottom = 0, height = 0, weight = 1f)
                add(button(activity, b, a, colors, dp), bottom = 0)
            }
            row.addView(view, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                if (index < total - 1) marginEnd = dp(12)
            })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = total == 1
            addView(row)
        }
    }

    /** HTML içerik: yalıtılmış WebView; köprü eylemleri handle'a, `resize` yükseklik güncellemesine dönüşür. */
    private fun htmlView(activity: Activity, a: Active, fullscreen: Boolean, screenHeight: Int, dp: (Int) -> Int): HsHtmlInAppView {
        a.web?.destroy()
        lateinit var web: HsHtmlInAppView
        web = HsHtmlInAppView(activity, a.message.display.html.orEmpty(), a.message.language) { action ->
            if (a.web !== web) return@HsHtmlInAppView
            when (action) {
                HsHtmlBridgeAction.Ready -> Unit
                HsHtmlBridgeAction.Close -> a.handle.dismissed()
                is HsHtmlBridgeAction.Click -> a.handle.htmlClicked(action.actionId, action.url)
                is HsHtmlBridgeAction.AddTag -> a.handle.htmlAddTag(action.key, action.value)
                is HsHtmlBridgeAction.TrackEvent -> a.handle.htmlTrackEvent(action.name, action.props)
                is HsHtmlBridgeAction.Resize -> if (!fullscreen) {
                    val view = web.webView
                    val height = min(dp(action.height), maxHtmlHeight(screenHeight))
                    val params = view.layoutParams
                    if (params != null && params.height != height) {
                        params.height = height
                        view.layoutParams = params
                    }
                }
            }
        }
        a.web = web
        return web
    }

    /** Banner/modal HTML yüksekliği üst sınırı: ekran yüksekliğinin %90'ı. */
    private fun maxHtmlHeight(screenHeight: Int): Int = (screenHeight * 0.9f).roundToInt()

    // --- Activity takibi ---

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        active?.let { if (it.dialog == null) attach(it) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityStopped(activity: Activity) {
        val a = active ?: return
        if (a.host?.get() === activity) {
            detach(a)
            if (a.closing) finish(a)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val SCRIM = 0x99000000.toInt()
        /** `html_height` verilmediğinde ilk yükseklik (dp); köprünün `resize` bildirimiyle güncellenir. */
        const val DEFAULT_BANNER_HTML_HEIGHT = 120
        const val DEFAULT_MODAL_HTML_HEIGHT = 360

        /** Gösterimden önce indirilecek görseller: görsel blokları ve carousel kart görselleri (HTML'de yok). */
        fun imageUrls(display: HsInAppDisplay): List<String> = display.blocks.flatMap { block ->
            when (block) {
                is HsInAppBlock.Image -> listOf(block.url)
                is HsInAppBlock.Carousel -> block.cards.mapNotNull { it.imageUrl }
                else -> emptyList()
            }
        }.distinct()
    }
}
