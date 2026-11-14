# Voice Prank Pro — build & usage guide

The app runs on Android 8.0 (API 26)+. Audio injection needs **root or Shizuku on every version** (no unprivileged way exists); on Android 8/9 Shizuku must be started from a PC over adb after each reboot (no wireless debugging before Android 11). Built with Kotlin + Android Gradle Plugin 8.1.4.

---

## 1. Open in Android Studio

1. **File → Open…** and select the `VOICE_PRANK_APP` folder.
2. Android Studio will detect the Gradle project and use the bundled wrapper
   (`gradle/wrapper/gradle-wrapper.properties` → Gradle 8.7).
3. Wait for the Gradle sync to finish, then press **Run ▶** on the
   `app` configuration.

Requirements:

| | version |
|---|---|
| Android Studio | Hedgehog (2023.1) or newer |
| Gradle | 8.7 (downloaded automatically by the wrapper) |
| JDK | 17 (Android Studio's bundled JBR 17 works) |
| Android SDK | compileSdk **34** (Android 14) |

If the build complains about the SDK location, create
`local.properties` next to `settings.gradle.kts`:

```properties
sdk.dir=/path/to/Android/Sdk
```

---

## 2. Release build (signed APK)

Release builds are minified and are signed only when `keystore.properties` exists
(git-ignored, next to `settings.gradle.kts`):

```properties
storeFile=my-release.jks
storePassword=...
keyAlias=mykey
keyPassword=...
```

Create a keystore:

```bash
keytool -genkey -v -keystore my-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias mykey
```

Build:

```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

---

## 3. How the app works

The app is a **two-part system**, exactly like the reference app it was
modelled on:

### A. Voice clone / TTS

* You register a **voice card**: a name, a Cartesia **Voice ID**, a sample
  audio and an optional cover image.
* Text is turned into speech with **Cartesia TTS**.
  * **Own API mode** — paste your own key from `play.cartesia.ai/keys` and
    generation happens straight from the phone. Your key never touches our
    server.
  * **Server mode** — if you run your own backend, point the app at it
    (`Settings → Backend base URL`).
* Generated audio is normalised to **16-bit mono PCM WAV** and cached.

### B. Injection into the game

* The app registers an Android **audio loop-back mix**
  (`AudioPolicy` / `AudioMix` / `AudioMixingRule` — hidden APIs) and plays
  the generated audio into it. Everything played is captured by other apps
  that open the microphone, which is how the voice reaches the game's voice
  chat.
* Two privilege sources:
  * **Root** — `su -c "CLASSPATH=<apk> nohup app_process /system/bin com.voiceprank.app.inject.Injector …"`
  * **Shizuku** — the same command, executed by a *user service* that
    Shizuku starts with the shell uid (no root needed).

---

## 4. Permissions and setup

| What | Where |
|---|---|
| **Overlay** (floating bubble over the game) | Settings → *Grant overlay permission* |
| **Shizuku** | Install Shizuku, pair, "Start via Wireless debugging", then Settings → *Check Shizuku / permission* → Allow |
| **Notifications** (foreground service) | Android 13+ asks on first launch |

Order of operations for injection:

1. Install and start **Shizuku** (or root your device).
2. Grant the **overlay** permission.
3. Open the game, then open the app and press **Start injection**.

---

## 5. Project layout

```
app/src/main/java/com/voiceprank/app/
├── App.kt                     Application
├── core/
│   ├── Gate.kt                 licence/entitlement state machine
│   ├── Identity.kt             device id + signing-cert SHA-256
│   ├── Prefs.kt                persisted state
│   └── Premium.kt              tiers, feature limits, licence parsing
├── data/
│   ├── IsFile.kt               "IS File" bundle export/import
│   ├── VoiceCard.kt            voice card model
│   └── VoiceStore.kt           local card library
├── inject/
│   ├── AudioPolicyFactory.kt   hidden-API bridge (reflection)
│   ├── InjectCtl.kt            start/stop/status of the injector
│   ├── Injector.kt             the injector itself (app_process entry)
│   ├── RootTools.kt            root detection
│   ├── ShellRunner.kt          command construction
│   ├── ShellService.kt         Shizuku user service (shell uid)
│   └── ShizukuBridge.kt        Shizuku binding
├── net/
│   ├── ApiClient.kt            backend transport + headers
│   └── CartesiaClient.kt       direct Cartesia TTS (own API mode)
├── service/
│   └── OverlayService.kt       floating bubble + panel
├── tts/
│   ├── PcmConverter.kt         WAV <-> PCM
│   └── TtsEngine.kt            generation + caching
└── ui/
    ├── LoginActivity.kt        the gate screen
    ├── MainActivity.kt         voice library
    ├── VoiceEditActivity.kt    add/edit voice, generate, inject, export
    ├── SettingsActivity.kt      own API mode, backend, Shizuku
    └── PremiumActivity.kt      premium / coupons / licence
```

---

## 5b. Licence keys (offline)

Keys are RSA-signed. The private key stays with you; the app only holds the public key.

```bash
python3 tools/make_license.py gen-keys --out license_private.pem   # once; paste output into Premium.LICENCE_PUBLIC_KEY
python3 tools/make_license.py issue --key license_private.pem --days 365 --device <device-id>
```

Without `LICENCE_PUBLIC_KEY` the app refuses every offline key. The 3-day trial is stored
on the device and can be reset by clearing app data; only a server-side check can prevent that.

---

## 6. Notes

* `app/build.gradle.kts` uses `minSdk = 26` so it runs on Android 8.0+.
* Shizuku mode uses an AIDL `UserService` (`IShell` / `ShellService`) that is **not** in the
  manifest, so other apps cannot bind to it. The injector reads its audio from
  `Android/data/<pkg>/files/inject/` (the shell uid cannot read the app's private dir).
* The injector needs the hidden `audiopolicy` API, which is only reachable
  from a process with shell/root privileges — that is why it is started
  through `app_process` and not from the app process itself.
* Voice bundles are capped at 200 cards / 100 MB and are validated for path
  traversal and zip bombs on import.
* If you publish this, follow the target game's terms of service; using a
  voice changer in competitive voice chat can get an account banned.
