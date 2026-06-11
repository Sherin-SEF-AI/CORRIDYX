# LiteRT / TFLite
-keep class org.tensorflow.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.**
-dontwarn com.google.ai.edge.litert.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ai.deepmost.corridyx.** {
    *** Companion;
}
-keepclasseswithmembers class ai.deepmost.corridyx.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# AIDL / Parcelable for the PerceptionConditions bound API
-keep class ai.deepmost.corridyx.conditions_api.** { *; }
