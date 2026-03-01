# WaveGuard — System Architecture

## Table of Contents

- [Overview](#overview)
- [Three-Tier System](#three-tier-system)
- [Data Flow](#data-flow)
- [Component Descriptions](#component-descriptions)
  - [Tier 1 — ESP32-S3 Edge Node](#tier-1--esp32-s3-edge-node)
  - [Tier 2 — Android Application](#tier-2--android-application)
  - [Tier 3 — ML Inference Layer](#tier-3--ml-inference-layer)
- [Phase 1 vs Phase 2 Modes](#phase-1-vs-phase-2-modes)
- [Inter-Component Protocols](#inter-component-protocols)
- [Design Decisions](#design-decisions)
- [Security Considerations](#security-considerations)

---

## Overview

WaveGuard is a three-tier, edge-first presence detection system. The core design principle is **local inference** — no raw sensor data leaves the device and no cloud connectivity is required for operation. The tiers are:

1. **ESP32-S3 Edge Node** — captures raw Wi-Fi CSI frames from the 802.11 physical layer and performs first-pass feature extraction in firmware
2. **Android Application** — the primary user interface, BLE sink, and inference host
3. **ML Inference Layer** — a TFLite-compiled WiFlexFormer transformer that transforms CSI feature vectors into a binary (or multi-class) presence label

In Phase 1 (phone-only), Tier 1 is absent and the Android phone's own Wi-Fi chip supplies coarser RSSI measurements.

---

## Three-Tier System

```
┌─────────────────────────────────────────────────────────────────────────┐
│  TIER 1 — ESP32-S3 EDGE NODE                                             │
│                                                                          │
│  802.11 PHY (Wi-Fi NIC)                                                  │
│    │                                                                     │
│    ▼  raw CSI callback (esp_wifi_set_csi_rx_cb)                          │
│  CSI Collector (csi_collector.c)                                         │
│    │  • 52 subcarriers, complex I/Q per packet                           │
│    │  • ~100–500 packets/sec at 20 MHz channel                           │
│    ▼                                                                     │
│  Feature Extractor (feature_extractor.c)                                 │
│    │  • Amplitude vector  : |H_k| for k = 0..51                          │
│    │  • Phase vector      : ∠H_k (unwrapped, linear-drift corrected)     │
│    │  • Statistical feats : mean, variance, energy per subcarrier        │
│    │  • Windowing         : 128-sample sliding window, 50% overlap       │
│    ▼                                                                     │
│  BLE GATT Server (ble_server.c)                                          │
│    │  • Service UUID: 4A1B-...                                           │
│    │  • Characteristic: CSI_FEATURE_VEC (notify, 256 bytes)              │
│    └──────────────────────────────── BLE ──────────────────────────────▶ │
└─────────────────────────────────────────────────────────────────────────┘
                                          │
                                          │ Bluetooth LE (GATT Notify)
                                          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  TIER 2 — ANDROID APPLICATION                                            │
│                                                                          │
│  ┌──────────────────────┐   ┌───────────────────────────────────────┐   │
│  │  BLE GATT Client     │   │  RSSI Scanner (Phase 1 only)          │   │
│  │  (BleDataSink.kt)    │   │  (RssiScanner.kt)                     │   │
│  │  • connects to ESP32 │   │  • WifiManager.startScan() loop       │   │
│  │  • parses feature    │   │  • adaptive interval: 500 ms → 5 s   │   │
│  │    vector from BLE   │   │  • multi-AP RSSI matrix               │   │
│  └──────────┬───────────┘   └──────────────┬────────────────────────┘   │
│             │                              │                             │
│             └──────────────┬───────────────┘                            │
│                            ▼                                             │
│             FeatureAggregator.kt                                         │
│               • normalises inputs                                        │
│               • appends IMU features (Phase 1): accel variance,         │
│                 gyro magnitude                                           │
│               • emits InputTensor for inference                          │
│                            │                                             │
│                            ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  TIER 3 — ML INFERENCE LAYER                                     │    │
│  │                                                                  │    │
│  │  TFLite Runtime                                                  │    │
│  │    ├── Phase 2: WiFlexFormer (wiflexformer.tflite, ~450 KB)     │    │
│  │    └── Phase 1: Threshold classifier (no model file)            │    │
│  │                                                                  │    │
│  │  Output: PresenceLabel { ABSENT | PRESENT | UNCERTAIN }         │    │
│  │          + confidence score [0.0, 1.0]                          │    │
│  └──────────────────────────────┬──────────────────────────────────┘    │
│                                 │                                        │
│                                 ▼                                        │
│             PresenceStateEngine.kt                                       │
│               • hysteresis filter (debounce 3 consecutive frames)       │
│               • emits StateChangeEvent                                   │
│                                 │                                        │
│               ┌─────────────────┼─────────────────┐                     │
│               ▼                 ▼                  ▼                     │
│        Dashboard UI      Notification        Session Logger             │
│        (LiveChart,        (Android           (CSV/Room DB)              │
│         ConfidenceMeter)   NotifManager)                                 │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Component Descriptions

### Tier 1 — ESP32-S3 Edge Node

| Component | File | Responsibility |
|-----------|------|---------------|
| CSI Collector | `csi_collector.c` | Registers `esp_wifi_set_csi_rx_cb` callback; buffers raw CSI structs from the Wi-Fi driver |
| Feature Extractor | `feature_extractor.c` | Converts raw I/Q to amplitude/phase; applies Hampel + Savitzky-Golay filter; builds 128-sample sliding windows |
| BLE GATT Server | `ble_server.c` | Exposes a BLE service with a notify characteristic; serialises feature vectors as packed float32 arrays |
| Main task | `main.c` | FreeRTOS task orchestration; watchdog; LED status indicator |

**CSI Data Format (per window):**

```
struct CsiFeatureVector {
    float amp_mean[52];       // mean amplitude per subcarrier
    float amp_var[52];        // amplitude variance per subcarrier
    float phase_mean[52];     // mean unwrapped phase per subcarrier
    float phase_var[52];      // phase variance per subcarrier
    uint32_t timestamp_ms;    // ESP32 millisecond timestamp
    uint8_t reserved[4];
};
// Total: 52*4*4 + 8 = 840 bytes, fragmented over BLE MTU (247 bytes)
```

---

### Tier 2 — Android Application

The Android app is written in **Kotlin**, follows **MVVM + Clean Architecture**, and uses **Jetpack** libraries throughout.

#### Package Structure

```
com.waveguard/
├── ui/
│   ├── dashboard/          DashboardFragment, DashboardViewModel
│   ├── settings/           SettingsFragment, SettingsViewModel
│   ├── sessions/           SessionListFragment, SessionDetailFragment
│   └── onboarding/         OnboardingActivity, PermissionFragment
│
├── sensing/
│   ├── RssiScanner.kt      WifiManager scan loop + LiveData<List<ScanResult>>
│   ├── BleDataSink.kt      BLE GATT client; parses CsiFeatureVector packets
│   ├── ImuSampler.kt       SensorManager wrapper for accel + gyro
│   └── FeatureAggregator.kt  Normalises and merges all sensor inputs
│
├── ml/
│   ├── TFLiteInference.kt  TFLite interpreter wrapper (thread-safe)
│   ├── WiFlexFormerModel.kt  Input pre-processing, output post-processing
│   └── PresenceStateEngine.kt  Hysteresis + state machine
│
├── data/
│   ├── db/                 Room entities + DAOs (SessionEntity, SampleEntity)
│   ├── repository/         PresenceRepository, SessionRepository
│   └── preferences/        DataStore<Preferences> for user config
│
└── service/
    └── DetectionService.kt  Foreground service; holds WakeLock during active monitoring
```

#### Key Data Classes

```kotlin
data class PresenceResult(
    val label: PresenceLabel,       // ABSENT | PRESENT | UNCERTAIN
    val confidence: Float,          // 0.0 – 1.0
    val source: DetectionSource,    // RSSI_PHASE1 | CSI_PHASE2
    val timestampMs: Long
)

enum class PresenceLabel { ABSENT, PRESENT, UNCERTAIN }
enum class DetectionSource { RSSI_PHASE1, CSI_PHASE2 }
```

---

### Tier 3 — ML Inference Layer

#### WiFlexFormer (Phase 2)

WiFlexFormer is a lightweight transformer encoder adapted for time-series Wi-Fi CSI data. The architecture used in WaveGuard:

```
Input: [batch=1, seq_len=128, features=208]
         (208 = 52 subcarriers × 4 feature types)
  │
  ▼
Linear Embedding  →  [1, 128, d_model=64]
  │
  ▼
Positional Encoding (learned)
  │
  ▼
× 4 Transformer Encoder Layers
    └── Multi-Head Self-Attention (heads=4, d_k=16)
    └── Feed-Forward (d_ff=128, GELU)
    └── LayerNorm + Residual
  │
  ▼
[CLS] token pool  →  [1, 64]
  │
  ▼
MLP Head: Linear(64→32) → GELU → Dropout(0.1) → Linear(32→2)
  │
  ▼
Output: [absent_logit, present_logit]  →  softmax  →  confidence
```

**Model Stats (INT8 quantized TFLite):**

| Metric | Value |
|--------|-------|
| Model size | ~450 KB |
| Inference latency (Pixel 6) | ~18 ms |
| Inference latency (mid-range Android) | ~35 ms |
| Input tensor shape | [1, 128, 208] |
| Output tensor shape | [1, 2] |
| Parameters | ~180 K |

#### Phase 1 Classifier

In Phase 1, no neural network is used. Instead:

1. Compute **RSSI variance** across all visible APs over a 2-second window
2. Subtract the **calibration baseline** (variance in a known-empty room)
3. Apply a **threshold**: `variance_delta > θ` → PRESENT, else ABSENT
4. IMU fusion: if accelerometer magnitude > motion_threshold, suppress false positives

---

## Phase 1 vs Phase 2 Modes

| Aspect | Phase 1 (RSSI) | Phase 2 (CSI) |
|--------|---------------|---------------|
| Hardware | Phone only | Phone + ESP32-S3 |
| Signal | RSSI (1 scalar per AP) | CSI (52 complex subcarriers) |
| Update rate | ~2 Hz (Android scan throttle) | ~50–100 Hz |
| Feature resolution | Low (dBm aggregate) | High (per-subcarrier amplitude + phase) |
| Model | Statistical threshold | WiFlexFormer transformer |
| Accuracy (lab) | ~85% | ~97% |
| Accuracy (cross-room) | ~70% | ~91% |
| Latency | ~2 s | ~200 ms |
| Battery impact | Low | Low (BLE passive) |
| Setup complexity | Zero | Flash ESP32, pair BLE |

---

## Inter-Component Protocols

### BLE GATT Profile

```
Service: WaveGuard CSI Service
  UUID: 0000AA10-0000-1000-8000-00805F9B34FB

  Characteristic: CSI Feature Vector
    UUID: 0000AA11-0000-1000-8000-00805F9B34FB
    Properties: NOTIFY
    Descriptor: Client Characteristic Configuration (0x2902)
    Format: Little-endian packed float32 array
            (see CsiFeatureVector struct above)
    MTU: 247 bytes (negotiated)
    Transmission: fragmented if > MTU, reassembled in BleDataSink.kt

  Characteristic: Device Status
    UUID: 0000AA12-0000-1000-8000-00805F9B34FB
    Properties: READ | NOTIFY
    Format: 1 byte (0x00=idle, 0x01=scanning, 0x02=error)
```

### Internal Android (Coroutines / Flow)

```
RssiScanner          →  Flow<List<ScanResult>>
BleDataSink          →  Flow<CsiFeatureVector>
ImuSampler           →  Flow<ImuSample>
FeatureAggregator    →  Flow<InputTensor>
TFLiteInference      →  Flow<PresenceResult>
PresenceStateEngine  →  StateFlow<PresenceState>
```

---

## Design Decisions

### Why ESP32-S3 specifically?

The ESP32-S3 is the only current Espressif SoC with documented, stable CSI output via the `esp_wifi_set_csi_rx_cb` API **and** sufficient RAM (512 KB SRAM + PSRAM option) to buffer sliding windows. The original ESP32 and ESP32-C3 have less predictable CSI output. The ESP32-S3 also has a hardware floating-point unit, making amplitude/phase computation feasible in firmware without significant CPU overhead.

### Why BLE instead of Wi-Fi TCP?

BLE GATT Notify provides ~250 byte MTU at ~2 kB/s — sufficient for pre-computed feature vectors. A direct TCP socket would require the ESP32 to join the same Wi-Fi network, potentially interfering with the CSI measurement (the CSI receiver should be in monitor/promiscuous mode relative to the target AP's transmissions). BLE keeps the data path entirely separate from the measured medium.

### Why TFLite with INT8 quantization?

The WiFlexFormer model at FP32 is ~1.8 MB and takes ~90 ms per inference on a mid-range Android. INT8 post-training quantization (PTQ) reduces this to ~450 KB and ~18–35 ms with less than 1% accuracy drop, making real-time inference at 30 Hz feasible without draining the battery.

### Why a foreground service?

Android aggressively kills background processes that perform continuous scanning. A foreground service with a persistent notification is the only reliable mechanism to maintain the RSSI scan loop and BLE connection without interruption. The notification is minimised by default and can be disabled in system settings.

---

## Security Considerations

- **No network permissions** are used beyond Wi-Fi scanning (no `INTERNET` permission in Phase 1/2)
- **BLE pairing** uses Just Works bonding; the device name is not globally unique — users should verify the MAC address during initial pairing (shown in app)
- **CSI data** does not contain decodable packet payloads; it represents only the radio channel's impulse response, not user traffic content
- **Session logs** are stored in app-private storage (not accessible to other apps without root)
