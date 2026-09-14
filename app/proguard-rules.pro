# VERTIL — ProGuard rules

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep VERTIL core contracts
-keep class com.vertil.core.** { *; }
-keep class com.vertil.model.** { *; }
-keep class com.vertil.permissions.** { *; }
-keep class com.vertil.tools.** { *; }
-keep class com.vertil.storage.** { *; }
-keep class com.vertil.activity.** { *; }
-keep class com.vertil.chat.** { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
