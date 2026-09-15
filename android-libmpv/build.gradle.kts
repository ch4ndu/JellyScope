// SPDX-License-Identifier: MPL-2.0

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.library)
}

private val pinnedMpvAar =
    configurations.create("pinnedMpvAar") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }

dependencies {
    add(pinnedMpvAar.name, variantOf(libs.libmpv) { artifactType("aar") })
}

private val extractedNativeDir =
    layout.buildDirectory.dir("generated/pinned-mpv/jniLibs")

private val extractPinnedMpvNative =
    tasks.register<Sync>("extractPinnedMpvNative") {
        description = "Extracts the pinned libmpv AAR native inputs without its original bridge."
        group = "android mpv"
        inputs.files(pinnedMpvAar)
        outputs.dir(extractedNativeDir)
        duplicatesStrategy = DuplicatesStrategy.FAIL
        from(
            pinnedMpvAar.elements.map { aarFiles ->
                aarFiles.single().asFile.let(::zipTree)
            },
        ) {
            include("jni/**")
            exclude("jni/x86/**", "jni/**/libplayer.so")
            eachFile {
                path = path.removePrefix("jni/")
            }
            includeEmptyDirs = false
        }
        into(extractedNativeDir)
    }

android {
    namespace = "dev.jdtech.mpv"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments +=
                    listOf(
                        "-DANDROID_STL=none",
                        "-DJELLYSCOPE_EXTRACTED_JNI_DIR=${extractedNativeDir.get().asFile.absolutePath}",
                    )
                cppFlags += listOf("-std=c++11", "-Werror")
            }
        }
    }

    sourceSets {
        getByName("main") {
            // AGP 9 rejects Provider instances in the legacy SourceSet API;
            // the native/CMake tasks below retain the explicit extraction edge.
            jniLibs.srcDir(extractedNativeDir.get().asFile)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// AGP creates variant/ABI-specific CMake tasks lazily. Every native configure
// and build task must see the generated imported libraries first; preBuild is
// retained for resource-only and clean packaging paths.
tasks.configureEach {
    if (
        name == "preBuild" ||
        name.contains("cmake", ignoreCase = true) ||
        name.contains("externalNativeBuild", ignoreCase = true)
    ) {
        dependsOn(extractPinnedMpvNative)
    }
}
