// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Fetches the vendored VLCKit xcframework (gitignored) before the Apple
// cinterop needs its headers. Idempotent: the script skips when already present.
val fetchVlcKit =
    tasks.register<Exec>("fetchVlcKit") {
        commandLine("bash", rootProject.file("scripts/fetch-vlckit.sh").absolutePath)
    }

val vlcKitXcframework =
    rootProject.file("ios-app/Frameworks/VLCKit.xcframework")

kotlin {
    jvmToolchain(
        libs.versions.jdkToolchain
            .get()
            .toInt(),
    )

    android {
        namespace = "com.jellyscope.player.runtime"
        compileSdk =
            libs.versions.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        withHostTest {}
    }
    jvm()

    // Each Apple target uses its matching slice; appleMain consumes the commonized API.
    fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.vlcKitCinterop(slice: String) {
        val cinterop =
            compilations.getByName("main").cinterops.create("vlckit") {
                defFile(project.file("src/nativeInterop/cinterop/vlckit.def"))
                compilerOpts("-F" + vlcKitXcframework.resolve(slice).absolutePath)
            }
        tasks.named(cinterop.interopProcessingTaskName).configure { dependsOn(fetchVlcKit) }
    }
    iosArm64 { vlcKitCinterop("ios-arm64") }
    iosSimulatorArm64 { vlcKitCinterop("ios-arm64_x86_64-simulator") }
    tvosArm64 { vlcKitCinterop("tvos-arm64") }
    tvosSimulatorArm64 { vlcKitCinterop("tvos-arm64_x86_64-simulator") }

    sourceSets {
        commonMain.dependencies {
            api(project(":network"))
            api(project(":playback-policy"))
            implementation(libs.ktor.http)
            implementation(libs.atomicfu)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            api(libs.koin.core)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.kotlinxCoroutines.get()}")
            implementation(libs.media3.datasource.okhttp)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(project(":android-libmpv"))
            implementation(libs.libvlc.all)
            implementation(libs.media3.ffmpeg.decoder)
        }

        jvmMain.dependencies {
            implementation(libs.jna)
        }

        jvmTest.dependencies {
            implementation(project(":jellyfin-api"))
        }

        named("androidHostTest") {
            dependencies {
                implementation(project(":jellyfin-api"))
                implementation(libs.androidx.test.core)
                implementation(libs.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
            }
        }
    }
}
