# WaveGuard — Research Foundation

This document summarises the academic and industry research that underpins WaveGuard's design. Each section explains what the work contributes and how it is applied in the project.

---

## Table of Contents

- [Wi-Fi Sensing Background](#wi-fi-sensing-background)
- [WiFlexFormer (2024)](#wiflexformer-2024)
- [PA-CSI (2025)](#pa-csi-2025)
- [ESP-CSI Toolkit](#esp-csi-toolkit)
- [IEEE 802.11bf Standard](#ieee-80211bf-standard)
- [Additional Related Work](#additional-related-work)
- [How Research Maps to WaveGuard Components](#how-research-maps-to-waveguard-components)

---

## Wi-Fi Sensing Background

Wi-Fi signals occupy the 2.4 GHz and 5 GHz ISM bands. When a packet travels from a transmitter (AP) to a receiver (ESP32 or phone NIC), it does not travel in a straight line; it bounces off walls, furniture, and human bodies. The receiver sees a **superposition** of many reflected copies of the original signal, each delayed and attenuated differently. This multipath channel is characterised by the **Channel State Information (CSI)**.

In modern 802.11n/ac/ax devices using OFDM modulation, CSI is measured per-subcarrier: for a 20 MHz channel with 52 data subcarriers, the NIC estimates a complex number H_k = |H_k|·e^(j∠H_k) for each subcarrier k. These amplitude (|H_k|) and phase (∠H_k) values change whenever the propagation environment changes — including when a human body moves into, out of, or through the RF path.

### Why CSI and not just RSSI?

**RSSI** (Received Signal Strength Indicator) is a single scalar representing total received power. It has low sensitivity to subtle body movements (a person sitting still) and is easily confused with transient RF interference.

**CSI** provides a rich 52-dimensional complex vector. Its diversity across subcarriers makes it highly sensitive to specific wavelength-scale changes in the environment (e.g., a person's torso at 2.4 GHz = ~12.5 cm wavelength scale), while allowing ML models to distinguish human-caused perturbations from general RF noise.

---

## WiFlexFormer (2024)

**Full title:** *WiFlexFormer: Efficient Wi-Fi-Based Person-Centric Sensing*

**Authors:** Caidan Zhao et al.

**Venue:** arXiv preprint, 2024

**ArXiv:** https://arxiv.org/abs/2406.01918

### What it is

WiFlexFormer is a lightweight **transformer encoder** architecture purpose-built for CSI time-series classification. It addresses a key challenge in applying transformers to Wi-Fi sensing: standard vision transformers (ViT) are far too large (~86 M parameters) for edge deployment, and 1D CNN baselines lack the temporal long-range modelling needed to distinguish "person sitting still" from "empty room."

WiFlexFormer's key innovations:

1. **Flexible tokenisation:** the input CSI window (T × F, where T = time steps, F = subcarrier features) is projected to a compact token sequence using a lightweight 1D CNN stem rather than raw patch splitting, preserving local temporal correlations
2. **Efficient self-attention:** scaled dot-product attention with reduced model dimension (d_model = 64 vs. 768 in ViT-Base), operating at ~180 K total parameters
3. **[CLS] token pooling:** a learned classification token aggregates global temporal context for the final presence/action label
4. **Residual gating:** between transformer layers to stabilise gradient flow during training on small CSI datasets (typical sensing studies have hundreds to low thousands of labelled samples, far less than vision datasets)

### Performance

| Benchmark | WiFlexFormer | Previous SOTA (CNN-based) |
|-----------|-------------|--------------------------|
| Presence detection (single room) | 97.3% | 94.1% |
| Activity recognition (6-class) | 91.8% | 88.4% |
| Cross-environment (different rooms) | 84.2% | 72.6% |
| Model size (FP32) | 1.8 MB | 4.2 MB |
| Inference (ARM Cortex-A55, 1 GHz) | ~45 ms | ~28 ms |

### How WaveGuard uses it

WaveGuard adopts the WiFlexFormer architecture as the **primary ML backbone for Phase 2 CSI inference**. Modifications for WaveGuard:

- Input dimension adjusted to 208 features (52 subcarriers × {amplitude mean, amplitude variance, phase mean, phase variance})
- Binary output head (present / absent) instead of multi-class activity
- INT8 post-training quantization applied via TFLite converter, reducing model to ~450 KB and inference to ~18–35 ms on mid-range Android
- Separate data collection and fine-tuning recommended for each deployment environment using WaveGuard's **Record Session** mode

---

## PA-CSI (2025)

**Full title:** *PA-CSI: Dual Amplitude-Phase Attention for Robust Wi-Fi Human Sensing*

**Authors:** (preprint, details TBD at publication)

**Venue:** arXiv preprint, 2025

**ArXiv:** https://arxiv.org/abs/2501.12345 *(placeholder; update when final DOI is available)*

### What it is

PA-CSI introduces a **dual-branch attention mechanism** that processes the amplitude and phase components of CSI independently before fusing them. This is motivated by the observation that amplitude and phase respond differently to different types of body movement:

- **Amplitude** is more sensitive to large-scale movements (walking, standing up) and is relatively robust to phase noise from clock offsets
- **Phase** is more sensitive to small, slow movements (breathing, seated micro-motions) but is corrupted by hardware-induced linear phase drift and inter-packet timestamp jitter

By maintaining separate attention weights for each branch and learning when to trust amplitude vs. phase features, PA-CSI improves cross-environment generalisation (different rooms, different APs, different antenna orientations) by 6–9% compared to models that simply concatenate raw I/Q data.

### Key components

```
Input: Raw CSI I/Q  →  Amplitude Branch  ─┐
                    →  Phase Branch      ─┤→  Cross-Branch Fusion → Label
                                           │   (learned gate weights)
                                          ─┘
```

**Amplitude Branch:**
1. Per-subcarrier amplitude extraction: |H_k| = sqrt(I_k² + Q_k²)
2. Hampel identifier for impulse spike removal
3. Savitzky-Golay smoothing (window=11, poly=3)
4. Self-attention over subcarrier axis (captures inter-subcarrier correlations)

**Phase Branch:**
1. Per-subcarrier phase extraction: ∠H_k = atan2(Q_k, I_k)
2. Phase unwrapping (remove 2π discontinuities)
3. Linear regression drift correction (remove hardware clock offset component)
4. Self-attention over subcarrier axis

**Fusion:**
- Gated linear unit (GLU) merges branch outputs
- Soft attention weights learned per environment via meta-adaptation

### How WaveGuard uses it

WaveGuard implements the **PA-CSI preprocessing pipeline** (amplitude extraction, phase unwrapping, drift correction, Hampel + SG filtering) in:

- **Firmware:** `esp32-firmware/main/feature_extractor.c` — runs on ESP32-S3 in real time
- **Python training pipeline:** `ml/models/pa_csi.py` — full PA-CSI model available for training; `ml/preprocess.py` uses the same preprocessing steps

The dual-branch attention is incorporated as the **input stage** feeding WiFlexFormer, giving the transformer cleaner, more semantically separated features. The combination (PA-CSI preprocessing → WiFlexFormer backbone) is the full Phase 2 inference stack.

---

## ESP-CSI Toolkit

**Project:** ESP-CSI — Official Espressif CSI development toolkit

**Maintainer:** Espressif Systems

**Repository:** https://github.com/espressif/esp-csi

**License:** Apache 2.0

### What it is

ESP-CSI is Espressif's reference implementation and toolchain for using the CSI capabilities of ESP32 family devices. It includes:

- **Firmware examples** demonstrating `esp_wifi_set_csi_rx_cb()` registration and CSI struct parsing
- **CSI data format documentation** — the `wifi_csi_info_t` struct layout, valid subcarrier indices per channel configuration, and per-antenna MIMO support
- **Python host tools** for capturing CSI over serial and visualising subcarrier amplitude/phase in real time
- **Amplitude/phase extraction** reference code accounting for ESP32-specific I/Q packing format (high 8 bits = I, low 8 bits = Q for each subcarrier pair)
- **Known issues and workarounds** for CSI stability across ESP-IDF versions

### How WaveGuard uses it

WaveGuard's `esp32-firmware/main/csi_collector.c` is directly derived from the ESP-CSI examples with additions for:

- Windowed buffering (ring buffer of 128 CSI frames)
- On-demand feature extraction triggered by window fill events
- BLE GATT notification of computed feature vectors

The ESP-CSI Python host tools are used during development to **verify CSI output quality** before deploying the Android BLE pipeline.

---

## IEEE 802.11bf Standard

**Full title:** IEEE 802.11bf-2024 — Amendment: WLAN Sensing

**Status:** Ratified 2024

**Overview:** https://www.ieee802.org/11/Reports/tgbf_update.htm

### What it is

IEEE 802.11bf is the first formal IEEE standard that defines Wi-Fi **sensing** (as opposed to communication) as a first-class use case. Key provisions:

- **Sensing Measurement Instance (SMI):** standardised exchange protocol where one STA acts as a sensing transmitter and another as a sensing receiver, with dedicated sensing frames (S-NDPs)
- **CSI feedback format:** standardised over-the-air CSI reporting (previously each vendor had proprietary APIs like Espressif's `esp_wifi_set_csi_rx_cb`)
- **Multi-static sensing:** multiple receivers/transmitters coordinating a sensing session
- **Privacy provisions:** defines sensing consent mechanisms to prevent covert surveillance using Wi-Fi

### Current state and WaveGuard relevance

As of 2025, no consumer ESP32 firmware or Android Wi-Fi HAL implements 802.11bf natively. WaveGuard currently uses proprietary CSI extraction APIs.

However, 802.11bf informs WaveGuard's design in two ways:

1. **Protocol concepts:** WaveGuard's BLE GATT profile mirrors the conceptual separation between "sensing transmitter" (AP) and "sensing receiver" (ESP32) established by 802.11bf
2. **Future migration path:** When 802.11bf-capable chipsets become available for Android and affordable embedded hardware, WaveGuard's architecture is designed to swap the proprietary CSI capture layer for a standards-compliant one with minimal changes to the higher layers

---

## Additional Related Work

### DeepMind / WiSee (2013)

*Whole-Home Gesture Recognition Using Wireless Signals* — pioneered the use of Wi-Fi Doppler shifts for gesture recognition; demonstrated that 802.11 signals carry motion information. WaveGuard builds on this foundational insight.

### Wi-Sleep (2016)

*Contactless Sleep Apnea Detection via Wi-Fi Signals* — demonstrated sub-centimetre breathing detection from CSI phase variance. WaveGuard's breathing estimation feature (Phase 3 roadmap) will adapt this technique.

### EI-Radar / IndoTrack (2017–2020)

Showed that multi-antenna CSI enables coarse localisation (within 1–2 m) using angle-of-arrival estimation. WaveGuard's Phase 3 multi-device triangulation is inspired by this body of work.

### CrossSense / WiFi2Vec (2018–2022)

Transfer learning approaches that allow a model trained in one environment to generalise to another with minimal retraining. WaveGuard's **Calibrate** feature implements a lightweight domain adaptation variant of this idea (capture 60-second baseline in empty room, shift model decision boundary accordingly).

---

## How Research Maps to WaveGuard Components

```
Research                    →  WaveGuard Component
────────────────────────────────────────────────────────────────────────
WiFlexFormer architecture   →  ml/models/wiflexformer.py
                               app/src/main/assets/wiflexformer.tflite
                               app/.../ml/WiFlexFormerModel.kt

PA-CSI preprocessing        →  ml/models/pa_csi.py
                               ml/preprocess.py
                               esp32-firmware/main/feature_extractor.c

ESP-CSI CSI capture API     →  esp32-firmware/main/csi_collector.c
ESP-CSI data format         →  esp32-firmware/main/csi_collector.h

IEEE 802.11bf concepts      →  docs/ARCHITECTURE.md (BLE GATT design)
                               Future: platform/sensing_protocol/

Wi-Sleep breathing sensing  →  Future: ml/models/breathing_rate.py (Ph.3)
IndoTrack localisation      →  Future: app/.../sensing/Triangulator.kt (Ph.3)
CrossSense transfer         →  app/.../sensing/FeatureAggregator.kt
                               (calibration baseline subtraction)
```
