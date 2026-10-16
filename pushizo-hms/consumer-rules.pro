# Çekirdek SDK, HMS sağlayıcısını yansıma ile bulur (HsPushProviders.HMS_PROVIDER_CLASS); servis manifest'ten referanslanır.
-keep class com.pushizo.sdk.hms.HmsPushProvider { <init>(); *; }
-keep class com.pushizo.sdk.hms.PushizoHmsMessageService { *; }

# Huawei HMS Core SDK'nın önerdiği kurallar (Push Kit dokümantasyonu).
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keep class com.huawei.hianalytics.** { *; }
-keep class com.huawei.updatesdk.** { *; }
-keep class com.huawei.hms.** { *; }
-dontwarn com.huawei.**
