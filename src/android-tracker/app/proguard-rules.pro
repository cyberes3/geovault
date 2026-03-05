# GeoVault Tracker – ProGuard/R8 rules for release (minified) build

# ---------------------------------------------------------------------------
# Kotlin
# ---------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-dontwarn kotlin.**
-keepclassmembers class * {
    @org.jetbrains.annotations.NotNull *;
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Parcelize
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---------------------------------------------------------------------------
# Gson – API models used by Retrofit
# ---------------------------------------------------------------------------
-keep class com.geovault.tracker.Tracker { *; }
-keep class com.geovault.tracker.GeoJsonLineString { *; }
-keep class com.geovault.tracker.TrackerCreateRequest { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# ---------------------------------------------------------------------------
# Retrofit / OkHttp
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn org.conscrypt.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
-keep class com.geovault.tracker.db.** { *; }

# ---------------------------------------------------------------------------
# MapLibre (consumer rules often bundled; keep critical classes if needed)
# ---------------------------------------------------------------------------
-dontwarn org.maplibre.**

# ---------------------------------------------------------------------------
# Play Services Location
# ---------------------------------------------------------------------------
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# ---------------------------------------------------------------------------
# Security / OAuth (EncryptedSharedPreferences, Custom Tabs)
# ---------------------------------------------------------------------------
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ---------------------------------------------------------------------------
# Application components (manifest entries are kept by default; explicit keeps for reflection)
# ---------------------------------------------------------------------------
-keep class com.geovault.tracker.TrackerApplication { *; }
-keep class com.geovault.tracker.MainActivity { *; }
-keep class com.geovault.tracker.SettingsActivity { *; }
-keep class com.geovault.tracker.OAuthCallbackActivity { *; }
-keep class com.geovault.tracker.TrackingService { *; }
-keep class com.geovault.tracker.BootReceiver { *; }

# Binary payload / JNI-style usage
-keep class com.geovault.tracker.BinaryPayloadBuilder { *; }
