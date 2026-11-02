pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Huawei HMS Push Kit (yalnızca `pushizo-hms` modülü); depo yalnızca Huawei gruplarına sorulur.
        maven {
            url = uri("https://developer.huawei.com/repo/")
            content {
                includeGroupByRegex("com\\.huawei(\\..*)?")
            }
        }
    }
}

rootProject.name = "pushizo-android"
include(":pushizo")
include(":pushizo-hms")
include(":pushizo-adm")
// Amazon ADM API'sinin yalnızca derleme zamanı saplaması (yayımlanmaz; bkz. pushizo-adm/stubs/build.gradle.kts).
include(":pushizo-adm-stubs")
project(":pushizo-adm-stubs").projectDir = file("pushizo-adm/stubs")
