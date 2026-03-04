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

# Parcelize
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---------------------------------------------------------------------------
# App model classes (used by Gson + Parcelable + Retrofit)
# ---------------------------------------------------------------------------
-keep class com.geovault.places.FeatureCollection { *; }
-keep class com.geovault.places.Feature { *; }
-keep class com.geovault.places.Geometry { *; }
-keep class com.geovault.places.Properties { *; }
-keep class com.geovault.places.AddressSearchResponse { *; }
-keep class com.geovault.places.GeocodingResponseData { *; }
-keep class com.geovault.places.AddressSearchResult { *; }
-keep class com.geovault.places.TileSourceResponse { *; }
-keep class com.geovault.places.TileSource { *; }
-keep class com.geovault.places.TileClientConfig { *; }
-keep class com.geovault.places.OfflineFeature { *; }
-keep interface com.geovault.places.GeovaultApi { *; }

# ---------------------------------------------------------------------------
# Retrofit
# ---------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------------------------------------------------------------------------
# Gson
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ---------------------------------------------------------------------------
# OkHttp
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# MapLibre (Java API; native libs are JNI)
# ---------------------------------------------------------------------------
-keep class org.maplibre.** { *; }

# ---------------------------------------------------------------------------
# AndroidX / Security Crypto (reflection)
# ---------------------------------------------------------------------------
-keep class androidx.security.crypto.** { *; }
