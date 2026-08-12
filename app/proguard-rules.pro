# Haha Remote - R8/ProGuard 规则

# zxing 扫码库（AAR 自带 consumer rules，这里兜底）
-keep class com.google.zxing.** { *; }


# Keystore/加密（通过反射/名称调用）
-keepclassmembers class com.cchaha.remote.CryptoStore { *; }
