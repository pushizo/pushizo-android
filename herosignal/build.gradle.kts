import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.herosignal.sdk"
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
            it.systemProperty("herosignal.testdata", file("../../testdata").absolutePath)
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
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
            groupId = "com.herosignal"
            artifactId = "herosignal-android"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("HeroSignal Android SDK")
                description.set("HeroSignal Android SDK: FCM kaydı, kullanıcı/tag yönetimi, olaylar, in-app mesajlar ve bildirim kutusu")
                url.set("https://github.com/exec0de/herosignal/tree/main/sdk/android")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://github.com/exec0de/herosignal/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("herosignal")
                        name.set("HeroSignal")
                        url.set("https://github.com/exec0de/herosignal")
                    }
                }
                scm {
                    url.set("https://github.com/exec0de/herosignal")
                    connection.set("scm:git:https://github.com/exec0de/herosignal.git")
                    developerConnection.set("scm:git:ssh://git@github.com/exec0de/herosignal.git")
                }
            }
        }
    }
    repositories {
        // GitHub Packages (sdk-release.yml `maven` işi): GITHUB_ACTOR/GITHUB_TOKEN ortam değişkenleri ya da gradle.properties
        // `gpr.user`/`gpr.key`. Maven Central için docs/sdk/publishing.md (Sonatype hesabı + GPG imzası gerekir).
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/exec0de/herosignal")
            credentials {
                username = (project.findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                password = (project.findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// `kotlinOptions` KGP 2.2'de kaldırıldı; `compilerOptions` 2.0+ ile uyumludur (örnek uygulamalar KGP 2.2 kullanır).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
