# Codex Meter — coding-agent handoff

## Current result

The Xiaomi X04G Android 10 display runs `Codex Meter` and fetches quota data
directly over Wi-Fi from ChatGPT. It no longer calls this computer.

Verified state:

- Device: `X04G`, ADB serial `21065C0VR35518`
- App ID: `com.gengyixiong.codexmeter`
- Network interface used by the device: `wlan0`
- `adb reverse --list` was empty
- The computer-side Bridge service was stopped and disabled
- The old TCP 8787 LAN firewall rule was removed
- The display continued changing its update time after the Bridge was stopped

## Architecture

```text
X04G app --HTTPS--> https://chatgpt.com/backend-api/wham/usage
       \--OAuth refresh--> https://auth.openai.com/oauth/token
```

`MainActivity.kt` performs the HTTPS requests, parses the `rate_limit` windows,
refreshes the access token after HTTP 401, and polls every 60 seconds.

Credentials are imported once from `files/auth.json`, then encrypted with an
Android Keystore AES/GCM key and stored in app-private SharedPreferences. The
plaintext import file is deleted after a successful import. Never commit or
print credentials.

## Important files

- `app/src/main/java/com/gengyixiong/codexmeter/MainActivity.kt` — Android UI,
  direct API client, parser, token refresh, and Keystore storage.
- `app/src/main/AndroidManifest.xml` — HTTPS-only app manifest.
- `device_auth.py` — creates a separate Codex OAuth device session. It prints a
  login URL/code, waits for authorization, and writes a mode-600 temporary JSON
  file without printing tokens.
- `README.md` — user-facing build/install notes.

The previous computer Bridge (`bridge.py`, `codex-meter.service`,
`test_bridge.py`) was intentionally removed because the display is now direct.

## Build and install an update

From this directory:

```bash
python3 device_auth.py --self-test
mise exec -- ./gradlew assembleDebug
mise exec -- adb -s 21065C0VR35518 install -r app/build/outputs/apk/debug/app-debug.apk
```

If the update changes the auth format or the device has lost its login, create a
new independent session. Do not reuse the computer's `~/.codex/auth.json`
refresh token because OAuth refresh tokens may rotate and invalidate the other
client:

```bash
python3 device_auth.py
# Open the printed URL, enter the printed one-time code, and approve access.
mise exec -- adb -s 21065C0VR35518 shell run-as com.gengyixiong.codexmeter mkdir -p files
mise exec -- adb -s 21065C0VR35518 shell run-as com.gengyixiong.codexmeter dd of=files/auth.json < /tmp/codex-meter-device-auth.json
mise exec -- adb -s 21065C0VR35518 shell am force-stop com.gengyixiong.codexmeter
mise exec -- adb -s 21065C0VR35518 shell monkey -p com.gengyixiong.codexmeter 1
```

After the app starts, verify that `files/auth.json` is gone. Delete the local
temporary `/tmp/codex-meter-device-auth.json` after import.

## Verification checklist

```bash
python3 device_auth.py --self-test
mise exec -- ./gradlew assembleDebug
mise exec -- adb -s 21065C0VR35518 shell ip route
mise exec -- adb -s 21065C0VR35518 reverse --list
mise exec -- adb -s 21065C0VR35518 shell pidof com.gengyixiong.codexmeter
systemctl --user is-active codex-meter.service   # expected: inactive/not found
```

The screen should show `DIRECT`, a current `UPDATED HH:MM`, and the 5-hour and
weekly values. To prove independence, stop the computer service (if present),
wait at least one 60-second polling interval, and check that `UPDATED` changes.

## Known limitations / if it stops working

1. `/backend-api/wham/usage` is an undocumented ChatGPT internal endpoint, not
   a stable public API. If its JSON changes, update `parseUsage()` for the new
   `plan_type`, `rate_limit.primary_window`, or `secondary_window` shape.
2. OAuth device login and refresh use OpenAI auth endpoints and the Codex client
   ID. If login returns 4xx/Cloudflare errors, try the device login again from a
   normal browser/network. Never paste tokens into source, logs, or chat.
3. If the screen says `OFFLINE`, first check Wi-Fi, then check that the app has
   encrypted credentials (the app-private `shared_prefs/auth.xml` should exist).
   Re-authorize with `device_auth.py` if the refresh token expired or was
   revoked.
4. The device is 32-bit ARMv7 with little free storage. Do not try to bundle the
   full x86_64 Codex CLI/app-server into the APK; keep the direct HTTPS client.
5. The app uses deprecated Android immersive-UI APIs because the target is
   Android 10. Change them only if the display behavior is intentionally
   retested on the X04G.

## Safe maintenance rules

- Keep credentials outside Git and outside APK resources.
- Prefer the existing Java/Kotlin standard library and Android Keystore; avoid
  adding a dependency for a small HTTP or JSON task.
- Test parser changes with `python3 device_auth.py --self-test` plus a real APK
  install and one live refresh on the X04G.
- If replacing the internal endpoint with an official API, update this document
  and remove the old endpoint-specific parser only after live verification.
