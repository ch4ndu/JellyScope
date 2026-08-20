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
        google()
        // org.jellyfin.media3:media3-ffmpeg-decoder is published here.
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "jellyscope"

include(":shared-core")
include(":shared-tvos")
include(":shared-ui")
include(":android-libmpv")
include(":android-app")
include(":android-tv-app")
include(":desktop-app")
