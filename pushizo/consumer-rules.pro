# Pushizo SDK public API korunur; FCM servisi manifest üzerinden referanslanır.
-keep class com.pushizo.sdk.Pushizo { *; }
-keep class com.pushizo.sdk.Pushizo$* { *; }
-keep class com.pushizo.sdk.PushizoMessagingService { *; }
-keep class com.pushizo.sdk.NotificationOpenedReceiver { *; }
-keep class com.pushizo.sdk.FlushWorker { *; }
# HTML in-app köprüsü: WebView yalnızca @JavascriptInterface metodunu çağırır; küçültmede adı korunmalı.
-keepclassmembers class com.pushizo.sdk.HsHtmlNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
