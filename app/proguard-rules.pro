# Keep GoMobile Xray JNI bindings
-keep class libv2ray.** { *; }
-keep class go.** { *; }

# Keep hev-socks5-tunnel JNI bindings
-keep class hev.htproxy.** { *; }

# Strip verbose logs in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
