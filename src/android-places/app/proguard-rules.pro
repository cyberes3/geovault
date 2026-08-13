# ---------------------------------------------------------------------------
# App model/API classes (Gson + Parcelable + Retrofit)
# ---------------------------------------------------------------------------
-keep class com.geovault.places.model.** { *; }
-keep class com.geovault.places.data.** { *; }

# ---------------------------------------------------------------------------
# Android components referenced by name
# ---------------------------------------------------------------------------
-keep public class * extends android.content.ContentProvider