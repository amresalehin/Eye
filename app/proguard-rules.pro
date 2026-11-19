# Proguard rules for Emre Gallery

# ONNX Runtime JNI
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Tesseract 4 Android JNI
-keep class com.googlecode.tesseract.android.** { *; }
-keep class cz.adaptech.tesseract4android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn cz.adaptech.tesseract4android.**

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Coil
-dontwarn coil.**

# Media3 ExoPlayer - rely on Media3 consumer proguard rules and keep reflective extractors/renderers
-keepclassmembers class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# FFmpegKit JNI callbacks (critical for release APKs)
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Chaquopy Python JNI bridge
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# WebView @JavascriptInterface bridges (media capture & deep scan)
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ML Kit & WorkManager workers
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class * extends androidx.work.ListenableWorker { *; }

# R8 Optimization flags
-allowaccessmodification
-repackageclasses ''
