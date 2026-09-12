# SMRITI Core — R8/ProGuard rules (offline ML stack).

# ---- TensorFlow Lite ----
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.**

# ---- MediaPipe Tasks ----
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ---- Vosk + JNA (native bindings must keep names) ----
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.vosk.**
-dontwarn com.sun.jna.**

# ---- ML Kit ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- Room / Kotlin metadata ----
-keepclassmembers class * {
    @androidx.room.** <methods>;
}
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
