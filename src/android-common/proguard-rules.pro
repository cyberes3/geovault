# GeoVault android-common library
# Consumer rules applied when this library is consumed by an app.

# Keep GeovaultAuthManager public API for reflection / R8
-keep class com.geovault.common.GeovaultAuthManager { *; }

# OkHttp / Retrofit (if used by app)
-dontwarn okhttp3.**
-dontwarn retrofit2.**
