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
 * Görseller gösterimden önce indirilir. Activity durdurulursa (arka plan, döndürme) mesaj sessizce kaldırılır ve
 * sonraki Activity'de yeniden gösterilir (yeni gösterim sayılmaz).
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
            val urls = message.display.blocks.filterIsInstance<HsInAppBlock.Image>().map { it.url }.distinct()
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
        if (active === a) active = null
        a.handle.closed()
    }

    private fun attach(a: Active) {
        if (a.dialog != null || a.images == null || a.closing) return
        val activity = currentActivity ?: return // onActivityResumed yeniden dener
        val dialog = runCatching { build(activity, a) }.getOrElse {
            HsLog.w("in-app görünümü oluşturulamadı", it)
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

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (fullscreen) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
            val top = if (showClose && display.blocks.firstOrNull() !is HsInAppBlock.Image) dp(40) else dp(if (banner) 16 else 24)
            setPadding(dp(if (banner) 16 else 24), top, dp(if (banner) 16 else 24), dp(if (banner) 16 else 24))
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
            is HsInAppBlock.Button -> {
                val (bg, fg) = HsInAppStyle.buttonColors(block, colors)
                spaced(Button(activity).apply {
                    text = block.text
                    isAllCaps = false
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    minHeight = dp(48)
                    minimumHeight = dp(48)
                    stateListAnimator = null
                    background = RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(bg, dp(10).toFloat()), null)
                    setOnClickListener { a.handle.buttonClicked(block.id) }
                }, bottom = 8)
            }
        }

        val card = FrameLayout(activity).apply {
            background = rounded(colors.background, if (fullscreen) 0f else dp(16).toFloat())
            elevation = dp(8).toFloat()
            isClickable = true // karta dokunma arka plana geçmez
            ViewCompat.setAccessibilityPaneTitle(this, title ?: activity.getString(R.string.hs_in_app_message))
        }
        val scroll = ScrollView(activity).apply { isFillViewport = fullscreen; addView(content) }
        card.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
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
    }
}
