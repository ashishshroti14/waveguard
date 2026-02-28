# Testing WaveGuard — Getting & Installing the APK

## Quickest way — Download from GitHub Actions

Every push to this repository automatically builds a debug APK via GitHub Actions.

### Step 1 — Get the APK

1. Open the repository on GitHub.
2. Click the **Actions** tab at the top.
3. Select the **"Build Debug APK"** workflow from the left sidebar.
4. Click the latest successful run (green ✅).
5. Scroll to the bottom of the run page — you'll see an **Artifacts** section.
6. Click **`waveguard-debug-<N>`** to download a ZIP file containing `app-debug.apk`.
7. Unzip to get `app-debug.apk`.

> **Tip:** You can also trigger a fresh build manually: go to Actions → Build Debug APK → **Run workflow**.

---

## Step 2 — Install on an Android device (sideload)

### Requirements
- Android 11 or higher (API 31+)
- "Install unknown apps" permission enabled for your file manager / browser

### Enable installation from unknown sources

**Android 12+:**
1. Settings → Apps → Special app access → Install unknown apps.
2. Find your file manager or browser and toggle **Allow from this source** ON.

**Android 11:**
1. When you tap the APK, Android will prompt you to enable the setting — tap **Settings** and allow it.

### Install steps
1. Transfer `app-debug.apk` to your phone (USB, email, Google Drive, etc.).
2. Tap the APK file in your file manager.
3. Tap **Install** when prompted.
4. Launch **WaveGuard** from your app drawer.

---

## Step 3 — Test Phase 1 (Phone-only RSSI mode — no hardware needed)

Phase 1 works on any Android phone with Wi-Fi. No ESP32 is required.

1. Open WaveGuard.
2. On the **Setup** screen, tap **"Use Phone-Only Mode"** (skip ESP32 pairing).
3. Grant the requested permissions:
   - **Location** (required by Android for Wi-Fi scanning)
   - **Nearby devices** / Bluetooth (can be denied for Phase 1)
   - **Notifications** (for fall/presence alerts)
4. Tap **Start Calibration** on the Calibration screen.
   - Leave the room (or stay very still) for 60 seconds while the baseline is recorded.
   - A progress bar and countdown are shown.
5. After calibration completes, tap **Go to Dashboard**.
6. The Dashboard shows:
   - **Presence Indicator** — pulsing circle (green = empty, yellow = presence, red = alert)
   - **Live RSSI Graph** — real-time signal strength from nearby Wi-Fi APs
   - **Activity timeline**
7. Walk into the monitored area — the indicator should turn yellow (Presence Detected).
8. Check **History** screen for logged alerts.

### What to expect in Phase 1

| Scenario | Expected Result |
|---|---|
| Empty room, no movement | 🟢 Green — "Empty" |
| Person walks into room | 🟡 Yellow — "Presence Detected" |
| Sudden movement / fall simulation | 🔴 Red — "Fall Detected" alert |
| Unusual activity for time of day | 🟠 Amber — "Unusual Activity" notification |

> **Note:** Results depend on your environment. More Wi-Fi APs nearby = better signal variance = better detection accuracy.

---

## Step 4 — Test Phase 2 (ESP32 CSI mode — requires hardware)

See [docs/HARDWARE_SETUP.md](docs/HARDWARE_SETUP.md) for the full ESP32 flashing and pairing guide.

Short summary:
1. Flash the firmware in `esp32/` to an ESP32-S3 board using ESP-IDF.
2. Power the ESP32 and place it in the room.
3. Open WaveGuard → Setup → **Scan for ESP32**.
4. Select **WaveGuard-ESP32** from the list and tap **Connect**.
5. Once connected, BLE CSI streaming begins automatically.
6. The app switches to the WiFlexFormer ML inference pipeline for higher-accuracy detection.

---

## Building from source

If you want to build the APK yourself locally:

### Prerequisites
- [Android Studio Hedgehog or newer](https://developer.android.com/studio)  
  *or* JDK 17 + Gradle 8.6 on the command line

### Android Studio
1. Open Android Studio → **Open** → select the `android/` folder.
2. Let Gradle sync finish.
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. The APK is output to `android/app/build/outputs/apk/debug/app-debug.apk`.

### Command line
```bash
cd android
# macOS/Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```
Output: `android/app/build/outputs/apk/debug/app-debug.apk`

> **Note:** `gradlew` requires `gradle/wrapper/gradle-wrapper.jar` which is generated when you run `gradle wrapper` inside the `android/` directory. If you have Android Studio, it provides this automatically.

---

## Running the ML pipeline (Python)

```bash
cd ml
pip install -r requirements.txt

# Explore with the notebook
jupyter notebook notebooks/exploration.ipynb

# Train WiFlexFormer (requires a CSI dataset — see ml/preprocessing/data_loader.py)
python training/train.py --dataset ut-har --epochs 50

# Export to TFLite for Android
python export/export_tflite.py --checkpoint checkpoints/best.pt --output wiflexformer.tflite
```

Copy the exported `wiflexformer.tflite` to `android/app/src/main/assets/` and rebuild the APK to enable Phase 2 ML inference.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "Install blocked" | Enable "Install unknown apps" for your file manager — see Step 2 above |
| Wi-Fi permission denied | Grant **Location** permission — Android requires it for Wi-Fi scanning |
| Presence not detected | Ensure there are ≥3 nearby Wi-Fi APs; recalibrate in a truly empty room |
| App crashes on launch | Check that you're on Android 11+ (API 31); the debug APK is not optimised |
| BLE device not found | Make sure the ESP32 is powered and within ~10 m; grant Nearby Devices permission |
| `./gradlew: Permission denied` | Run `chmod +x android/gradlew` first |
