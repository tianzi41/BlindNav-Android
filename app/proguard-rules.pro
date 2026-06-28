# 盲人导航应用 ProGuard 规则
# 保留关键库的类和方法不被混淆

# ONNX Runtime - 保留所有类和 native 方法
-keep class com.microsoft.onnxruntime.** { *; }
-keepclassmembers class com.microsoft.onnxruntime.** { *; }
-dontwarn com.microsoft.onnxruntime.**

# MediaPipe - 保留所有类
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# CameraX - 保留核心类
-keep class androidx.camera.** { *; }
-keepclassmembers class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Jetpack Compose
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# 应用数据模型 - 保留所有数据类
-keep class com.blindnav.app.data.** { *; }
-keepclassmembers class com.blindnav.app.data.** { *; }

# 应用导航状态机
-keep class com.blindnav.app.navigation.** { *; }
-keepclassmembers class com.blindnav.app.navigation.** { *; }

# Kotlin 协程
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Kotlin 标准库
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# JSON 解析
-keep class org.json.** { *; }
-dontwarn org.json.**

# AndroidX
-keep class androidx.** { *; }
-keepclassmembers class androidx.** { *; }
-dontwarn androidx.**

# 防止移除 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留 Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
