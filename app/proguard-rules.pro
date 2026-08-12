# Keep Kotlin serialization metadata and generated serializers.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    public static ** serializer(...);
}
-keepclassmembers class **$$serializer { <fields>; }

# The terminal library accesses some platform details reflectively.
-keep class com.termux.** { *; }
