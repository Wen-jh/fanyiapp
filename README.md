# fanyiapp

这是一个最小可编译的 Android 验证版项目，用于验证：
1. 悬浮窗权限
2. MediaProjection 授权
3. AudioPlaybackCapture 能否初始化
4. 不支持直捕时是否可退回麦克风探测

当前版本说明：
- 已重建为标准 Android Gradle 工程
- 目标是先通过 GitHub Actions 产出 debug APK
- 目前还没有接入真正的 ASR/翻译引擎，只做“音频捕获能力验证 + 悬浮提示”
- 是否能抓到 115 App 或其他 App 的播放音频，取决于 Android 系统版本、ROM 和目标 App 是否允许被系统捕获

本地运行：
- 需要 JDK 17
- 需要 Android SDK 34
- 可执行：./gradlew assembleDebug

GitHub Actions：
- push 到 main 后会自动构建
- 也可在 Actions 页面手动运行 workflow_dispatch
- 构建产物为 artifact：fanyiapp-debug-apk
