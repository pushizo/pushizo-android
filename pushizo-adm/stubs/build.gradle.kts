// Amazon Device Messaging (ADM) API saplaması: yalnızca `pushizo-adm` modülünün derlenmesi ve birim testleri içindir.
// Gerçek `amazon-device-messaging-1.x.jar` Maven deposunda yoktur ve Amazon dağıtım koşulları gereği SDK ile paketlenmez;
// Fire OS cihazı ADM sınıflarını çalışma anında sağlar (`amazon:enable-feature`). Bu modül `compileOnly` /
// `testImplementation` olarak kullanılır, `maven-publish` uygulanmaz ve yayımlanan AAR'a girmez. Sınıflar yalnızca
// `pushizo-adm`'nin kullandığı imzaları içerir; gövdeler `RuntimeException("Stub")` fırlatır.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.amazon.device.messaging.stubs"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
