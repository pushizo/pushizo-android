import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

android {
    namespace = "com.pushizo.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"0.1.0\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // Platformlar arası ortak test vektörleri (sdk/testdata).
            it.systemProperty("pushizo.testdata", file("../../testdata").absolutePath)
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            // Maven Central her yayında -sources ve -javadoc jar'ı ister.
            withJavadocJar()
        }
    }
}

dependencies {
    // Token alma/yenileme ve mesaj alma (müşteri google-services.json ekler).
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.pushizo"
            artifactId = "pushizo-android"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Pushizo Android SDK")
                description.set("Pushizo Android SDK: FCM kaydı, kullanıcı/tag yönetimi, olaylar, in-app mesajlar ve bildirim kutusu")
                url.set("https://docs.pushizo.com/docs/sdk/android")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://github.com/pushizo/pushizo-android/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("pushizo")
                        name.set("Pushizo")
                        url.set("https://github.com/pushizo/pushizo-android")
                    }
                }
                scm {
                    url.set("https://github.com/pushizo/pushizo-android")
                    connection.set("scm:git:https://github.com/pushizo/pushizo-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/pushizo/pushizo-android.git")
                }
            }
        }
    }
    repositories {
        // GitHub Packages (sdk-release.yml `maven` işi): GITHUB_ACTOR/GITHUB_TOKEN ortam değişkenleri ya da gradle.properties
        // `gpr.user`/`gpr.key`.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/exec0de/pushizo")
            credentials {
                username = (project.findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                password = (project.findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
            }
        }
        // Maven Central: imzalı yayın önce bu yerel klasöre yazılır, `mavenCentralBundle` ile zip'lenir ve Sonatype Central
        // Portal Publisher API'sine yüklenir (sdk-release.yml `maven` işi; docs/sdk/publishing.md).
        maven {
            name = "MavenCentralStaging"
            url = uri(layout.buildDirectory.dir("maven-central/staging"))
        }
    }
}

// İmza anahtarı yalnızca yayında verilir (ORG_GRADLE_PROJECT_signingInMemoryKey / signingInMemoryKeyPassword: ASCII-armored
// GPG özel anahtarı ve parolası). Anahtar yoksa yerel derleme ve GitHub Packages yayını imzasız sürer; Central paketi üretilmez.
val signingKey: String? = providers.gradleProperty("signingInMemoryKey").orNull?.takeIf { it.isNotBlank() }
val signingKeyPassword: String? = providers.gradleProperty("signingInMemoryKeyPassword").orNull

signing {
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingKeyPassword)
        sign(publishing.publications["release"])
    }
}

val mavenCentralStagingDir = layout.buildDirectory.dir("maven-central/staging")

// Önceki sürümlerin dosyaları Central paketine karışmasın diye ara klasör her yayından önce boşaltılır.
val cleanMavenCentralStaging by tasks.registering(Delete::class) {
    delete(mavenCentralStagingDir)
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (name == "publishReleasePublicationToMavenCentralStagingRepository") {
        dependsOn(cleanMavenCentralStaging)
    }
}

// Central Portal yükleme paketi: Maven dizin düzenindeki .aar/.pom/.module, -sources/-javadoc jar'ları, .asc imzaları ve
// sağlama toplamları. maven-metadata dosyaları Central tarafından üretildiğinden, imza dosyalarının sağlama toplamları da
// gerekmediğinden pakete alınmaz.
tasks.register<Zip>("mavenCentralBundle") {
    group = "publishing"
    description = "Maven Central (Central Portal) için imzalı yükleme paketini üretir."
    dependsOn("publishReleasePublicationToMavenCentralStagingRepository")
    from(mavenCentralStagingDir)
    exclude("**/maven-metadata*", "**/*.asc.md5", "**/*.asc.sha1", "**/*.asc.sha256", "**/*.asc.sha512")
    destinationDirectory.set(layout.buildDirectory.dir("maven-central"))
    archiveFileName.set("pushizo-android-central-bundle.zip")
    val signed = signingKey != null
    doFirst {
        if (!signed) {
            throw GradleException(
                "Maven Central paketi imzalı olmalı: signingInMemoryKey ve signingInMemoryKeyPassword Gradle özelliklerini verin.",
            )
        }
    }
}

// `kotlinOptions` KGP 2.2'de kaldırıldı; `compilerOptions` 2.0+ ile uyumludur (örnek uygulamalar KGP 2.2 kullanır).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
