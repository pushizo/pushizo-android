<!-- pushizo:header -->
<p align="center">
  <a href="https://pushizo.com"><img src="https://raw.githubusercontent.com/pushizo/.github/main/profile/assets/mark.svg" alt="Pushizo" width="56" height="56"></a>
</p>
<p align="center">
  <a href="https://docs.pushizo.com/docs/sdk/android"><img src="https://img.shields.io/badge/docs-docs.pushizo.com-5b4bdb" alt="Docs"></a>
  <a href="https://docs.pushizo.com/docs/api"><img src="https://img.shields.io/badge/API-reference-b44fc4" alt="API reference"></a>
  <a href="https://github.com/pushizo/pushizo-android/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT-16130f" alt="License: MIT"></a>
</p>
<p align="center"><a href="#english">English</a> · <a href="#türkçe">Türkçe</a></p>

## English

**Pushizo Android SDK** — Kotlin SDK for push via FCM (and Huawei HMS Push Kit on devices without Google Play Services), in-app messages, notification inbox, users, tags and aliases, email and SMS subscriptions, preferences, notification channels, location and live chat. minSdk 23; usable from Java.

> **Status:** Publishing to Maven Central is in progress; until then you can build from this repository.<br>
> **Durum:** Maven Central yayını hazırlanıyor; o zamana kadar bu depodan derleyerek kullanabilirsiniz.

```kotlin
dependencies {
    implementation("com.pushizo:pushizo-android:0.1.0")
    // Huawei (optional): implementation("com.pushizo:pushizo-android-hms:0.1.0")
}
```

The detailed guide below is in Turkish; code samples are language-neutral. Full documentation:
[docs.pushizo.com/docs/sdk/android](https://docs.pushizo.com/docs/sdk/android) · API reference: [docs.pushizo.com/docs/api](https://docs.pushizo.com/docs/api).

## Türkçe
<!-- /pushizo:header -->

# Pushizo Android SDK

Kotlin ile yazılmış Android SDK'sı: FCM üzerinden push, Google Play Services olmayan Huawei/Honor cihazlarında HMS Push
Kit, in-app mesajlar, bildirim kutusu, kullanıcı/tag/alias, e-posta ve SMS abonelikleri, kullanıcı tercihleri,
bildirim kanalları, konum ve canlı sohbet. `minSdk 23`, AGP 8. Java'dan da kullanılabilir.

| Modül | Maven koordinatı | Açıklama |
|-------|------------------|----------|
| [`pushizo`](pushizo) | `com.pushizo:pushizo-android` | Çekirdek SDK (FCM) |
| [`pushizo-hms`](pushizo-hms) | `com.pushizo:pushizo-android-hms` | İsteğe bağlı Huawei HMS Push Kit modülü |

## Kurulum

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.pushizo:pushizo-android:0.1.0")
    // Huawei cihazları için (isteğe bağlı):
    // implementation("com.pushizo:pushizo-android-hms:0.1.0")
}
```

Maven Central yayını tamamlanana kadar bu depodan yerel Maven deposuna kurabilirsiniz:

```bash
git clone https://github.com/pushizo/pushizo-android
gradle -p pushizo-android :pushizo:publishToMavenLocal
```

ve uygulamanın `settings.gradle.kts` dosyasındaki depolara `mavenLocal()` ekleyin.

Firebase projesi ve `google-services.json` gerekir; Pushizo panelinde **Kanallar → Android Push** altında Firebase
servis hesabı JSON'unu yükleyip doğrulayın.

## Başlatma

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Pushizo.initialize(this, "app_…")
        Pushizo.Notifications.addClickListener { event -> /* event.notification, event.actionId, event.url */ }
    }
}

// Android 13+ bildirim izni (bir Activity içinden):
Pushizo.Notifications.requestPermission(activity)

// Kullanıcıyı kendi kimliğinizle eşleyin:
Pushizo.login("user_42")
```

Manifest'e ek bir şey gerekmez: izinler, mesajlaşma servisi ve tıklama alıcısı manifest birleştirmeyle eklenir.

Huawei kurulumu, izin gözlemcisi, JWT ile kimlik doğrulama, kanallar, in-app mesajlar, bildirim kutusu, konum, canlı
sohbet ve sorun giderme: [docs.pushizo.com/docs/sdk/android](https://docs.pushizo.com/docs/sdk/android).

<!-- pushizo:footer -->
---

## Support · Destek

- **Guide · Rehber:** [docs.pushizo.com/docs/sdk/android](https://docs.pushizo.com/docs/sdk/android)
- **API reference & OpenAPI · API referansı ve OpenAPI:** [docs.pushizo.com/docs/api](https://docs.pushizo.com/docs/api) ·
  [pushizo-openapi](https://github.com/pushizo/pushizo-openapi)
- **Bugs & ideas · Hata ve öneri:** [Issues](https://github.com/pushizo/pushizo-android/issues)
- **Security · Güvenlik:** please do not open public issues; email [security@pushizo.com](mailto:security@pushizo.com) ·
  herkese açık issue açmayın, bu adrese yazın.
- **Help · Destek:** [support@pushizo.com](mailto:support@pushizo.com)

This repository is mirrored automatically from the Pushizo main repository on every release. Please open an issue
instead of a pull request; fixes land in the main repository and ship with the next release.
Bu depo her sürümde Pushizo ana deposundan otomatik yansıtılır. Pull request yerine issue açın; düzeltmeler ana depoda
yapılır ve bir sonraki sürümle buraya gelir.

Released under the MIT License · MIT lisanslıdır — [LICENSE](https://github.com/pushizo/pushizo-android/blob/main/LICENSE).
<!-- /pushizo:footer -->
