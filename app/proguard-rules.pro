# Keep TextLine and DocumentData data classes
-keep class com.readoutloud.TextLine { *; }
-keep class com.readoutloud.DocumentData { *; }
-keepclassmembers class com.readoutloud.DocumentData$* { *; }

# Keep TTS Service and Controller
-keep class com.readoutloud.TtsService { *; }
-keep class com.readoutloud.TtsController { *; }
-keep class com.readoutloud.AppSettings { *; }

# Keep MainActivity and Compose UI classes
-keep class com.readoutloud.MainActivity { *; }
-keepclassmembers class com.readoutloud.MainActivity* { *; }

# Keep loader classes
-keep class com.readoutloud.DocxLoader { *; }
-keep class com.readoutloud.EpubLoader { *; }
-keep class com.readoutloud.OdtLoader { *; }
-keep class com.readoutloud.HtmlLoader { *; }

# ============================================
# PDFBox: Only keep required classes
# Remove Bouncy Castle (not actually needed for basic PDF text extraction)
# ============================================
-dontwarn org.bouncycastle.**
-keepnames class org.bouncycastle.** { *; }

# Keep only PDF text extraction classes
-keep class org.apache.pdfbox.pdmodel.** { *; }
-keep class org.apache.pdfbox.text.** { *; }
-keep class org.apache.pdfbox.filter.** { *; }
-keep class org.apache.pdfbox.contentstream.** { *; }

# Exclude unnecessary PDFBox modules
-dontwarn org.apache.pdfbox.examples.**
-dontwarn org.apache.pdfbox.pdfviewer.**
-dontwarn org.apache.pdfbox.printing.**
-dontwarn org.apache.pdfbox.javadoc.**

# JPX/JP2 decoder (optional, not used in basic text extraction)
-dontwarn com.gemalto.jp2.**
-keepnames class com.gemalto.jp2.** { *; }

# ============================================
# readability4j pulls in slf4j-api but no binding. R8 fails the release build on the
# missing StaticLoggerBinder; slf4j falls back to a no-op logger at runtime.
# ============================================
-dontwarn org.slf4j.**

# ============================================
# Jsoup: Keep only required classes
# ============================================
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.helper.DataUtil

# ============================================
# Compose: Keep runtime but remove debug-only code
# ============================================
-dontwarn androidx.compose.ui.tooling.**
-dontwarn androidx.compose.ui.test.**

# ============================================
# Remove unused AndroidX libraries
# ============================================
-dontwarn androidx.test.**
-dontwarn androidx.espresso.**
-dontwarn androidx.junit.**

# ============================================
# sherpa-onnx JNI bridge — must not be renamed
# ============================================
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# ============================================
# General optimizations
# (-optimizations and -optimizationpasses are ProGuard-only; R8 ignores them, so they are gone)
# ============================================
-allowaccessmodification

# Remove assertions
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
