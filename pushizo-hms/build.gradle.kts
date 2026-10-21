import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

android {
    namespace = "com.pushizo.sdk.hms"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Çekirdek SDK (FCM, kuyruk, bildirim gösterimi); HMS bağımlılığı yalnızca bu modüldedir.
    api(project(":pushizo"))
    // Huawei Push Kit (depo: https://developer.huawei.com/repo/); HmsInstanceId, HmsMessageService, HuaweiApiAvailability.
    implementation("com.huawei.hms:push:6.13.0.301")
    // App ID'yi agconnect-services.json'dan okumak için (Push Kit 6.13'ün kendi kullandığı sürüm).
    implementation("com.huawei.agconnect:agconnect-core:1.9.1.304")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.pushizo"
            artifactId = "pushizo-android-hms"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Pushizo Android SDK - Huawei HMS")
                description.set("Pushizo Android SDK için Huawei HMS Push Kit sağlayıcısı (Google Play Services olmayan cihazlar)")
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
        // Çekirdek modülle aynı GitHub Packages deposu (`gpr.user`/`gpr.key` veya GITHUB_ACTOR/GITHUB_TOKEN).
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/pushizo/pushizo")
            credentials {
                username = (project.findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                password = (project.findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
            }
        }
        // Maven Central: çekirdek modülle aynı akış (yerel klasör → `mavenCentralBundle` → Central Portal).
        maven {
            name = "MavenCentralStaging"
            url = uri(layout.buildDirectory.dir("maven-central/staging"))
        }
    }
}

// İmza anahtarı yalnızca yayında verilir (çekirdek modülle aynı Gradle özellikleri); yoksa yerel derleme imzasız sürer.
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

val cleanMavenCentralStaging by tasks.registering(Delete::class) {
    delete(mavenCentralStagingDir)
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (name == "publishReleasePublicationToMavenCentralStagingRepository") {
        dependsOn(cleanMavenCentralStaging)
    }
}

tasks.register<Zip>("mavenCentralBundle") {
    group = "publishing"
    description = "Maven Central (Central Portal) için imzalı HMS modülü yükleme paketini üretir."
    dependsOn("publishReleasePublicationToMavenCentralStagingRepository")
    from(mavenCentralStagingDir)
    exclude("**/maven-metadata*", "**/*.asc.md5", "**/*.asc.sha1", "**/*.asc.sha256", "**/*.asc.sha512")
    destinationDirectory.set(layout.buildDirectory.dir("maven-central"))
    archiveFileName.set("pushizo-android-hms-central-bundle.zip")
    val signed = signingKey != null
    doFirst {
        if (!signed) {
            throw GradleException(
                "Maven Central paketi imzalı olmalı: signingInMemoryKey ve signingInMemoryKeyPassword Gradle özelliklerini verin.",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
