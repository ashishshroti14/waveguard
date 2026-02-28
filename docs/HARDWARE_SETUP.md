# WaveGuard — ESP32-S3 Hardware Setup Guide

## Table of Contents

- [Hardware Requirements](#hardware-requirements)
- [Development Environment Setup](#development-environment-setup)
- [Wiring and Placement](#wiring-and-placement)
- [Firmware Configuration](#firmware-configuration)
- [Flashing the Firmware](#flashing-the-firmware)
- [BLE Pairing with the Android App](#ble-pairing-with-the-android-app)
- [LED Status Codes](#led-status-codes)
- [Optimal Sensor Placement](#optimal-sensor-placement)
- [Multi-Device Setup (Phase 3)](#multi-device-setup-phase-3)
- [Troubleshooting](#troubleshooting)
- [Advanced: Building a Permanent Enclosure](#advanced-building-a-permanent-enclosure)

---

## Hardware Requirements

### Required

| Component | Specification | Recommended Part | Approx. Cost |
|-----------|---------------|-----------------|-------------|
| Microcontroller | ESP32-S3, 2.4 GHz Wi-Fi, BLE 5.0 | ESP32-S3-DevKitC-1 (N8R8) | $8–12 USD |
| USB cable | USB-C, data-capable (not charge-only) | Any quality USB-C cable | $3–5 USD |
| Computer | macOS, Linux, or Windows 10/11 | — | — |

### Optional

| Component | Purpose | Approx. Cost |
|-----------|---------|-------------|
| 5V/1A USB power adapter | Permanent deployment (no PC needed) | $5 USD |
| Enclosure | Protection and aesthetics | $3–8 USD |
| LED indicator (external) | Visible status when in enclosure | $0.50 USD |
| 10 cm USB-C extension cable | Easier port access in enclosures | $3 USD |

### Why ESP32-S3 specifically?

The ESP32-**S3** is required (not ESP32, ESP32-C3, or ESP32-S2) because:

- Its Wi-Fi driver exposes `esp_wifi_set_csi_rx_cb()` with **stable, documented** CSI output
- It has **512 KB SRAM** (expandable with PSRAM) — sufficient to buffer 128-sample CSI windows
- Its **hardware FPU** handles amplitude/phase computation without excessive CPU load
- It supports **BLE 5.0** for higher-throughput GATT notify (needed for feature vector streaming)

---

## Development Environment Setup

### 1. Install ESP-IDF v5.2 or later

**macOS / Linux:**

```bash
# Install prerequisites
# macOS:
brew install cmake ninja dfu-util python3

# Ubuntu/Debian:
sudo apt-get install git cmake ninja-build python3 python3-pip \
     python3-venv libusb-1.0-0 libssl-dev

# Clone ESP-IDF
mkdir -p ~/esp
cd ~/esp
git clone --recursive https://github.com/espressif/esp-idf.git
cd esp-idf
git checkout v5.2.1   # or latest stable tag

# Run the install script
./install.sh esp32s3

# Add to shell profile (add to ~/.zshrc or ~/.bashrc)
echo 'source ~/esp/esp-idf/export.sh' >> ~/.zshrc
source ~/.zshrc
```

**Windows:**

Download and run the [ESP-IDF Windows Installer](https://dl.espressif.com/dl/esp-idf/) (choose ESP-IDF v5.2+). This installs IDF Tools, Python, and drivers automatically.

### 2. Verify installation

```bash
idf.py --version
# Expected output: ESP-IDF v5.2.x
```

### 3. Install USB-to-Serial driver (Windows only)

The ESP32-S3-DevKitC-1 uses a **CP2102N** USB bridge. Download the driver from:
[https://www.silabs.com/developers/usb-to-uart-bridge-vcp-drivers](https://www.silabs.com/developers/usb-to-uart-bridge-vcp-drivers)

On macOS and Linux, no additional driver is needed.

---

## Wiring and Placement

The ESP32-S3-DevKitC-1 requires **no additional wiring** for WaveGuard. Simply plug it into USB power.

### Pin Reference (informational)

| GPIO | Function | WaveGuard Use |
|------|----------|--------------|
| GPIO 38 | RGB LED (WS2812) | Status indicator |
| GPIO 0 | BOOT button | Firmware flash mode |
| GPIO 3 | EN/RESET | Reset button |
| GPIO 19/20 | USB D-/D+ | Flash + serial monitor |

The onboard antenna (PCB trace antenna) is used for both the Wi-Fi CSI receiver and the BLE data link. No external antenna is connected in the standard configuration.

---

## Firmware Configuration

### 1. Clone the repository (if not already done)

```bash
git clone https://github.com/ashishshroti14/waveguard.git
cd waveguard/esp32-firmware
```

### 2. Open menuconfig

```bash
idf.py set-target esp32s3
idf.py menuconfig
```

Navigate to **WaveGuard Configuration** and set:

| Option | Description | Default |
|--------|-------------|---------|
| `WAVEGUARD_WIFI_SSID` | SSID of the AP to monitor against | `"MyHomeWifi"` |
| `WAVEGUARD_WIFI_PASSWORD` | AP password (used only to associate; needed for CSI from AP) | `""` |
| `WAVEGUARD_WIFI_CHANNEL` | Wi-Fi channel (1–13 for 2.4 GHz) | `6` |
| `WAVEGUARD_CSI_BUFFER_SIZE` | Window size in packets | `128` |
| `WAVEGUARD_BLE_DEVICE_NAME` | BLE advertised name prefix | `"WaveGuard"` |
| `WAVEGUARD_MONITOR_MODE` | Use monitor mode (true) or station mode | `false` |

> **Note:** For best CSI quality, use **station mode** (connect to your AP). Monitor mode captures more packets but may have less stable CSI in busy RF environments.

Save and exit menuconfig (press `S` then `Q`).

### 3. Review sdkconfig

The following Wi-Fi and BLE settings are pre-configured in `sdkconfig.defaults`:

```
CONFIG_ESP32S3_DEFAULT_CPU_FREQ_240=y
CONFIG_ESP_WIFI_CSI_ENABLED=y
CONFIG_ESP_WIFI_STATIC_RX_BUFFER_NUM=16
CONFIG_ESP_WIFI_DYNAMIC_RX_BUFFER_NUM=64
CONFIG_BT_ENABLED=y
CONFIG_BT_BLE_ENABLED=y
CONFIG_BTDM_CTRL_MODE_BLE_ONLY=y
```

Do not change these unless you know what you are doing.

---

## Flashing the Firmware

### 1. Enter bootloader mode

- Hold the **BOOT** button on the board
- Press and release **EN/RESET** while holding BOOT
- Release **BOOT**

The board is now in download mode (the RGB LED will be off).

Alternatively, `idf.py flash` handles this automatically on most systems.

### 2. Identify the serial port

**macOS:**
```bash
ls /dev/cu.usbserial-*
# Typically: /dev/cu.usbserial-0001 or /dev/cu.SLAB_USBtoUART
```

**Linux:**
```bash
ls /dev/ttyUSB* /dev/ttyACM*
# Typically: /dev/ttyUSB0
```

**Windows:**
Check Device Manager under "Ports (COM & LPT)" — typically `COM3` or `COM4`.

### 3. Build and flash

```bash
# From waveguard/esp32-firmware/
idf.py build flash monitor -p /dev/cu.usbserial-0001
# Replace port with your actual port
```

Expected output during flashing:

```
esptool.py v4.x
Serial port /dev/cu.usbserial-0001
Connecting....
Chip is ESP32-S3 (revision v0.2)
Features: WiFi, BLE
...
Wrote 1234567 bytes at 0x00010000 in 12.3 seconds
Hash of data verified.
Leaving...
Hard resetting via RTS pin...
```

### 4. Verify operation via serial monitor

After flashing, the serial monitor (`idf.py monitor`) should show:

```
I (1234) WAVEGUARD: WaveGuard v0.2.0 starting...
I (1456) WAVEGUARD: Wi-Fi initialized, connecting to 'MyHomeWifi'...
I (2891) WAVEGUARD: Wi-Fi connected. IP: 192.168.1.105
I (2892) WAVEGUARD: CSI callback registered. Buffer size: 128
I (2895) WAVEGUARD: BLE GATT server started. Advertising as 'WaveGuard-A3F2'
I (2896) WAVEGUARD: Ready. Waiting for Android connection...
```

Note the **device suffix** (e.g., `A3F2`) — you will need it to identify the board in the Android app.

Press `Ctrl+]` to exit the serial monitor.

---

## BLE Pairing with the Android App

### Prerequisites

- Android device with **Bluetooth enabled**
- WaveGuard app installed (see [Quick Start in README](../README.md#quick-start--phone-only-mode-phase-1))
- ESP32-S3 powered and running WaveGuard firmware (LED should be **blinking blue**)

### Pairing Steps

1. Open the **WaveGuard** app on your Android device
2. Tap the **⚙️ Settings** icon (top right of the dashboard)
3. Select **CSI Sensor → Add New Sensor**
4. The app begins scanning for BLE devices — wait up to 10 seconds
5. Your ESP32 appears as **`WaveGuard-XXXX`** (e.g., `WaveGuard-A3F2`)
6. Tap the device name
7. Tap **Connect** on the confirmation dialog
8. The app negotiates GATT services and subscribes to the CSI Feature Vector characteristic
9. The ESP32's LED turns **solid blue** — pairing is complete

### After Pairing

- The device is remembered across app restarts (stored in DataStore)
- Auto-reconnect is attempted when the app launches with Bluetooth enabled
- You can manage paired devices under **Settings → CSI Sensors → Manage**

---

## LED Status Codes

| LED State | Meaning |
|-----------|---------|
| Off | Board not powered or crashed (check serial monitor) |
| Rapid red blink (5 Hz) | Wi-Fi connection failed — check SSID/password in menuconfig |
| Slow white pulse (1 Hz) | Wi-Fi connected, no BLE client connected |
| Slow blue pulse (1 Hz) | Wi-Fi + BLE advertising, waiting for Android to connect |
| **Solid blue** | BLE connected, streaming CSI features to Android |
| Yellow flash (per packet) | CSI data being captured (normal during operation) |
| Solid red | Fatal error — check serial monitor output |

---

## Optimal Sensor Placement

Wi-Fi CSI presence detection works by measuring how a human body disturbs the multipath propagation between the sensor and the access point. Placement significantly affects accuracy.

### Recommended

```
┌──────────────────────────────────────────────────┐
│                       Room                        │
│                                                   │
│  [Router/AP]                       [ESP32-S3]     │
│    ████                               ████        │
│      ↑                                  ↑         │
│   Near wall                         Near wall     │
│   or shelf                          (opposite     │
│                                      or adjacent) │
│                                                   │
│              ↗ propagation paths ↙               │
│           Human body disrupts these               │
└──────────────────────────────────────────────────┘
```

**Best placement rules:**
- Place ESP32 on a **shelf, desk, or wall-mounted bracket** at 1–1.5 m height
- Aim for a **line-of-sight** path between the ESP32 and the Wi-Fi AP
- The detection zone is the area **between** the sensor and the AP
- Keep ESP32 at least **30 cm away from metal surfaces** (interference)
- Avoid placing inside a closed cabinet or drawer

### Avoid

- Directly on the floor (signal path obscured by furniture)
- Inside metal enclosures without antenna cutout
- Right next to the AP (too close; CSI variance is low)
- In a different room from the AP unless through-wall sensing is desired

---

## Multi-Device Setup (Phase 3)

Multiple ESP32-S3 boards can be paired to a single Android app to enable coarse spatial sensing.

1. Flash the same firmware to each board (each board gets a unique BLE name from its MAC address)
2. In the app, go to **Settings → CSI Sensors → Add New Sensor** and pair each board in turn
3. The app assigns each sensor to a named zone (e.g., "Living Room", "Bedroom")
4. The PresenceStateEngine fuses signals from all active sensors weighted by their zone assignment

> **Phase 3 is planned functionality.** Multi-device fusion is not yet implemented in the current codebase.

---

## Troubleshooting

### Board not appearing in BLE scan

1. Confirm the board is powered (LED visible)
2. Confirm firmware is flashed correctly (run `idf.py monitor` and check for errors)
3. On Android, toggle Bluetooth off and back on
4. Ensure **Location Services** are enabled on Android (required for BLE scanning on Android 12+)
5. Check that the BLE device name in firmware matches what you expect (`WaveGuard-XXXX`)

### Wi-Fi connection fails at boot

```
E (5000) WAVEGUARD: Wi-Fi connect timeout. Check SSID and password.
```

- Re-run `idf.py menuconfig`, verify the SSID (case-sensitive) and password
- Ensure the AP is on 2.4 GHz — the ESP32-S3 does not support 5 GHz Wi-Fi
- Reflash after correcting the config

### CSI data looks flat / no variance

- The AP and ESP32 must be on the **same Wi-Fi channel** — configure this in menuconfig
- Move the ESP32 closer to the AP (within 10 m line-of-sight)
- Check that the AP is actively transmitting (connected phone/laptop helps generate traffic)
- If using monitor mode, switch to station mode for more reliable CSI

### App shows "No data received" after BLE connection

- Open serial monitor on ESP32: confirm `BLE client connected` message appears
- Check that Android's battery optimization is not killing the WaveGuard foreground service
  - Go to: **Android Settings → Apps → WaveGuard → Battery → Unrestricted**
- Try reinstalling the app if the GATT characteristic subscription state is corrupt

### Serial monitor shows `heap_caps_malloc failed`

The CSI buffer is too large for available SRAM. Reduce `WAVEGUARD_CSI_BUFFER_SIZE` to `64` in menuconfig.

### Flashing fails with "No serial data received"

- Manually enter bootloader mode (hold BOOT, press RESET, release BOOT) before running `idf.py flash`
- Try a different USB cable (many cables are charge-only)
- On Linux, add your user to the `dialout` group: `sudo usermod -aG dialout $USER` then log out and back in

---

## Advanced: Building a Permanent Enclosure

For permanent room deployment, a simple 3D-printed or off-the-shelf ABS project box works well.

### Minimum enclosure dimensions

| Dimension | Minimum |
|-----------|---------|
| Length | 75 mm |
| Width | 45 mm |
| Height | 20 mm |

### Cutouts needed

| Cutout | Location | Size |
|--------|----------|------|
| USB-C power port | Short side | 10 × 4 mm |
| LED window | Top or front face | 5 × 5 mm |
| Ventilation slots | Both long sides | 3 × 20 mm × 4 slots |

> **Note:** Do not use metal enclosures without an external antenna. The PCB trace antenna requires clear space above the ESP32. If using a metal enclosure, add a U.FL/IPEX connector and route an external 2.4 GHz antenna through the enclosure wall.

### 3D Print Files

Printable STL files for a snap-fit enclosure are planned for Phase 2 delivery and will be published in `hardware/enclosure/`.
