# ProGuard / R8 configuration for VK TURN Proxy Android

# Keep all classes and native methods in the core package (Go bridge JNI)
-keep class com.vkturnproxy.core.** { *; }

# Keep all VPN service and helper classes (AIDL, lifecycle, background service)
-keep class com.vkturnproxy.service.** { *; }

# Keep data models used with Gson serialization
-keep class com.vkturnproxy.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Preserve all native method signatures across the app
-keepclasseswithmembernames class * {
    native <methods>;
}
