# Playback Capture Local ASR Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Replace the current system SpeechRecognizer path with a real low-latency local ASR pipeline that consumes playback-captured PCM audio, produces Japanese text locally on-device, translates to Chinese, and preserves the visible overlay diagnostics.

**Architecture:** Keep the foreground `SubtitleOverlayService` and overlay formatter, but replace the recognition backend with a Vosk-based streaming decoder fed by our own `AudioRecord` playback-capture pipeline. Use Vosk small Japanese model as the first practical local ASR, show partial/final recognition separately, translate only coalesced final text to minimize latency jitter and duplicate work, and optionally dump PCM/WAV for field debugging.

**Tech Stack:** Kotlin, Android foreground service, MediaProjection playback capture, AudioRecord PCM16 mono 16k, Vosk Android, ML Kit translation, JUnit unit tests, GitHub Actions Android build.

---

### Task 1: Add failing unit tests for the new overlay/runtime wording

**Objective:** Lock down the user-visible statuses needed for local ASR, low-latency buffering, and debug dumping before production changes.

**Files:**
- Modify: `app/src/test/java/com/wenjh/fanyiapp/SubtitleOverlayFormatterTest.kt`
- Modify: `app/src/test/java/com/wenjh/fanyiapp/SubtitlePipelineBootstrapPlannerTest.kt`

**Step 1: Write failing tests**

Add formatter tests for:
- local ASR model loading hint
- playback capture active hint
- PCM dump active hint
- translated fallback when local ASR partial exists but translation not finalized yet

Add bootstrap planner tests for a renamed/expanded recognition stage that no longer references system SpeechRecognizer semantics.

**Step 2: Run tests to verify failure**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.SubtitleOverlayFormatterTest --tests com.wenjh.fanyiapp.SubtitlePipelineBootstrapPlannerTest`
Expected: FAIL because the old formatter/planner wording and enums do not yet match.

**Step 3: Write minimal code later in following tasks**

No production changes in this task beyond what is needed to make tests compile in later tasks.

**Step 4: Commit**

```bash
git add app/src/test/java/com/wenjh/fanyiapp/SubtitleOverlayFormatterTest.kt app/src/test/java/com/wenjh/fanyiapp/SubtitlePipelineBootstrapPlannerTest.kt
git commit -m "test: define local asr overlay and bootstrap expectations"
```

### Task 2: Add Vosk dependency and model-delivery plumbing

**Objective:** Make the project able to build with a local Japanese ASR engine and prepare first-run model installation.

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/wenjh/fanyiapp/VoskModelManager.kt`
- Create: `app/src/test/java/com/wenjh/fanyiapp/VoskModelManagerTest.kt`

**Step 1: Write failing test**

Add unit tests for model-path selection / status formatting logic in `VoskModelManagerTest.kt`, for example:
- `resolveModelState_reportsPreparingWhenModelMissing()`
- `resolveModelState_reportsReadyWhenModelMarkerExists()`

Keep this logic pure and testable without Android instrumentation by factoring file-state decisions into Kotlin helpers.

**Step 2: Run test to verify failure**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.VoskModelManagerTest`
Expected: FAIL because `VoskModelManager` does not exist.

**Step 3: Write minimal implementation**

- Add `implementation("com.alphacephei:vosk-android:0.3.75")`
- Add packaging options if needed to avoid native duplicate conflicts.
- Create `VoskModelManager` that:
  - checks app files dir for unpacked `model-ja-small`
  - if absent, copies/unpacks from app assets into app storage
  - surfaces states like `正在准备本地日语识别模型`, `本地日语识别模型就绪`, `本地日语识别模型准备失败`
- Keep the model manager lifecycle independent from the service so it can be tested in small units.

**Step 4: Run test to verify pass**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.VoskModelManagerTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/wenjh/fanyiapp/VoskModelManager.kt app/src/test/java/com/wenjh/fanyiapp/VoskModelManagerTest.kt
git commit -m "feat: add vosk model manager scaffolding"
```

### Task 3: Add playback-capture audio source and debug dump helpers

**Objective:** Implement a real PCM source for playback capture and optional low-overhead debug dumping.

**Files:**
- Create: `app/src/main/java/com/wenjh/fanyiapp/PlaybackCaptureAudioSource.kt`
- Create: `app/src/main/java/com/wenjh/fanyiapp/AudioDebugDumpWriter.kt`
- Create: `app/src/test/java/com/wenjh/fanyiapp/AudioDebugDumpWriterTest.kt`
- Modify: `app/src/main/java/com/wenjh/fanyiapp/MainActivity.kt`

**Step 1: Write failing test**

Add unit tests for `AudioDebugDumpWriter` pure helpers:
- wav header sizing
- pcm filename rotation / cap policy
- disabled mode produces no writes decision

**Step 2: Run test to verify failure**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.AudioDebugDumpWriterTest`
Expected: FAIL because the classes do not exist.

**Step 3: Write minimal implementation**

Implement `PlaybackCaptureAudioSource` that:
- builds `AudioPlaybackCaptureConfiguration` from `MediaProjection`
- creates `AudioRecord` at 16k mono PCM16
- exposes `start()`, `read(shortArray)`, `stop()`
- can fall back to microphone-only local ASR when playback capture cannot initialize

Implement `AudioDebugDumpWriter` that:
- optionally writes rolling `.pcm` or `.wav` samples into app external files dir
- keeps file count small
- writes asynchronously enough to avoid decode stalls

Update `MainActivity` so service launch includes explicit debug-dump flags.

**Step 4: Run test to verify pass**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.AudioDebugDumpWriterTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/wenjh/fanyiapp/PlaybackCaptureAudioSource.kt app/src/main/java/com/wenjh/fanyiapp/AudioDebugDumpWriter.kt app/src/test/java/com/wenjh/fanyiapp/AudioDebugDumpWriterTest.kt app/src/main/java/com/wenjh/fanyiapp/MainActivity.kt
git commit -m "feat: add playback capture audio source and debug dump support"
```

### Task 4: Add Vosk streaming recognizer abstraction

**Objective:** Introduce a recognizer component that accepts PCM chunks and emits partial/final Japanese text with minimal latency.

**Files:**
- Create: `app/src/main/java/com/wenjh/fanyiapp/VoskStreamingRecognizer.kt`
- Create: `app/src/test/java/com/wenjh/fanyiapp/VoskStreamingRecognizerTest.kt`

**Step 1: Write failing test**

Create pure Kotlin tests around JSON parsing / de-dup / coalescing helpers:
- parse partial result JSON
- parse final result JSON
- ignore duplicate finals
- avoid empty partial churn

**Step 2: Run test to verify failure**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.VoskStreamingRecognizerTest`
Expected: FAIL because the recognizer wrapper does not exist.

**Step 3: Write minimal implementation**

Implement `VoskStreamingRecognizer` that:
- owns `Model` + `Recognizer`
- receives `ShortArray` frames
- returns partial/final events through callbacks or a small listener interface
- includes helper methods for parsing Vosk JSON safely
- coalesces repeated finals and noisy partials to keep overlay responsive

Favor a chunk size around 160–240 ms initially for low latency without excessive callback churn.

**Step 4: Run test to verify pass**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.VoskStreamingRecognizerTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/wenjh/fanyiapp/VoskStreamingRecognizer.kt app/src/test/java/com/wenjh/fanyiapp/VoskStreamingRecognizerTest.kt
git commit -m "feat: add streaming vosk recognizer wrapper"
```

### Task 5: Refactor bootstrap planner away from SpeechRecognizer semantics

**Objective:** Make startup sequencing match the new local-ASR architecture.

**Files:**
- Modify: `app/src/main/java/com/wenjh/fanyiapp/SubtitlePipelineBootstrapPlanner.kt`
- Modify: `app/src/test/java/com/wenjh/fanyiapp/SubtitlePipelineBootstrapPlannerTest.kt`

**Step 1: Write failing test**

Update planner tests to expect stages like:
- `PREPARE_AUDIO_SOURCE`
- `PREPARE_ASR`
- `START_RECOGNITION`
- `PREPARE_TRANSLATOR`

**Step 2: Run test to verify failure**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.SubtitlePipelineBootstrapPlannerTest`
Expected: FAIL.

**Step 3: Write minimal implementation**

Refactor planner enums and sequences so startup becomes:
- determine input mode
- prepare audio source
- prepare local ASR
- start recognition loop
- warm translator in background

If no playable input is available, still permit microphone fallback local ASR when possible.

**Step 4: Run test to verify pass**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.SubtitlePipelineBootstrapPlannerTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/wenjh/fanyiapp/SubtitlePipelineBootstrapPlanner.kt app/src/test/java/com/wenjh/fanyiapp/SubtitlePipelineBootstrapPlannerTest.kt
git commit -m "refactor: align bootstrap planner with local asr pipeline"
```

### Task 6: Integrate playback capture + local ASR into the foreground service

**Objective:** Replace the system SpeechRecognizer lifecycle in `SubtitleOverlayService` with the new audio source and Vosk stream.

**Files:**
- Modify: `app/src/main/java/com/wenjh/fanyiapp/SubtitleOverlayService.kt`
- Modify: `app/src/main/java/com/wenjh/fanyiapp/SubtitleOverlayFormatter.kt`
- Modify: `app/src/test/java/com/wenjh/fanyiapp/SubtitleOverlayFormatterTest.kt`

**Step 1: Write failing tests**

Update formatter tests to expect local-ASR-specific states such as:
- `本地识别模型就绪`
- `播放音频捕获中`
- `本地识别中（实时）`
- `调试录音已保存`
- proper Chinese fallback when only partial Japanese exists

**Step 2: Run tests to verify failure**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.SubtitleOverlayFormatterTest`
Expected: FAIL.

**Step 3: Write minimal implementation**

In `SubtitleOverlayService`:
- remove dependency on Android `SpeechRecognizer`
- create state for:
  - audio source status
  - model load status
  - ASR partial/final status
  - dump writer status
- wire startup through the planner:
  - prepare audio source
  - prepare Vosk model/recognizer
  - start read/decode loop
  - warm translator asynchronously
- on partial result:
  - update Japanese text live
  - do not translate yet unless a heuristic says the segment is stable enough
- on final result:
  - trigger translation
  - schedule next segment cleanly without restarting whole service
- if playback capture fails but microphone local ASR is possible, switch modes explicitly
- preserve visible diagnostics in notification + overlay

**Step 4: Run tests to verify pass**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.SubtitleOverlayFormatterTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/wenjh/fanyiapp/SubtitleOverlayService.kt app/src/main/java/com/wenjh/fanyiapp/SubtitleOverlayFormatter.kt app/src/test/java/com/wenjh/fanyiapp/SubtitleOverlayFormatterTest.kt
git commit -m "feat: switch overlay service to playback capture local asr"
```

### Task 7: Add low-latency translation coalescing heuristics

**Objective:** Minimize end-to-end delay while avoiding translation spam and duplicate subtitles.

**Files:**
- Create: `app/src/main/java/com/wenjh/fanyiapp/TranslationSegmenter.kt`
- Create: `app/src/test/java/com/wenjh/fanyiapp/TranslationSegmenterTest.kt`
- Modify: `app/src/main/java/com/wenjh/fanyiapp/SubtitleOverlayService.kt`

**Step 1: Write failing test**

Add unit tests for heuristics like:
- ignore duplicate final text
- translate partial when punctuation or minimum stable age threshold reached
- flush final text immediately
- trim overlap between previous and next segment

**Step 2: Run test to verify failure**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.TranslationSegmenterTest`
Expected: FAIL because the helper does not exist.

**Step 3: Write minimal implementation**

Implement `TranslationSegmenter` as a pure Kotlin helper that:
- tracks last partial/final text
- decides when to emit a translation request
- deduplicates repeated Vosk outputs
- supports a conservative low-latency mode favoring quick subtitle updates

Integrate it into the service so translation is called on meaningful stable chunks, not every callback.

**Step 4: Run test to verify pass**

Run: `gradle testDebugUnitTest --tests com.wenjh.fanyiapp.TranslationSegmenterTest`
Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/wenjh/fanyiapp/TranslationSegmenter.kt app/src/test/java/com/wenjh/fanyiapp/TranslationSegmenterTest.kt app/src/main/java/com/wenjh/fanyiapp/SubtitleOverlayService.kt
git commit -m "feat: add low latency translation segmenter"
```

### Task 8: Add model asset placeholder and runtime docs

**Objective:** Make the repo self-describing for how the Japanese ASR model is delivered and used.

**Files:**
- Create: `app/src/main/assets/model-ja-small/.gitkeep`
- Create: `app/src/main/assets/model-ja-small/README.txt`
- Modify: `README.md`

**Step 1: Write content**

Document:
- why Vosk was chosen for phase 1
- expected model location
- how first-run extraction works
- current latency tradeoffs
- playback capture limitations on vendor ROMs/apps
- where debug WAV/PCM files are saved

**Step 2: Verify docs**

Run: `search_files("model-ja-small", path="/home/ubuntu/fanyiapp", target="content")`
Expected: README and asset placeholder are discoverable.

**Step 3: Commit**

```bash
git add app/src/main/assets/model-ja-small/.gitkeep app/src/main/assets/model-ja-small/README.txt README.md
git commit -m "docs: describe local asr model delivery and debug flow"
```

### Task 9: Build and run unit tests locally

**Objective:** Verify the repo builds cleanly and the new logic is regression-covered.

**Files:**
- Modify if needed: any touched files for final fixes

**Step 1: Generate wrapper if still missing**

Run:
```bash
gradle wrapper --gradle-version 8.7
```

**Step 2: Run targeted tests**

Run:
```bash
./gradlew testDebugUnitTest --no-daemon
```
Expected: PASS.

**Step 3: Build debug APK**

Run:
```bash
./gradlew assembleDebug --stacktrace --no-daemon
```
Expected: PASS and APK under `app/build/outputs/apk/debug/`.

**Step 4: Commit fixes if required**

```bash
git add -A
git commit -m "fix: stabilize local asr build and tests"
```

### Task 10: Push to GitHub and trigger APK production through Actions

**Objective:** Deliver a buildable GitHub state that produces the next debug APK artifact.

**Files:**
- No source-file target; repository state only.

**Step 1: Check git state**

Run:
```bash
git status --short
git log --oneline -5
```

**Step 2: Push to main**

Run:
```bash
git push origin main
```

If HTTPS auth fails in this environment, run:
```bash
gh auth setup-git
git push origin main
```

**Step 3: Verify workflow run exists**

Run:
```bash
gh run list --workflow "Android Debug APK" --limit 5
```
Expected: a new run for the pushed commit appears.

**Step 4: Report artifact path or workflow run status**

Summarize:
- commit hash
- workflow run id/status
- where the APK artifact will appear

---

## Implementation notes

- Prefer Vosk small Japanese model for phase 1 because it is the fastest realistic local streaming ASR to integrate into this repo now.
- Keep audio sample rate fixed at 16 kHz mono PCM16 end-to-end to reduce conversion overhead and avoid recognizer mismatch.
- Translate only stabilized segments or finals to keep latency low without flooding ML Kit.
- Do not block recognition on translator warmup; translator must stay async in background.
- Preserve user-visible overlay diagnostics at all times.
- If model asset cannot be committed due to repo size, leave asset placeholder + runtime README and implement runtime extraction/download hook so the code path is ready.
