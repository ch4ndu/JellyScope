# Preserve serialization entry points for the shared session values.
-keepclassmembers class com.jellyscope.** {
    *** Companion;
}
-keepclasseswithmembers class com.jellyscope.** {
    kotlinx.serialization.KSerializer serializer(...);
}
