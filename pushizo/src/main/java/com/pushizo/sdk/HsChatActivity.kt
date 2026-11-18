package com.pushizo.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference

/**
 * Tam ekran hazır sohbet ekranı ([HsChatView]); [PushizoChat.show] ile açılır, [PushizoChat.hide] ya da kapat
 * düğmesi/geri tuşuyla kapanır. Dosya ekleme sistem dosya seçicisiyle yapılır (ek izin gerektirmez). Sistem çubukları
 * ve klavye için kenar boşlukları uygulanır. Görünümü uygulamanın temasına uydurmak için manifest'te bu Activity'nin
 * `android:theme` değeri ezilebilir (varsayılan `@style/HsChatTheme`).
 */
class HsChatActivity : Activity() {
    private lateinit var chat: HsChatView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Pushizo.ensureInitialized(applicationContext)) {
            HsLog.w("HsChatActivity: Pushizo.initialize çağrılmadı; sohbet ekranı açılamadı")
            finish()
            return
        }
        chat = HsChatView(this)
        chat.onCloseRequested = { finish() }
        chat.onAttachRequested = { pickAttachment() }
        setContentView(chat)
        setTitle(R.string.hs_chat_title)

        // İçerik sistem çubuklarının ve klavyenin altında kalmasın (Android 15 kenardan kenara çizimi zorunlu kılar).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(chat) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, chat).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        current = WeakReference(this)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun pickAttachment() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, chat.attachmentMimeTypes)
        }
        try {
            // Bağımlılık eklememek için düz Activity sonucu kullanılır (androidx.activity gerekmez).
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_ATTACHMENT)
        } catch (e: ActivityNotFoundException) {
            HsLog.w("Dosya seçici açılamadı", e)
        }
    }

    @Deprecated("Activity sonucu: dosya seçici")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ATTACHMENT || resultCode != RESULT_OK) return
        data?.data?.let(chat::sendAttachment)
    }

    internal companion object {
        private const val REQUEST_ATTACHMENT = 0x4854
        @Volatile private var current: WeakReference<HsChatActivity>? = null

        fun intent(context: Context): Intent = Intent(context, HsChatActivity::class.java).apply {
            // Activity dışı bağlamdan (Application, servis) açılabilsin; açık ekran varsa yenisi açılmaz.
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        /** Açık sohbet ekranını kapatır; açıksa true. */
        fun finishCurrent(): Boolean {
            val activity = current?.get() ?: return false
            activity.runOnUiThread { if (!activity.isFinishing) activity.finish() }
            return true
        }
    }
}
