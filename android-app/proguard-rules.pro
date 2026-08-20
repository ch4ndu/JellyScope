# kotlinx-serialization: keep generated serializers for our DTOs
-keepclassmembers class com.jellyscope.** {
    *** Companion;
}
-keepclasseswithmembers class com.jellyscope.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Media3 extension renderers and LibVLC discover native entry points by name.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.videolan.libvlc.** { *; }
