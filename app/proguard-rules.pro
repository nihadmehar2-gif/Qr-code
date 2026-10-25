# Default ProGuard rules for the QR Scanner app.
# Since we don't enable R8 minification, this file is mostly empty.

# Keep ML Kit model classes
-keep class com.google.mlkit.** { *; }

# Keep ZXing
-keep class com.google.zxing.** { *; }
