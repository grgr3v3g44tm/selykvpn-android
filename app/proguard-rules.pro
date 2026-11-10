# gomobile bindings (Xray core): classes are looked up from native code by name.
-keep class go.** { *; }
-keep class xyz.selykvpn.libxray.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class xyz.selykvpn.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class xyz.selykvpn.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp optional platform integrations
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
