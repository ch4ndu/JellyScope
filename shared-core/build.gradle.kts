// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Fetches the vendored VLCKit xcframework (gitignored) before the iOS
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
        namespace = "com.jellyscope.core"
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

    // VLCKit cinterop is iOS-only (never tvOS). Each iOS target points at
    // its matching xcframework slice; the cinterop task fetches the framework first.
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
    tvosArm64()
    tvosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kermit)
            api(libs.koin.core)
            api(libs.ktor.client.core)
            api(libs.kotlinx.datetime)
            api(libs.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.network)
            implementation(libs.ktor.client.resources)
            implementation(libs.ktor.client.serialization)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.koin.android)
            // API 33 and lower use the one app-owned foreground dataSync worker.
            // API 34+ is deliberately scheduled through UIDT JobScheduler only;
            // this dependency does not make WorkManager an SDK-34 fallback.
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.media3.datasource.okhttp)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(project(":android-libmpv"))
            implementation(libs.libvlc.all)
            // Jellyfin FFmpeg audio decoder from Maven Central (matches media3
            // version); enables the decoder-fallback ladder without a local build.
            implementation(libs.media3.ffmpeg.decoder)
        }

        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.ktor.client.okhttp)
        }

        named("androidHostTest") {
            dependencies {
                implementation(libs.androidx.test.core)
                implementation(libs.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
                implementation(libs.room.testing)
            }
        }

        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspTvosArm64", libs.room.compiler)
    add("kspTvosSimulatorArm64", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}
