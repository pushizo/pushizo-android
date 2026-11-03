# Pushizo SDK public API korunur; FCM servisi manifest üzerinden referanslanır.
-keep class com.pushizo.sdk.Pushizo { *; }
-keep class com.pushizo.sdk.Pushizo$* { *; }
-keep class com.pushizo.sdk.PushizoMessagingService { *; }
# Firebase Installation ID kipi: register() yansımayla çağrılır (firebase-messaging 25.1.2+).
-keepclassmembers class com.google.firebase.messaging.FirebaseMessaging {
    public com.google.android.gms.tasks.Task register();
}
-keep class com.pushizo.sdk.NotificationOpenedReceiver { *; }
-keep class com.pushizo.sdk.FlushWorker { *; }
# HTML in-app köprüsü: WebView yalnızca @JavascriptInterface metodunu çağırır; küçültmede adı korunmalı.
-keepclassmembers class com.pushizo.sdk.HsHtmlNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
