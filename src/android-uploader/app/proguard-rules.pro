# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ---------------------------------------------------------------------------
# Kotlin
# ---------------------------------------------------------------------------
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Parcelable (Uri, etc.)
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---------------------------------------------------------------------------
# OkHttp
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------------------------------------------------------------------------
# Retrofit (R8 must not obfuscate retrofit2.* or Call adapter lookup fails)
# ---------------------------------------------------------------------------
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# ---------------------------------------------------------------------------
# android-common (ServerUrlProvider is referenced in manifest by class name;
# GeovaultAuthManager, RetrofitClient used from app)
# ---------------------------------------------------------------------------
-keep class com.geovault.common.** { *; }

# ---------------------------------------------------------------------------
# Android components referenced by name (manifest, system)
# ---------------------------------------------------------------------------
-keep public class * extends android.content.ContentProvider

# ---------------------------------------------------------------------------
# AndroidX / Security Crypto (reflection)
# ---------------------------------------------------------------------------
-keep class androidx.security.crypto.** { *; }
