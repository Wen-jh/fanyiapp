# Hy-MT 完全离线拍照翻译接入方案

更新时间：2026-05-26
项目：`/home/ubuntu/fanyiapp`
目标：把当前“拍照识图翻译”从内置词典翻译替换为 `Hy-MT1.5-1.8B-1.25bit` 本地模型推理，并实现“安装 APK 后无需联网、无需二次下载即可直接翻译”。

## 1. 当前现状

当前拍照翻译路径位于：
- `app/src/main/java/com/wenjh/fanyiapp/ImageTranslationActivity.kt`
- `app/src/main/java/com/wenjh/fanyiapp/OfflineEnglishChineseTranslator.kt`

当前流程是：
1. 拍照 / 相册导入
2. ML Kit OCR 识别英文文本
3. `OfflineEnglishChineseTranslator.translate()` 做内置短语/词典翻译
4. `ImageTranslationFormatter.composeResult()` 输出状态、原文、译文

关键事实：
- OCR 已是本地：`com.google.mlkit:text-recognition`
- 翻译不是大模型，只是 Kotlin 内置规则词典
- 工程里还没有 GGUF / llama.cpp / JNI 推理运行时
- App 现有 assets 约 `95M`
- 现有 APK 约 `105M`
- `Hy-MT` ModelScope 页面显示模型文件约 `941.79MB`

## 2. 官方 Hy-MT Demo 已确认的信息

通过对官方 demo APK 的静态检查，已经确认：
- demo APK 内含 native 推理库，典型组件包括：
  - `libllama.so`
  - `libggml.so`
  - `libggml-base.so`
  - 多个 `libggml-cpu-android_armv*.so`
  - `libomp.so`
  - `libai-chat.so`
- demo APK 本身只有约 `7.1M`
- demo APK 内没有打包 `.gguf` 模型
- demo dex 字符串明确包含：
  - `ModelDownloader`
  - `HY1.8B-MT-1.25bit.gguf`
  - ModelScope 下载地址

结论：
- 官方 demo 证明 Android 本地 GGUF 推理是可行的
- 但官方 demo 走的是“小 APK + 首次下载模型”路线
- 我们要做的是“同样本地推理，但把模型直接随 APP 安装包发出去”

## 3. 最终目标定义

目标不是做“通用聊天模型 UI”，而是只替换拍照翻译后端：
- 保留当前 `ImageTranslationActivity` 的拍照/相册/OCR UI
- 保留当前结果展示结构
- 用 Hy-MT 替换 `OfflineEnglishChineseTranslator`
- 安装 APK 后无需联网
- 首次进入拍照翻译页面时，可接受一次本地“模型准备/解包”过程
- 准备完成后，后续离线推理

非目标：
- 不改当前实时字幕服务主链路
- 不把 Hy-MT 接到悬浮窗实时语音翻译
- 不在当前阶段做多语言扩展

## 4. 推荐总体架构

建议采用“四层架构”：

### 4.1 UI 层
文件：
- `ImageTranslationActivity.kt`
- `ImageTranslationFormatter.kt`
- `activity_image_translation.xml`

职责：
- 拍照、选图
- 显示模型准备状态
- 显示 OCR 状态
- 显示翻译状态
- 显示译文/错误信息

### 4.2 OCR 层
保留现有：
- ML Kit 本地 OCR

职责：
- 从图片中提取英文文本
- 将文本传给 Hy-MT 翻译层

### 4.3 Hy-MT 业务桥接层
新增建议文件：
- `PhotoTranslationEngine.kt`
- `HyMtTranslationEngine.kt`
- `HyMtPromptBuilder.kt`
- `HyMtResultParser.kt`
- `HyMtModelManager.kt`
- `HyMtInitState.kt`

职责：
- 对上提供统一 `translate(text)` 接口
- 管理模型准备、加载、并发互斥、错误状态
- 组装翻译 prompt
- 调 native/JNI 推理
- 清理模型输出，只返回最终中文译文

### 4.4 Native 推理层
新增建议目录：
- `app/src/main/jniLibs/arm64-v8a/*.so`
- `app/src/main/java/com/wenjh/fanyiapp/HyMtNativeBridge.kt`

职责：
- 加载 `libllama.so` / `libggml*.so` / 桥接 JNI 库
- 暴露最小接口给 Kotlin：
  - `init(modelPath: String, threads: Int, context: Int): Boolean`
  - `isReady(): Boolean`
  - `translate(prompt: String, maxTokens: Int, temperature: Float): String`
  - `release()`

注意：
- 这里不建议直接把 UI 写死依赖某个第三方 Java API
- 最稳的是自己控制一层 `HyMtNativeBridge`
- 即使底层最初复用 demo 风格 native 库，业务层仍应隔离

## 5. 模型打包与离线可用方案

## 5.1 打包策略
推荐直接随应用打包：
- 模型文件：`app/src/main/assets/hy_mt/Hy-MT1.5-1.8B-1.25bit.gguf`
- 若运行时还需要 tokenizer / config / prompt template，也一起放：
  - `app/src/main/assets/hy_mt/...`

如果单文件太大导致 aapt2 / 打包或安装过程不稳定，可改为分片：
- `Hy-MT1.5-1.8B-1.25bit.gguf.part01`
- `...part02`
- 安装后首次在私有目录合并

但优先建议：
- 先尝试单文件 asset
- 若构建/安装失败，再切分

## 5.2 首次使用准备流程
因为 GGUF 通常更适合走真实文件路径加载，推荐：
1. App 安装时模型在 assets 内
2. 用户首次进入拍照翻译页面
3. `HyMtModelManager` 检查 `filesDir/hy_mt/Hy-MT1.5-1.8B-1.25bit.gguf` 是否已存在
4. 若不存在，则从 assets 拷贝到 `filesDir/hy_mt/`
5. 拷贝完成后做完整性检查：
   - 文件存在
   - 文件长度 > 最小阈值
   - 可选：sha256 校验
6. 调 JNI 初始化模型
7. 标记 ready

推荐原因：
- 避免直接从压缩 asset 做 mmap 失败
- 便于后续 native 层通过绝对路径打开模型
- 更容易显示“准备中”进度

## 5.3 建议目录
- assets: `app/src/main/assets/hy_mt/`
- 运行时私有目录：`context.filesDir/hy_mt/`
- 可选缓存目录：`context.cacheDir/hy_mt_tmp/`

## 6. Android 工程改造点

## 6.1 Gradle
`app/build.gradle.kts` 需要增加/调整：

1. 只保留 `arm64-v8a`
   原因：1.25bit 大模型在当前目标设备上主要就是 ARM64，减少无意义 ABI 体积。

建议：
```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            noCompress += setOf("gguf", "bin", "model", "txt")
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
```

其中 `noCompress` 很关键：
- 避免模型 asset 被压缩
- 降低首次解包和读取复杂度

2. Gradle 内存提高
当前 `gradle.properties`：
- `org.gradle.jvmargs=-Xmx2048m`

对于接近 1GB 模型打包，建议提升到至少：
- `-Xmx4096m`
或更高

## 6.2 Manifest
如果最终完全离线，可以移除拍照翻译对网络的依赖说明，但全局 APP 还有其他能力，暂不必移除 `INTERNET`。

不用新增特殊权限。

## 6.3 Native 库
新增：
- `app/src/main/jniLibs/arm64-v8a/libllama.so`
- `app/src/main/jniLibs/arm64-v8a/libggml.so`
- `app/src/main/jniLibs/arm64-v8a/libggml-base.so`
- `app/src/main/jniLibs/arm64-v8a/libggml-cpu-*.so`
- `app/src/main/jniLibs/arm64-v8a/libomp.so`
- 以及你自己的桥接库，比如 `libfanyi_hymt_jni.so`

不建议直接依赖“来源不明但能跑”的 `.so` 而不做封装；应该有自己 JNI 层，避免未来无法维护。

## 7. Kotlin 层接口设计

建议新增统一接口，替代当前 `OfflineEnglishChineseTranslator` 的硬编码实现。

```kotlin
interface PhotoTranslationEngine {
    suspend fun prepareIfNeeded(): PreparationResult
    suspend fun translate(text: String): TranslationResult
    fun currentState(): EngineState
    fun release()
}
```

### 7.1 PreparationResult
```kotlin
data class PreparationResult(
    val ready: Boolean,
    val message: String,
    val copiedBytes: Long? = null,
    val totalBytes: Long? = null
)
```

### 7.2 TranslationResult
```kotlin
data class TranslationResult(
    val text: String,
    val backend: String,
    val promptTokensEstimate: Int? = null,
    val outputTokensEstimate: Int? = null,
    val rawOutput: String? = null
)
```

### 7.3 EngineState
```kotlin
sealed class EngineState {
    data object Idle : EngineState()
    data class Preparing(val message: String, val progress: Int?) : EngineState()
    data class Loading(val message: String) : EngineState()
    data object Ready : EngineState()
    data class Translating(val message: String) : EngineState()
    data class Error(val message: String) : EngineState()
}
```

## 8. 拍照翻译页面的状态流改造

当前 `ImageTranslationActivity` 只有 OCR/翻译两个粗状态。接 Hy-MT 后建议细化为：

1. 准备就绪：可拍照或导入图片（Hy-MT 离线模型）
2. 正在检查本地模型
3. 正在解包离线模型（x%）
4. 正在加载 Hy-MT 模型到内存
5. 模型已就绪，开始识别图片中的英文
6. 正在整理识别文本
7. 正在进行 Hy-MT 离线翻译
8. 翻译完成
9. 模型准备失败 / 模型加载失败 / 翻译失败

建议 UI 增加：
- 一个单独的“模型状态”文本块
- 一个可选 `ProgressBar`
- 一个“重新初始化模型”按钮（调试阶段非常有用）

## 9. Prompt 设计

OCR 文本经常包含断行、菜单、按钮、杂质字符，因此不要把 Hy-MT 当聊天模型随便问，必须固定提示词。

建议 prompt 模板：

```text
你是一个离线翻译引擎。
任务：把下面的英文 OCR 文本翻译成自然、简洁、准确的简体中文。
要求：
1. 只输出中文译文。
2. 不要解释。
3. 不要添加原文中没有的信息。
4. 如果是界面按钮、菜单、提示语，优先用中文 UI 常见说法。
5. 保留必要的数字、专有名词与品牌名。

英文 OCR 文本：
{{OCR_TEXT}}
```

实现上应：
- 对 OCR 文本长度做上限控制
- 过长时先裁剪或按段翻译
- 清洗连续空行、极长空白、异常符号

## 10. 输出后处理

Hy-MT 输出可能仍有：
- 多余前缀
- 空行
- 编号
- “翻译如下：”之类冗余

因此需要 `HyMtResultParser`：
- 去掉首尾空白
- 去掉显式前缀：`翻译：`、`译文：`、`中文：`
- 合并异常空行
- 对纯重复输出做压缩
- 若输出为空则报错

## 11. 并发与生命周期

当前 `ImageTranslationActivity` 已有 `analyzeRequestToken` 和 `analyzeJob`，这一点很好，继续保留。

新增要求：
- `prepareIfNeeded()` 必须串行，避免重复拷贝模型
- `translate()` 必须串行进入 native 推理，避免多线程冲突
- 页面销毁时调用 `release()` 释放 native 句柄
- 旋转屏幕后应恢复：
  - 当前状态文本
  - 已识别原文
  - 已翻译译文
  - 预览图 URI
  - 是否模型 ready（可重新查询 manager）

建议：
- `Mutex` 保护 prepare
- `Mutex` 保护 inference

## 12. 设备与性能预期

当前目标设备是 OnePlus 13T，ARM64，理论上比中低端机更适合测试此类 1.8B 量化模型。

但仍要预留失败路径：
- 内存不足
- 加载时间过长
- 首次解包耗时长
- OCR 文本太长导致推理慢

建议默认策略：
- 仅支持 `arm64-v8a`
- 默认线程数：`min(4, availableProcessors())`
- 上下文长度先保守配置，例如 `1024` 或 `1536`
- 输出 token 上限控制，如 `256`
- temperature 设低值，如 `0.2f`

目标先是“稳定能翻译”，不是“速度极致优化”。

## 13. 落地实施顺序

### Phase A：工程骨架
1. 新建 `PhotoTranslationEngine` 接口
2. 新建 `HyMtTranslationEngine` 骨架
3. 新建 `HyMtModelManager` 骨架
4. 新建 `HyMtNativeBridge` 占位实现
5. 改造 `ImageTranslationActivity` 使其不再直接依赖 `OfflineEnglishChineseTranslator`
6. 在 UI 增加模型准备/加载状态区

这一阶段可以先不接入真实 `.so`，但要把调用链换好。

### Phase B：本地模型准备逻辑
1. 在 assets 预留 `hy_mt/`
2. 写模型拷贝器（assets -> filesDir）
3. 加入文件存在/长度校验
4. 将准备进度展示到页面

### Phase C：接入真实 native 推理
1. 放入 arm64 native libs
2. JNI bridge 打通 `init / translate / release`
3. 通过固定 prompt 跑通英文 -> 中文
4. 将输出接回页面

### Phase D：稳定性强化
1. 长文本裁剪
2. 异常输出清洗
3. 模型加载失败兜底
4. 页面旋转和重复点击保护
5. 若需要，增加“重置模型”按钮

## 14. 对现有类的具体改造建议

### 14.1 `OfflineEnglishChineseTranslator.kt`
建议不要直接删，改成：
- 保留为 fallback 或测试桩
- 或重命名为 `BuiltinFallbackTranslator.kt`

原因：
- 在 JNI 未打通前，仍可做本地回退
- 单元测试也更容易逐步迁移

### 14.2 `ImageTranslationActivity.kt`
核心改动：
- 初始化 `PhotoTranslationEngine`
- 页面启动时先异步 `prepareIfNeeded()`
- 在 `translateRecognizedText()` 中改调 engine
- 将状态文案从“内置离线翻译”改成“Hy-MT 离线模型翻译”

### 14.3 `strings.xml`
需要更新文案：
- “内置离线词典” -> “内置 Hy-MT 离线模型”
- 提示用户首次进入可能需要本地模型准备

### 14.4 `activity_image_translation.xml`
建议新增：
- `TextView`：`modelStatusText`
- `ProgressBar`：`modelProgressBar`
- `Button`：`retryModelInitButton`（可选，但建议加）

## 15. 风险点

1. 最大风险不是 Kotlin，而是 native/runtime 来源
   - 当前 repo 没有现成 llama Android 接入
   - 如果没有合法可复用的 AAR/JNI 源，需自己接 JNI

2. 大模型随 APK 打包可能影响：
   - Gradle 构建速度
   - CI 工件上传时间
   - 安装时间
   - Git 仓库存储策略

3. 不能把近 1GB 的模型直接提交进 git 主仓库而不考虑仓储策略
   更建议：
   - 代码仓和模型文件分离管理
   - CI 拉取模型后再打包
   - 或通过 GitHub Release / 私有制品仓缓存

但如果用户明确接受“只要最终安装包可离线使用”，那么：
- 最终产物可以是包含模型的 APK / split APK / AAB 安装集
- 不一定要求 git 仓库直接长期保存 1GB 二进制

## 16. 推荐最终实施策略

对于这个 repo，最现实的路径是：

方案 A（推荐）
- 先在代码里完成 Hy-MT 离线翻译骨架、状态展示、模型准备逻辑
- 模型文件与 native libs 暂通过本地放置/CI 注入方式进入构建
- 输出一个 arm64 专用 APK

方案 B（不推荐作为第一步）
- 立即在仓库里直接提交 1GB 模型 + 全部 native libs
- 风险是 repo 和 CI 很容易变笨重，迭代困难

## 17. 下一步实施清单

下一步实现时，建议直接做以下内容：
1. 新增 `PhotoTranslationEngine`、`HyMtTranslationEngine`、`HyMtModelManager`、`HyMtNativeBridge`
2. 改造 `ImageTranslationActivity`，接入 engine 状态流
3. 改 `activity_image_translation.xml` 和 `strings.xml`
4. 在 `app/build.gradle.kts` 增加 `arm64-v8a` 与 `noCompress` 配置
5. 暂时保留旧 `OfflineEnglishChineseTranslator` 作为 fallback
6. 等你提供或我们确认可合法使用的 native libs / JNI 桥后，再打通真实推理

## 18. 当前阶段结论

可以做，而且方向已经明确：
- OCR 继续使用 ML Kit
- 翻译后端改为 Hy-MT 本地 GGUF 推理
- 模型通过 assets 随 APK 打包
- 首次进入拍照翻译页做本地解包到 `filesDir`
- 页面显式展示“模型准备 / 加载 / 翻译”状态
- 工程上先把 Kotlin 骨架和资源打包机制搭起来，再接 native 真推理

这条路线最符合“安装 APK 后直接离线翻译”的要求。