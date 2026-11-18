# Çekirdek SDK, ADM kayıt sınıfını yansıma ile bulur (HsPushProviders.ADM_REGISTRAR_CLASS); işleyiciler ve alıcı
# manifest'ten referanslanır. ADM sınıfları R8 sırasında bulunmayabilir (uygulama jar'ı eklemediyse): üst sınıfı
# bilinmeyen işleyicilerin geçersiz kılınan metotları (onMessage, onRegistered…) bu yüzden adlarıyla korunur.
-keep class com.pushizo.sdk.adm.AdmRegistrar { <init>(); *; }
-keep class com.pushizo.sdk.adm.PushizoAdmMessageReceiver { *; }
-keep class com.pushizo.sdk.adm.PushizoAdmMessageHandler { *; }
-keep class com.pushizo.sdk.adm.PushizoAdmLegacyMessageHandler { *; }

# Amazon ADM'nin önerdiği kurallar: kütüphane cihazdan gelir, uygulamada yalnızca derleme zamanı bağımlılığıdır.
-dontwarn com.amazon.device.messaging.**
-keep class com.amazon.device.messaging.** { *; }
-keep public class * extends com.amazon.device.messaging.ADMMessageReceiver
-keep public class * extends com.amazon.device.messaging.ADMMessageHandlerBase
-keep public class * extends com.amazon.device.messaging.ADMMessageHandlerJobBase
