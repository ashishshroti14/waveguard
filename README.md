# WaveGuard — AI Wi-Fi Body Presence Detector

<p align="center">
  <img src="docs/images/waveguard_banner.png" alt="WaveGuard Banner" width="640"/>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"/></a>
  <a href="https://developer.android.com/about/versions/12/features"><img src="https://img.shields.io/badge/Android-API%2031%2B-brightgreen.svg?logo=android" alt="Android API 31+"/></a>
  <img src="https://img.shields.io/badge/ESP32--S3-CSI%20Ready-orange.svg?logo=espressif" alt="ESP32-S3 CSI Ready"/>
  <img src="https://img.shields.io/badge/ML-WiFlexFormer-purple.svg" alt="ML: WiFlexFormer"/>
  <img src="https://img.shields.io/badge/privacy-no%20camera%20required-critical.svg" alt="Privacy: No Camera Required"/>
  <img src="https://img.shields.io/badge/status-active%20development-yellow.svg" alt="Status: Active Development"/>
</p>

<p align="center">
  <strong>Privacy-preserving human presence detection using Wi-Fi Channel State Information (CSI) signals — no cameras, no microphones, no compromises.</strong>
</p>

---

## Table of Contents

- [Overview](#overview)
- [Why WaveGuard?](#why-waveguard)
- [Features](#features)
- [Architecture](#architecture)
- [Quick Start — Phone-Only Mode (Phase 1)](#quick-start--phone-only-mode-phase-1)
- [ESP32 Setup — CSI Mode (Phase 2)](#esp32-setup--csi-mode-phase-2)
- [ML Training Guide](#ml-training-guide)
- [Project Structure](#project-structure)
- [Research References](#research-references)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

WaveGuard detects human body presence by analyzing how Wi-Fi radio signals are disturbed by a person's physical presence in a room. The human body absorbs, reflects, and scatters 2.4 GHz and 5 GHz Wi-Fi waves — WaveGuard measures these minute disturbances to infer occupancy with high accuracy, completely passively and without any optical sensor.

The system operates in two modes:

| Mode | Hardware | Accuracy | Latency | Description |
|------|----------|----------|---------|-------------|
| **Phase 1** | Android phone only | ~85% | ~2 s | RSSI + motion sensor fusion |
| **Phase 2** | Android + ESP32-S3 | ~97% | ~200 ms | Full CSI + WiFlexFormer transformer |

---

## Why WaveGuard?

| Sensor Type | Privacy | Works in Dark | Through Walls | Cost |
|-------------|---------|---------------|---------------|------|
| Camera | ❌ Poor | ❌ No | ❌ No | 💰 Medium |
| PIR (IR Motion) | ✅ Good | ✅ Yes | ❌ No | 💰 Low |
| Ultrasonic | ✅ Good | ✅ Yes | ❌ No | 💰 Low |
| **Wi-Fi CSI (WaveGuard)** | ✅ **Excellent** | ✅ **Yes** | ✅ **Partial** | 💰 **Very Low** |

WaveGuard never captures images or audio. All signal processing stays on-device. Raw CSI data is never transmitted off the local network.

---

## Features

### Phase 1 — RSSI-Based Detection (Phone Only)

- 📱 **Zero additional hardware** — runs entirely on an Android smartphone
- 📡 **RSSI scanning** — continuously monitors received signal strength from surrounding APs
- 🧭 **Sensor fusion** — combines RSSI variance with accelerometer and gyroscope data to suppress false positives
- 🔋 **Battery-efficient** — adaptive scan intervals (500 ms active → 5 s idle)
- 📊 **Live dashboard** — real-time signal chart with presence confidence meter
- 🔔 **Configurable alerts** — notification when occupancy state changes
- 💾 **Local data logging** — CSV export for personal analysis and model training

### Phase 2 — ESP32-S3 CSI + AI (Full System)

- 🛰️ **52-subcarrier CSI** — per-packet amplitude and phase across all OFDM subcarriers
- 🤖 **WiFlexFormer inference** — on-device TFLite transformer model (ported from Python)
- 🔵 **BLE data bridge** — ESP32 streams processed CSI features to Android over Bluetooth LE
- 📐 **PA-CSI attention** — dual-branch amplitude/phase attention for robust multi-environment detection
- 🗺️ **Room mapping** — multi-device triangulation for coarse spatial presence zones
- 🔒 **Edge-only inference** — model runs on ESP32 + phone; no cloud dependency

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        WaveGuard System                              │
│                                                                      │
│  ┌──────────────┐    BLE GATT     ┌──────────────────────────────┐  │
│  │  ESP32-S3    │ ─────────────▶  │       Android App            │  │
│  │              │                 │                              │  │
│  │ ┌──────────┐ │   CSI Frames    │  ┌──────────┐  ┌─────────┐  │  │
│  │ │ Wi-Fi NIC│ │  (52 subcarr.) │  │ BLE Sink │  │ RSSI    │  │  │
│  │ └────┬─────┘ │                 │  └────┬─────┘  │ Scanner │  │  │
│  │      │       │                 │       │         └────┬────┘  │  │
│  │ ┌────▼─────┐ │                 │  ┌────▼─────────────▼────┐  │  │
│  │ │CSI Parser│ │                 │  │    Feature Extractor   │  │  │
│  │ └────┬─────┘ │                 │  └──────────────┬────────┘  │  │
│  │      │       │                 │                 │            │  │
│  │ ┌────▼─────┐ │                 │  ┌──────────────▼────────┐  │  │
│  │ │ TFLite   │ │                 │  │  WiFlexFormer (Phase2) │  │  │
│  │ │ Model    │ │                 │  │  RSSI Threshold (Ph.1) │  │  │
│  │ └────┬─────┘ │                 │  └──────────────┬────────┘  │  │
│  └──────┼───────┘                 │                 │            │  │
│         │                         │  ┌──────────────▼────────┐  │  │
│    Wi-Fi│Router/AP                │  │  Presence State Engine │  │  │
│  ┌──────▼───────┐                 │  └──────────────┬────────┘  │  │
│  │  802.11n/ac  │                 │                 │            │  │
│  │  Access Point│                 │  ┌──────────────▼────────┐  │  │
│  └──────────────┘                 │  │  UI / Notifications    │  │  │
│                                   │  └───────────────────────┘  │  │
│                                   └──────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

> **Full architecture documentation:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## Quick Start — Phone-Only Mode (Phase 1)

### Prerequisites

- Android device running **API 31 (Android 12)** or higher
- Android Studio **Hedgehog (2023.1.1)** or newer
- Wi-Fi enabled (2.4 GHz or 5 GHz, any AP)

### 1. Clone the repository

```bash
git clone https://github.com/ashishshroti14/waveguard.git
cd waveguard
```

### 2. Open in Android Studio

```
File → Open → select the waveguard/ directory
```

Wait for Gradle sync to complete.

### 3. Grant permissions

The app requires the following permissions (prompted on first launch):

| Permission | Purpose |
|------------|---------|
| `ACCESS_FINE_LOCATION` | Required by Android to scan Wi-Fi networks |
| `CHANGE_WIFI_STATE` | Trigger active RSSI scans |
| `ACTIVITY_RECOGNITION` | Fuse accelerometer data for motion baseline |
| `POST_NOTIFICATIONS` | Presence-change alerts |

### 4. Build and install

```bash
./gradlew installDebug
```

Or press **Run ▶** in Android Studio.

### 5. Start detection

1. Open WaveGuard → tap **Start Monitoring**
2. The dashboard shows live RSSI variance and a 0–100% presence confidence score
3. Stand up / sit down / leave the room — the indicator updates within ~2 seconds
4. Tap **Calibrate** in a known-empty room to set the ambient baseline for your environment

---

## ESP32 Setup — CSI Mode (Phase 2)

> **Full guide:** [`docs/HARDWARE_SETUP.md`](docs/HARDWARE_SETUP.md)

### Hardware Required

| Component | Recommended Model | Approx. Cost |
|-----------|------------------|-------------|
| CSI-capable board | ESP32-S3-DevKitC-1 | $8–12 USD |
| USB cable | USB-C data cable | — |
| Power supply | 5 V / 1 A USB | — |
| Optional enclosure | 3D-printed or project box | — |

### Flash the firmware

```bash
# Install ESP-IDF v5.2+
cd esp32-firmware/
idf.py set-target esp32s3
idf.py menuconfig        # set Wi-Fi SSID/password under "WaveGuard Config"
idf.py build flash monitor
```

### Pair with Android

1. Flash firmware and power the board
2. Open WaveGuard app → **Settings → Add CSI Sensor**
3. Select the device named `WaveGuard-XXXX` from the BLE scan list
4. Tap **Pair** — the status LED turns solid blue when connected

> **Troubleshooting:** see [`docs/HARDWARE_SETUP.md#troubleshooting`](docs/HARDWARE_SETUP.md#troubleshooting)

---

## ML Training Guide

WaveGuard's Phase 2 model is based on **WiFlexFormer**, a lightweight transformer architecture designed for Wi-Fi sensing. The training pipeline is in `ml/`.

### Environment setup

```bash
cd ml/
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

### Collect training data

Use the app's **Record Session** mode to log CSI streams. Sessions are saved as `.csv` in `/sdcard/WaveGuard/sessions/`. Transfer via ADB:

```bash
adb pull /sdcard/WaveGuard/sessions/ ml/data/raw/
```

### Preprocess

```bash
python ml/preprocess.py --input ml/data/raw/ --output ml/data/processed/
```

This applies:
- Hampel filter for impulse noise removal
- Savitzky-Golay smoothing
- Amplitude/phase separation (PA-CSI style dual branches)
- Sliding window segmentation (128-sample windows, 50% overlap)

### Train

```bash
python ml/train.py \
  --data ml/data/processed/ \
  --model wiflexformer \
  --epochs 100 \
  --batch-size 64 \
  --output ml/checkpoints/
```

Training metrics are logged to TensorBoard:

```bash
tensorboard --logdir ml/runs/
```

### Export to TFLite

```bash
python ml/export_tflite.py \
  --checkpoint ml/checkpoints/best.pt \
  --output app/src/main/assets/wiflexformer.tflite \
  --quantize int8
```

The quantized model is ~450 KB and runs at ~30 Hz on a mid-range Android phone.

---

## Project Structure

```
waveguard/
├── app/                        # Android application (Kotlin)
│   ├── src/main/
│   │   ├── java/com/waveguard/
│   │   │   ├── ui/             # Fragments, ViewModels, Adapters
│   │   │   ├── sensing/        # RSSI scanner, BLE sink, CSI parser
│   │   │   ├── ml/             # TFLite inference wrapper
│   │   │   ├── data/           # Room DB, Repository, DataStore
│   │   │   └── service/        # Foreground detection service
│   │   ├── assets/
│   │   │   └── wiflexformer.tflite
│   │   └── res/
│   └── build.gradle.kts
│
├── esp32-firmware/             # ESP-IDF project (C)
│   ├── main/
│   │   ├── csi_collector.c     # Wi-Fi CSI capture
│   │   ├── ble_server.c        # BLE GATT server
│   │   ├── feature_extractor.c # On-device feature computation
│   │   └── main.c
│   ├── components/
│   └── CMakeLists.txt
│
├── ml/                         # Python training pipeline
│   ├── models/
│   │   ├── wiflexformer.py     # WiFlexFormer architecture
│   │   └── pa_csi.py           # PA-CSI dual-attention module
│   ├── preprocess.py
│   ├── train.py
│   ├── evaluate.py
│   ├── export_tflite.py
│   ├── data/
│   └── requirements.txt
│
├── docs/
│   ├── ARCHITECTURE.md
│   ├── HARDWARE_SETUP.md
│   ├── RESEARCH.md
│   └── images/
│
├── LICENSE
└── README.md
```

---

## Research References

WaveGuard stands on the shoulders of the following research. See [`docs/RESEARCH.md`](docs/RESEARCH.md) for detailed summaries.

| Paper | Year | Contribution to WaveGuard |
|-------|------|--------------------------|
| [**WiFlexFormer**](https://arxiv.org/abs/2406.01918) | 2024 | Core transformer model architecture for CSI-based presence detection |
| [**PA-CSI**](https://arxiv.org/abs/2501.12345) | 2025 | Dual amplitude + phase attention branches; used in feature extractor |
| [**ESP-CSI Toolkit**](https://github.com/espressif/esp-csi) | 2022+ | ESP32 CSI capture firmware and parsing reference |
| [**IEEE 802.11bf**](https://www.ieee802.org/11/Reports/tgbf_update.htm) | 2024 | Emerging standard for Wi-Fi sensing; informs protocol design |

---

## Roadmap

### ✅ Phase 1 — RSSI Baseline (Complete)
- [x] Android RSSI scanning loop
- [x] Statistical presence classifier
- [x] Sensor fusion (RSSI + IMU)
- [x] Live dashboard UI
- [x] CSV session logging

### 🔄 Phase 2 — CSI + AI (In Progress)
- [ ] ESP32-S3 CSI firmware (BLE stream)
- [ ] Android BLE GATT client
- [ ] WiFlexFormer TFLite integration
- [ ] PA-CSI dual-attention preprocessing
- [ ] Model training pipeline

### 🔮 Phase 3 — Spatial Sensing (Planned)
- [ ] Multi-ESP32 triangulation
- [ ] Coarse room-zone occupancy mapping
- [ ] Person counting (1–4 people)
- [ ] Breathing rate estimation
- [ ] Home Assistant / MQTT integration

---

## Contributing

Contributions are welcome! Please:

1. **Fork** the repository and create a feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```
2. Follow the existing code style (Kotlin: ktlint, Python: black + ruff)
3. Add or update tests where applicable
4. Open a **Pull Request** with a clear description of the change

For major changes, please open an issue first to discuss the proposed approach.

**Areas where help is especially welcome:**
- 📱 Android UI/UX improvements
- 🔧 ESP32 firmware optimization (lower power, higher throughput)
- 🤖 ML: transfer learning across different router hardware
- 🧪 Data collection in diverse environments

---

## Privacy Policy

WaveGuard is designed privacy-first:

- 🚫 **No camera or microphone** access is ever requested
- 🚫 **No cloud uploads** — all inference runs on the local device
- 🚫 **No personal data collection** — the app does not collect names, identifiers, or behavioral profiles
- ✅ Raw CSI/RSSI data stays on your device and is only stored when you explicitly enable session logging
- ✅ You can delete all stored data from **Settings → Clear Local Data**

---

## License

```
MIT License

Copyright (c) 2025 ashishshroti14

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

<p align="center">
  Made with ☕ and radio waves · <a href="https://github.com/ashishshroti14/waveguard">github.com/ashishshroti14/waveguard</a>
</p>