-keep class com.bockmedia.console.** { *; }
-keepattributes *Annotation*

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-if class kotlinx.serialization.** { *; }
-keep class kotlinx.serialization.** { *; }
