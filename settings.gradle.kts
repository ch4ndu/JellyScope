// SPDX-License-Identifier: MPL-2.0

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                ivy {
                    name = "JellyScopeMpvReleases"
                    url = uri("https://github.com/ch4ndu/jellyscope-mpv-android/releases/download")
                    patternLayout {
                        artifact("v[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("io.github.ch4ndu", "libmpv-native") }
        }
        google()
        // org.jellyfin.media3:media3-ffmpeg-decoder is published here.
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "jellyscope"

include(":core-model")
include(":diagnostics")
include(":platform")
include(":network")
include(":player")
include(":playback-policy")
include(":shared-core")
include(":shared-tvos")
include(":ui-foundation")
include(":settings-account")
include(":browse")
include(":playback")
include(":detail")
include(":auth")
include(":downloads")
include(":shared-ui")
include(":ios-framework")
include(":android-libmpv")
include(":android-app")
include(":android-tv-app")
include(":desktop-app")
