# fanyiapp

这是一个 Android 实时翻译验证版项目，当前已切到“真实音频采集 + 本地日语 ASR + 中文翻译”的主路线。

当前仓库现状：
- 已重建为标准 Android Gradle 工程
- GitHub Actions 会在 push 到 main 后自动构建 debug APK
- 悬浮窗支持拖动，并在悬浮窗内展示完整运行链路
- 当前版本优先尝试 MediaProjection 播放捕获 + 本地 Vosk 日语识别
- 播放捕获不可用时会自动回退到麦克风本地识别
- 已接入 ML Kit 做日语→中文翻译
- 悬浮窗会展示：采集状态、识别状态、翻译状态、音量、日语原文、中文结果
- 可选调试音频落盘（PCM/WAV）已接入服务链路

为什么第一阶段选 Vosk：
- 比系统 SpeechRecognizer 更适合直接喂入我们自己采集到的 PCM 音频
- 集成成本低于 sherpa-onnx / whisper 风格方案
- 更适合先做低延迟流式字幕原型

当前限制：
- 播放捕获仍受 Android 版本、ROM、目标 App 的 `allowAudioPlaybackCapture` 策略影响
- 若 `app/src/main/assets/model-ja-small/` 里没有放入 Vosk 日语小模型，运行时会明确提示本地识别模型准备失败
- 当前仓库只提交了模型占位目录，未直接提交约 47MB 的日语模型文件
- 翻译目前采用“稳定分段优先 + final 立即翻译”的策略，延迟已优先压低，但仍可能受模型下载、设备性能和媒体噪声影响

模型准备：
- 推荐模型：`vosk-model-small-ja-0.22`
- 下载地址：`https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip`
- 将压缩包内容解压后放到：`app/src/main/assets/model-ja-small/`
- 首次运行时，应用会把模型复制到应用私有目录后再初始化识别器

调试音频：
- 若启用调试音频落盘，文件保存在应用 external files 目录下的 `audio-dumps/`
- 支持 `.wav` 和 `.pcm` 两种格式
- 该功能默认关闭，以减少额外 I/O 带来的延迟抖动

本地运行：
- 需要 JDK 17
- 需要 Android SDK 34
- 仓库当前未提交 Gradle wrapper；本地若要直接构建，需要先生成 wrapper 或自行安装 Gradle 8.7

GitHub Actions：
- push 到 `main` 后会自动构建
- 也可在 Actions 页面手动运行 `workflow_dispatch`
- 构建产物 artifact 名称：`fanyiapp-debug-apk`
