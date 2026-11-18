import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

android {
    namespace = "com.pushizo.sdk.adm"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Saf JVM testleri: android.util.Log ve Context sarmalayıcıları varsayılan değer döndürür (Robolectric yok).
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Çekirdek SDK (kuyruk, abonelik, bildirim gösterimi); Amazon ADM bağımlılığı yalnızca bu modüldedir.
    api(project(":pushizo"))
    // Amazon ADM API saplaması: yalnızca derleme ve birim testi. Gerçek ADM sınıflarını Fire OS çalışma anında sağlar;
    // compileOnly olduğundan yayımlanan AAR'a ve POM'a girmez.
    compileOnly(project(":pushizo-adm-stubs"))

    testImplementation(project(":pushizo-adm-stubs"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.pushizo"
            artifactId = "pushizo-android-adm"
            version = "0.3.0"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Pushizo Android SDK - Amazon ADM")
                description.set("Pushizo Android SDK için Amazon Device Messaging (ADM) modülü (Fire OS cihazları)")
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
            url = uri("https://maven.pkg.github.com/exec0de/pushizo")
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
    description = "Maven Central (Central Portal) için imzalı ADM modülü yükleme paketini üretir."
    dependsOn("publishReleasePublicationToMavenCentralStagingRepository")
    from(mavenCentralStagingDir)
    exclude("**/maven-metadata*", "**/*.asc.md5", "**/*.asc.sha1", "**/*.asc.sha256", "**/*.asc.sha512")
    destinationDirectory.set(layout.buildDirectory.dir("maven-central"))
    archiveFileName.set("pushizo-android-adm-central-bundle.zip")
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
