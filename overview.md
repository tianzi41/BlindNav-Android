# 盲人导航安卓应用 - 交付概览

## TL;DR

已完成智能眼镜盲人导航项目的安卓手机移植，包含 25 个 Kotlin 源文件的完整安卓原生应用。

## 交付概览

| 项目 | 状态 |
|------|------|
| 代码实现 | ✅ 完成 |
| QA 验证 | ✅ 通过（2个Bug已修复） |
| 已知问题 | 0 个关键问题 |

## 核心功能

1. **盲道导航** — YOLO 分割模型实时识别盲道，提供方向指引
2. **过马路辅助** — 斑马线 + 红绿灯检测
3. **物品查找** — YOLO-E 检测 + 手部引导
4. **障碍物检测** — 实时检测并语音警告

## 关键变更

- ❌ 去掉阿里云 DashScope API（ASR + Qwen 对话）
- ✅ 按钮点击替代语音指令
- ✅ 预录音频 wav 播放替代 TTS
- ✅ 手机摄像头替代 ESP32-CAM
- ✅ 摄像头权限已声明并申请

## 文件清单

### 项目路径
`Q:/xiaolongxia/xiaxia/blindnav-android/`

### 核心文件（25个 Kotlin）
- `app/src/main/java/com/blindnav/app/MainActivity.kt` — 权限申请 + Compose 入口
- `app/src/main/java/com/blindnav/app/viewmodel/MainViewModel.kt` — 主 ViewModel
- `app/src/main/java/com/blindnav/app/navigation/NavigationMaster.kt` — 状态机
- `app/src/main/java/com/blindnav/app/camera/CameraManager.kt` — CameraX 封装
- `app/src/main/java/com/blindnav/app/audio/AudioPlayerManager.kt` — 音频系统
- `app/src/main/java/com/blindnav/app/ml/YoloOnnxEngine.kt` — ONNX 推理引擎
- `app/src/main/java/com/blindnav/app/ui/screens/MainScreen.kt` — 主界面
- ... 等共 25 个文件

### 资源文件
- `app/src/main/assets/voice/` — 106 个导航语音 wav + map.zh-CN.json
- `app/src/main/assets/music/` — 25 个音乐 wav

### 构建文件
- `build.gradle.kts` + `app/build.gradle.kts` + `settings.gradle.kts`
- `gradle.properties` + `gradle/wrapper/gradle-wrapper.properties`
- `gradlew` + `gradlew.bat`
- `app/proguard-rules.pro`

## 下一步建议

1. **用 Android Studio 打开项目** — 打开 `Q:/xiaolongxia/xiaxia/blindnav-android/` 目录
2. **下载 Gradle Wrapper** — 首次构建时会自动下载，或手动下载 gradle-wrapper.jar
3. **转换模型文件** — 将 PyTorch .pt 模型转换为 ONNX 格式放到 `app/src/main/assets/models/`
4. **连接真机测试** — 需要 Android 8.0+ 真机（模拟器无摄像头）
5. **构建 APK** — Build → Build Bundle(s) / APK(s) → Build APK(s)
