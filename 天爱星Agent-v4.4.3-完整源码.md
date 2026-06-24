# 天爱星 Agent v4.4.3 完整源码变更

**日期**: 2026-06-25
**版本**: v4.4.3
**versionCode**: 6
**APK**: 天爱星Agent-v4.4.3.apk (~587MB)

---

## 变更概览

Kokoro TTS 完整修复链路：
1. **模型替换** — model_q8f16.onnx(残缺) → model.onnx(完整 FP32, 325MB)
2. **AudioTrack 升级** — MediaPlayer → AudioTrack (PERFORMANCE_MODE_LOW_LATENCY)
3. **输出节点兼容** — tryExtractAudio() 4 节点名回退
4. **全链路诊断** — TTSHealthReport + selfCheck + runDiagnosticsAndSpeak
5. **智能路由** — AudioRouteManager 设备感知 + 热插拔 + 强制校正
6. **440Hz 测试音** — 排除 TTS 链路的纯 AudioTrack 验证
7. **路由日志** — 每次播放后记录路由/设备/音量快照

---

## 新增文件

### 1. `app/src/main/java/com/lightagent/tts/TTSHealthReport.kt`
7 项检查报告数据类：Model / Style / Voice / Waveform / PCM / AudioTrack / Sound

### 2. `app/src/main/java/com/lightagent/tts/AudioRouteManager.kt`
智能音频路由管理器：
- `init()` — 初始化 MODE_NORMAL + 路由选择 + 注册 BroadcastReceiver
- `forceBeforePlay()` — 播放前强制校正（mode + speakerphone + BT SCO）
- `updateRoute()` — 检测输出设备列表，自动选择扬声器/有线/蓝牙
- `DeviceChangeReceiver` — 监听 ACTION_AUDIO_BECOMING_NOISY / HEADSET_PLUG / 蓝牙状态变化
- `snapshot()` — 返回当前路由状态摘要

### 3. `app/src/main/assets/kokoro/model.onnx`
- 来源: hf-mirror.com/onnx-community/Kokoro-82M-v1.0-ONNX
- 大小: 325,532,232 bytes (完整 FP32)
- 输入: input_ids(1,N) + style(1,256) + speed(1,1)
- 输出: waveform(1,1,M)

---

## 修改文件

### 4. `app/src/main/java/com/lightagent/tts/KokoroTTS.kt`
- `MODEL_FILE` → `"kokoro/model.onnx"`
- 新增诊断接口: `initialized`, `getInputNames()`, `getOutputNames()`, `getCurrentStyle()`
- `tryExtractAudio()` — 多节点名回退 (waveform/audio/output/wav)
- `copyAsset()` 模型文件始终强制覆盖

### 5. `app/src/main/java/com/lightagent/tts/KokoroTTSManager.kt`
- 移除 `MediaPlayer`，全面使用 `AudioTrack`
- `playPcm()` — MODE_STREAM + PERFORMANCE_MODE_LOW_LATENCY
- `monitoredPlay()` — MODE_STATIC + underrun + playbackHeadPosition
- 集成 `AudioRouteManager`: `routeManager.forceBeforePlay()` 在每次播放前
- 新增诊断套件: `selfCheck()`, `runDiagnosticsAndSpeak()`, `checkPcmQuality()`, `pcmEnergy()`
- 新增: `playTestTone()` 440Hz, `logAudioRouting()`, `logAudioRoutingQuick()`, `logAudioRouteDetail()`
- `release()` 中 `routeManager.unregister()`

### 6. `app/src/main/java/com/lightagent/ui/ChatViewModel.kt`
- `runTtsDiagnostics(text)` — 一键触发全链路诊断 + 路由诊断 + 测试音

### 7. `app/build.gradle`
- ONNX Runtime: `1.17.3` → `1.20.0`
- versionCode: `5` → `6`
- versionName: `"4.4.2"` → `"4.4.3"`

### 8. **已删除**
- `assets/kokoro/model_q8f16.onnx` (86MB 残缺量化版)

---

## 架构图

```
ChatViewModel
  │
  ├─ feedStream() / flushStream()   ← 日常 TTS
  │     └─ KokoroTTSManager
  │           ├─ AudioRouteManager.forceBeforePlay()   ← 路由校正
  │           ├─ KokoroTTS.synthesize(text)            ← ONNX 推理
  │           ├─ playPcm(pcm)                          ← AudioTrack 播放
  │           └─ logAudioRouteDetail(track)            ← 路由验证
  │
  └─ runTtsDiagnostics(text)        ← 一键诊断
        ├─ selfCheck()
        ├─ synthesize → checkPcmQuality()
        ├─ monitoredPlay()
        ├─ logAudioRouting()
        └─ playTestTone()
```

---

## 预期日志 (tts_debug.log)

```
═══ TTS 诊断开始 ═══
[OK] 模型输入: [input_ids, style, speed]
[OK] 模型输出: [waveform]
[OK] Style 256 维，非零值: 233
[INFO] PCM 长度 = 43200
[OK] PCM 质量正常
[OK] AudioTrack 播放完成
  routing_stats: speaker=true mode=0 attrs={USAGE_MEDIA,CONTENT_TYPE_SPEECH} fmt={PCM_16,24000,MONO}
═══ 音频路由诊断 ═══
STREAM_MUSIC volume = 7/15
STREAM_MUSIC muted  = false
mode                = 0(0=normal,1=ring,2=incall,3=comm)
isSpeakerphoneOn    = true
━━ 输出设备 ━━
  内置扬声器 (扬声器)
── 播放 440Hz 测试音 ──
测试音完成: headPos=24000, underrun=0
  routing_stats: speaker=true mode=0 attrs={USAGE_MEDIA,CONTENT_TYPE_SPEECH}
═══ TTS 诊断结束 ═══
```
