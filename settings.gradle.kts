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
        // Huawei HMS Push Kit (yalnızca `herosignal-hms` modülü); depo yalnızca Huawei gruplarına sorulur.
        maven {
            url = uri("https://developer.huawei.com/repo/")
            content {
                includeGroupByRegex("com\\.huawei(\\..*)?")
            }
        }
    }
}

rootProject.name = "herosignal-android"
include(":herosignal")
include(":herosignal-hms")
