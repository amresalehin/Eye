# ProGuard / R8 rules for Emre Gallery Release Builds

# Preserve annotations, signatures, inner classes, and line numbers for reflection & crash diagnostics
-keepattributes *Annotation*,JavascriptInterface,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Keep classes and members annotated with @androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# WebView @JavascriptInterface bridges (BrowserMediaExtractor & DirectBrowserView)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.amresalehin.emregallery.data.BrowserMediaExtractor$EmreMediaBridge {
    public <init>(...);
    @android.webkit.JavascriptInterface public *;
}

# Chaquopy Python SDK & Kotlin <-> Python JNI reflective bridges
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**
-keep class com.amresalehin.emregallery.data.PythonProgressBridge {
    public <init>(...);
    public void onProgress(long, long, long);
    *;
}
-keep class com.amresalehin.emregallery.data.AndroidMediaMuxer {
    public static ** INSTANCE;
    public static boolean hasAudioTrack(java.io.File);
    public static boolean hasVideoTrack(java.io.File);
    public static boolean muxVideoAndAudioPaths(java.lang.String, java.lang.String, java.lang.String);
    public static boolean muxVideoAndAudio(java.io.File, java.io.File, java.io.File);
    *;
}
-keep class com.amresalehin.emregallery.data.GalleryDlEngine { *; }

# WorkManager CoroutineWorker reflective instantiation
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.amresalehin.emregallery.data.MediaDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ONNX Runtime JNI
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Tesseract 4 Android JNI
-keep class com.googlecode.tesseract.android.** { *; }
-keep class cz.adaptech.tesseract4android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn cz.adaptech.tesseract4android.**

# Google ML Kit Text Recognition (Latin, Chinese, Devanagari, Japanese, Korean)
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.odml.**

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Coil & OkHttp3 / Okio / Jsoup
-dontwarn coil.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Media3 ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# JNI entry points are resolved by fixed native symbol names.
-keep class com.amresalehin.emregallery.data.ai.NativeVisionEngine { *; }
