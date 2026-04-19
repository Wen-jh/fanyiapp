# fanyiapp

这是一个 Android 实时翻译验证版项目，当前重点是先把“能运行、能看见链路状态、能持续迭代”的基础打稳。

当前仓库现状：
- 已重建为标准 Android Gradle 工程
- GitHub Actions 会在 push 到 main 后自动构建 debug APK
- 悬浮窗支持拖动，并在悬浮窗内展示完整运行链路
- 已接入 Android `SpeechRecognizer` 做日语识别
- 已接入 ML Kit 做日语→中文翻译
- 悬浮窗会展示：采集状态、识别状态、翻译状态、音量、日语原文、中文结果

当前限制：
- 虽然已经申请 `MediaProjection`/播放捕获权限并做了能力探测，但当前版本“真正用于识别的音频输入”仍然是麦克风路径
- 也就是说，现在更接近“日语语音实时识别+翻译悬浮窗”，还不是“稳定抓取任意视频 App 内放声音频并转字幕”的完成版
- 是否能抓到 Douyin / TikTok / 其他 App 的播放音频，仍取决于 Android 版本、ROM、目标 App 策略，以及后续是否补上真正的播放音频 ASR 管线

本地运行：
- 需要 JDK 17
- 需要 Android SDK 34
- 仓库当前未提交 Gradle wrapper；本地若要直接构建，需要先生成 wrapper 或自行安装 Gradle 8.7

GitHub Actions：
- push 到 `main` 后会自动构建
- 也可在 Actions 页面手动运行 `workflow_dispatch`
- 构建产物 artifact 名称：`fanyiapp-debug-apk`
- 最近一次已成功构建
