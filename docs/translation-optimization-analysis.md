# fanyiapp 实时翻译深度分析与优化方案

## 一、当前链路全景

```
AudioRecord (PCM 16kHz)
  → Vosk ASR (partial / final)
    → TranslationSegmenter (1400ms 稳定窗)
      → PendingTranslationCoordinator (pending 队列)
        → ML Kit Translate (串行 await)
          → Hy-MT 润色 (异步二次翻译)
            → renderPipeline() → 悬浮窗 UI
```

## 二、延迟根因分析（按影响程度排序）

### 2.1 TranslationSegmenter 稳定窗口过长（最大瓶颈）

**文件**: `TranslationSegmenter.kt`
**问题**: `stableWindowMs = 1400ms`

分段器要等一段 partial 文本**稳定 1.4 秒**后才提交翻译。对于正常语速的日语（约 5-7 字符/秒），1.4 秒意味着多说 7-10 个字符才会触发翻译。

**用户感知**: 说话后大约 1.5~2 秒才看到第一段翻译，感觉"卡顿"。

**根因**: 分段器的设计哲学是"宁可等待，不要错误"，但对于实时字幕场景，这个策略太保守了。

### 2.2 Final 结果防抖延迟过高

**文件**: `SubtitleOverlayService.kt`
**问题**: `FINAL_TRANSLATION_DEBOUNCE_MS = 1200L`

当 Vosk 产生 final 结果时，如果不是完整句子结尾（没有句号等），会等待 1200ms 看是否有更多 final 结果到来。

**用户感知**: 短句（如"はい"、"そうです"）说完后还要再等 1.2 秒才翻译。

### 2.3 翻译串行执行，无并行管道

**文件**: `SubtitleOverlayService.kt` 的 `translateRecognizedText()`

当前翻译流程是严格串行的：
1. 第 1 句翻译中 → 第 2 句进入 pending 队列等待
2. 第 1 句完成 → 消费 pending → 开始第 2 句

ML Kit 翻译调用本身耗时约 200-500ms（取决于设备），串行意味着 N 句话的总延迟是 N × 单次翻译时间。

### 2.4 翻译不连续 / 跳变

**根因**: 每次翻译都是"整句替换"

当前的 UI 更新逻辑是：
```kotlin
lastOriginalText = bufferedFinalText  // 整句替换原文
lastTranslatedText = translated       // 整句替换译文
```

用户看到的是：
- 原文：「今日は天気が」→「今日は天気がいいですね」
- 译文：（空白）→（空白）→「今天天气真好呢」

译文在 ML Kit 返回前一直是空白或旧译文，然后突然跳变为新译文，造成"不连续"的感觉。

### 2.5 Hy-MT 润色引入额外延迟

当 final 翻译完成后，还会异步调用 Hy-MT 做二次润色。润色结果到达后再替换已显示的 ML Kit 译文，造成译文"二次跳变"。

### 2.6 Provisional 翻译被过度抑制

**文件**: `SubtitleOverlayService.kt` 的 `shouldSkipTranslation()`

多个条件会跳过 provisional 翻译：
- `bufferedFinalText.isNotBlank() && provisional` → 有 final 缓冲时跳过所有 provisional
- 文本增长不足 5 字符 → 跳过
- 与上次提交相同 → 跳过

这导致在说话过程中，用户几乎看不到"正在翻译"的中间状态。

## 三、优化方案

### 方案 A：快速调参（不改架构，立即可做）

| 参数 | 当前值 | 优化值 | 效果 |
|------|--------|--------|------|
| `stableWindowMs` | 1400ms | 200ms | 感知延迟降低 7x |
| `FINAL_TRANSLATION_DEBOUNCE_MS` | 1200ms | 100ms | 短句几乎即时翻译 |
| `IMMEDIATE_FINAL_TRANSLATION_LENGTH` | 36 | 20 | 更短文本立即翻译 |
| `minPartialLength` | 14 | 8 | 更短的 partial 也可触发翻译 |
| `minMeaningfulGrowthChars` | 5 | 3 | 降低增长门槛 |

### 方案 B：架构重构 — 增量流式翻译管道

#### 3.1 核心思路

把"整句翻译"改为"增量翻译"：
- ASR 每产出一个 partial，立即提取**新增部分**送去翻译
- 翻译结果以**前缀合并**方式更新 UI，而不是整句替换
- 引入**并发翻译管道**，最多 3 路并行

#### 3.2 新增组件：IncrementalTranslationPipeline

```kotlin
class IncrementalTranslationPipeline(
    private val translator: Translator,
    private val maxConcurrency: Int = 3
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Deferred<String>>()
    private val translationCache = LRUCache<String, String>(50)
    private var lastSubmittedSource: String = ""
    private var lastDisplayedTranslation: String = ""

    // 结果回调
    var onTranslationUpdate: ((source: String, translated: String, isPartial: Boolean) -> Unit)? = null

    fun submitPartial(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        // 计算与前次提交的 diff
        val commonPrefix = findCommonPrefix(lastSubmittedSource, normalized)
        val newPart = normalized.substring(commonPrefix)

        // 如果新增部分太短且不是结尾，跳过
        if (newPart.length < 3 && !isSentenceEnd(normalized)) return

        // 如果缓存中有完整翻译，直接使用
        translationCache[normalized]?.let { cached ->
            emitUpdate(normalized, cached, isPartial = true)
            return
        }

        // 取消过时的翻译任务
        cancelStaleJobs(normalized)

        // 并发提交翻译
        if (activeJobs.size < maxConcurrency) {
            val job = scope.async {
                try {
                    translator.translate(normalized).await().trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
            }
            activeJobs[normalized] = job

            scope.launch {
                val result = job.await()
                activeJobs.remove(normalized)
                if (result.isNotBlank()) {
                    translationCache.put(normalized, result)
                    emitUpdate(normalized, result, isPartial = true)
                }
            }
        }

        lastSubmittedSource = normalized
    }

    fun submitFinal(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return

        // 取消所有 partial 任务
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()

        // Final 立即翻译，不等待
        scope.launch {
            val result = try {
                translator.translate(normalized).await().trim()
            } catch (e: Exception) {
                ""
            }
            if (result.isNotBlank()) {
                translationCache.put(normalized, result)
                emitUpdate(normalized, result, isPartial = false)
            }
        }

        lastSubmittedSource = normalized
    }

    private fun emitUpdate(source: String, translated: String, isPartial: Boolean) {
        lastDisplayedTranslation = translated
        onTranslationUpdate?.invoke(source, translated, isPartial)
    }

    private fun cancelStaleJobs(currentText: String) {
        val stale = activeJobs.keys.filter { key ->
            !currentText.startsWith(key) && !key.startsWith(currentText)
        }
        stale.forEach { key ->
            activeJobs[key]?.cancel()
            activeJobs.remove(key)
        }
    }

    private fun findCommonPrefix(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        for (i in 0 until minLen) {
            if (a[i] != b[i]) return i
        }
        return minLen
    }

    private fun isSentenceEnd(text: String): Boolean {
        return text.endsWith("。") || text.endsWith("！") || text.endsWith("？") ||
            text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
    }

    fun release() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        scope.cancel()
    }
}
```

#### 3.3 新增组件：SmoothSubtitleRenderer

```kotlin
class SmoothSubtitleRenderer {
    private var displayedOriginal: String = ""
    private var displayedTranslation: String = ""
    private var committedOriginal: String = ""  // 已确认的原文部分
    private var committedTranslation: String = "" // 已确认的译文部分

    fun onPartialUpdate(source: String, translated: String) {
        // 分离已确认部分和变化部分
        val committedLen = findCommonPrefix(committedOriginal, source)
        committedOriginal = source.substring(0, committedLen)
        committedTranslation = translated.substring(0,
            minOf(committedTranslation.length,
                  mapSourceIndexToTarget(committedLen, source, translated)))

        displayedOriginal = source
        displayedTranslation = translated
    }

    fun onFinalUpdate(source: String, translated: String) {
        committedOriginal = source
        committedTranslation = translated
        displayedOriginal = source
        displayedTranslation = translated
    }

    fun getDisplayText(): Pair<String, String> {
        return displayedOriginal to displayedTranslation
    }

    private fun findCommonPrefix(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        for (i in 0 until minLen) {
            if (a[i] != b[i]) return i
        }
        return minLen
    }

    private fun mapSourceIndexToTarget(
        sourceIndex: Int, source: String, target: String
    ): Int {
        // 简单按比例映射
        if (source.isEmpty()) return 0
        val ratio = target.length.toFloat() / source.length.toFloat()
        return (sourceIndex * ratio).toInt().coerceIn(0, target.length)
    }
}
```

#### 3.4 重构 SubtitleOverlayService 的翻译调度

```kotlin
// 替换现有的 translateRecognizedText + queueFinalTranslation 逻辑

private var translationPipeline: IncrementalTranslationPipeline? = null
private var smoothRenderer = SmoothSubtitleRenderer()

private fun setupTranslationPipeline() {
    val currentTranslator = translator ?: return
    translationPipeline?.release()
    translationPipeline = IncrementalTranslationPipeline(currentTranslator).apply {
        onTranslationUpdate = { source, translated, isPartial ->
            serviceScope.launch {
                if (isPartial) {
                    smoothRenderer.onPartialUpdate(source, translated)
                } else {
                    smoothRenderer.onFinalUpdate(source, translated)
                }
                val (displayOrig, displayTrans) = smoothRenderer.getDisplayText()
                lastOriginalText = displayOrig
                lastTranslatedText = displayTrans
                translationState = if (isPartial) "实时翻译中" else "翻译完成"
                renderPipeline()
            }
        }
    }
}

// 在 ASR 事件处理中：
is AsrEvent.Partial -> {
    translationPipeline?.submitPartial(event.text)
}
is AsrEvent.Final -> {
    translationPipeline?.submitFinal(event.text)
}
```

### 方案 C：完整重构路线图

#### Phase 1: 参数调优（1天）
- 调整 Segmenter 和防抖参数
- 减少 provisional 翻译的跳过条件
- 预期效果：延迟从 ~2s 降到 ~500ms

#### Phase 2: 并发翻译管道（2-3天）
- 实现 IncrementalTranslationPipeline
- 替换串行翻译逻辑
- 预期效果：吞吐量提升 3x，多句翻译不再排队

#### Phase 3: 平滑渲染（1-2天）
- 实现 SmoothSubtitleRenderer
- 增量更新 UI 而非整句替换
- 预期效果：消除跳变感，翻译看起来"连续流出"

#### Phase 4: 翻译缓存 + 预翻译（1天）
- 对常见短语建立缓存
- 对 partial 增长趋势做预翻译
- 预期效果：常见表达几乎零延迟

## 四、关键代码变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `TranslationSegmenter.kt` | 修改参数 | stableWindowMs→200, minPartialLength→8 |
| `SubtitleOverlayService.kt` | 重构 | 替换翻译调度逻辑，接入 Pipeline |
| `PendingTranslationCoordinator.kt` | 可删除 | 被 Pipeline 替代 |
| `IncrementalTranslationPipeline.kt` | 新增 | 并发翻译管道 |
| `SmoothSubtitleRenderer.kt` | 新增 | 平滑渲染器 |
| `SubtitleOverlayFormatter.kt` | 微调 | 支持增量显示模式 |

## 五、性能预估

| 指标 | 当前 | Phase 1 | Phase 2 | Phase 3+4 |
|------|------|---------|---------|-----------|
| 首字延迟 | ~2000ms | ~500ms | ~300ms | ~200ms |
| 翻译吞吐 | 串行 | 串行 | 3路并发 | 3路并发+缓存 |
| 连续性 | 跳变 | 跳变 | 跳变 | 平滑增量 |
| 资源消耗 | 低 | 低 | 中 | 中 |
