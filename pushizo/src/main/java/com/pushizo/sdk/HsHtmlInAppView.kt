package com.pushizo.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * HTML in-app mesajını yalıtılmış bir WebView'da çizer (ADR-0027). Güvenlik katmanları:
 * - Belgedeki CSP ağ isteklerini (fetch/XHR/WebSocket) ve dış betik/stil yüklemeyi engeller; yalnızca https/data görsel.
 * - WebView: yalnızca JavaScript açık; dosya/içerik erişimi, DOM depolama, veritabanı, konum, karışık içerik kapalı.
 * - [WebViewClient.shouldOverrideUrlLoading] her gezinmeyi iptal eder (bağlantılar köprü üzerinden `click` olur);
 *   [WebViewClient.shouldInterceptRequest] https görseller dışındaki tüm istekleri boş yanıtla keser (CSP'ye ek savunma).
 * - [WebChromeClient] yeni pencere, dosya seçici, izin ve JS diyalog isteklerini reddeder.
 * - Yerel arayüz yalnızca tek `postMessage(json)` metodunu açar; mesaj ana iş parçacığında [HsHtmlBridge] ile
 *   (işaret, sürüm, gösterime özel nonce, şema) doğrulanır, uygulamaya yalnızca doğrulanmış eylem ulaşır.
 */
internal class HsHtmlInAppView(
    context: Context,
    private val html: String,
    private val language: String?,
    private val onAction: (HsHtmlBridgeAction) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    /** Her gösterim (ve yeniden ekleme) için yeni nonce; köprü betiğine gömülür. */
    private val nonce = HsHtmlBridge.newNonce()
    @Volatile private var destroyed = false

    val webView: WebView = WebView(context)

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @Suppress("DEPRECATION")
    private fun configure() {
        webView.settings.apply {
            // Yalnızca köprü betiği ve mesajın satır içi betikleri için; CSP dış betiği engeller.
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Safe Browsing varsayılan (açık) bırakılır.
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.addJavascriptInterface(HsHtmlNativeBridge(::receive), HsHtmlBridge.NATIVE_INTERFACE)
    }

    /** Belgeyi yükler: base URL yok (`about:blank`, opak köken), geçmiş kaydı yok. */
    fun load() {
        if (destroyed) return
        webView.loadDataWithBaseURL(null, HsHtmlBridge.document(html, nonce, language), "text/html", "utf-8", null)
    }

    /** Görünüm kaldırılınca çağrılır: köprü ayrılır, WebView kaynakları bırakılır. Tekrar çağrı zararsızdır. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        runCatching {
            webView.removeJavascriptInterface(HsHtmlBridge.NATIVE_INTERFACE)
            webView.stopLoading()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }.onFailure { HsLog.d("HTML in-app WebView kapatılamadı") }
    }

    /** JavaBridge iş parçacığından gelir: ana iş parçacığına aktarılıp doğrulanır. */
    private fun receive(json: String) {
        if (destroyed || json.length > HsHtmlBridge.MAX_MESSAGE_LENGTH) return
        main.post {
            if (destroyed) return@post
            val action = HsHtmlBridge.parse(json, nonce)
            if (action == null) {
                HsLog.d("HTML in-app köprüsü: geçersiz mesaj yok sayıldı")
                return@post
            }
            onAction(action)
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        @Deprecated("API 24 öncesi cihazlar için", ReplaceWith("shouldOverrideUrlLoading(view, request)"))
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (allowed(request.url, request.isForMainFrame, request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value)) null else blocked()

        @Deprecated("API 21-23 için", ReplaceWith("shouldInterceptRequest(view, request)"))
        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
            if (allowed(Uri.parse(url), mainFrame = false, accept = null)) null else blocked()
    }

    private class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false

        override fun onShowFileChooser(view: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
            filePathCallback.onReceiveValue(null)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean = cancel(result)
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean = cancel(result)
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean = cancel(result)
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean = cancel(result)

        private fun cancel(result: JsResult): Boolean {
            result.cancel()
            return true
        }
    }

    private companion object {
        /** Yalnızca alt kaynak olarak https görseller (ve data: URI'leri) geçer; `Accept` başlığı görsel değilse kesilir. */
        fun allowed(uri: Uri, mainFrame: Boolean, accept: String?): Boolean {
            if (mainFrame) return false
            return when (uri.scheme?.lowercase()) {
                "data" -> true
                "https" -> accept == null || accept.contains("image/")
                else -> false
            }
        }

        fun blocked() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}

/** WebView'a eklenen tek yerel nesne: yalnızca `postMessage(json)` (başka metot JavaScript'e açılmaz). */
internal class HsHtmlNativeBridge(private val receiver: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(json: String?) {
        if (json != null) receiver(json)
    }
}
