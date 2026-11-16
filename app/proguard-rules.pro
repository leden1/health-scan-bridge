# Add project specific ProGuard rules here.

# ML Kit
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }

# Health Connect
-keep class androidx.health.connect.client.** { *; }

# Kotlin
-keepclassmembers class **$WhenMappings { *; }