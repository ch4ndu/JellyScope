# SPDX-License-Identifier: MPL-2.0

# Compose Desktop release shrinking runs over every runtime dependency. These
# classes are optional JVM/TLS/native-image integrations referenced by OkHttp
# but not required by JellyScope's desktop runtime.
-dontwarn okhttp3.internal.graal.**
-dontwarn okhttp3.internal.platform.BouncyCastlePlatform**
-dontwarn okhttp3.internal.platform.ConscryptPlatform**
-dontwarn okhttp3.internal.platform.OpenJSSEPlatform**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.graalvm.nativeimage.**
-dontwarn org.openjsse.**

# Ktor logging has an Android logger fallback on the JVM classpath. Desktop does
# not ship android.util.Log and does not use that path.
-dontwarn android.util.Log
-dontnote io.ktor.client.plugins.logging.LoggerJvmKt

# Ktor discovers HTTP engines and kotlinx-serialization extensions through
# META-INF/services. Service files survive shrinking, so keep provider classes
# that would otherwise look unused to ProGuard.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }

# Coil also discovers optional fetchers/decoders through META-INF/services. Keep
# those provider classes so release builds can load authenticated Jellyfin
# artwork through the shared Ktor-backed image loader.
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-keep class coil3.network.ktor3.internal.KtorNetworkFetcherServiceLoaderTarget { *; }

# Skiko keeps a constructor that references the JetBrains Runtime shared-texture
# descriptor. Keep the descriptor so ProGuard does not shrink it away.
-keep class com.jetbrains.SharedTextures { *; }
-dontnote org.jetbrains.skiko.swing.JbrSharedTexturesAdapter

# Room KMP, AndroidX SQLite bundled, and JNA/libmpv rely on generated classes,
# JNI members, and reflective/native method mapping at desktop startup and
# playback time. Names are already preserved by the Compose Desktop default
# release config; these rules keep the shrinker from deleting runtime-loaded
# entry points.
-keep class com.jellyscope.core.data.local.*Database_Impl { *; }
-keep class com.jellyscope.core.data.local.*DatabaseConstructor { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }
-keep class com.jellyscope.core.playback.LibMpv { *; }
-keep class com.jellyscope.core.playback.LibMpv$* { *; }
-keep class com.jellyscope.core.playback.PosixC { *; }
-keep class com.jellyscope.core.playback.MpvRenderParam { *; }
-keep class com.jellyscope.core.playback.MpvRenderParam$* { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-dontnote com.jellyscope.core.playback.LibMpv**
-dontnote com.jellyscope.core.playback.MpvRenderParam**
-dontnote androidx.sqlite.driver.bundled.**
-dontnote com.sun.jna.**
