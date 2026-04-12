-keep class com.geovault.common.maps.** { *; }

# MapLibre uses JNI/reflection and requires stable class/member names.
-keep class org.maplibre.** { *; }
-keepclassmembers class org.maplibre.** { *; }
