# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Server response classes and models are read and written by name through kotlinx.serialization
-keep class com.chapterplayer.data.remote.** { *; }
-keep class com.chapterplayer.data.model.** { *; }

# Generic type information used when decoding response bodies
-keepattributes Signature
-keepattributes Exceptions

# HTTP client: Ktor runs on OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
