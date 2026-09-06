# Codex Meter

A lightweight Codex usage display for the Xiaomi X04G (Android 10, 800×480).

## What it shows

- 5-hour and weekly usage, remaining allowance, and live reset countdowns
- ChatGPT plan and last update time
- Offline state with the last successful reading retained

The Android app connects to ChatGPT directly over Wi-Fi every 60 seconds. Its
independent OAuth session is encrypted with the Android Keystore and stored in
the app-private directory; the APK and repository never contain credentials.

## Build and install the app

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 device_auth.py
adb shell run-as com.gengyixiong.codexmeter sh -c 'cat > files/auth.json' \
  < /tmp/codex-meter-device-auth.json
adb shell am force-stop com.gengyixiong.codexmeter
adb shell monkey -p com.gengyixiong.codexmeter 1
```

Follow the device-login instructions printed by `device_auth.py`. After the app
imports the file it deletes the plaintext copy from its private directory.
Delete `/tmp/codex-meter-device-auth.json` after installation. Select Codex
Meter as the Home app if you want the device to boot directly into the meter.

## Test

```bash
python3 -m unittest -v
```
