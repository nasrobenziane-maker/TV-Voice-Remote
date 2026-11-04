# TV Voice Remote

An Android TV command receiver paired with a phone app. Speech recognition and microphone use happen on the phone. The TV receives commands over the local Wi-Fi and sends playback actions to the active media session. The TV app has no microphone permission; the regular TV remote continues to work.

## Build APKs

Open this folder in Android Studio, or run `gradlew.bat assembleDebug` on Windows / `bash ./gradlew assembleDebug` on Linux or macOS.

The repository contains two Android apps: `app` (TV) and `phone` (phone). The phone needs a Vosk model, downloadable once from the app or importable from a ZIP/folder. Speech is recognized locally after installation.

## Build updateable APKs with GitHub Actions

Android only accepts an in-place update when the new APK has the same package ID and is signed with the same private key as the installed app. Create a release key once on your PC and keep a private backup:

```bash
keytool -genkeypair -v -keystore tv-voice-release.jks -alias tv-voice -keyalg RSA -keysize 2048 -validity 10000
```

Convert the keystore to Base64 and save it, along with the passwords, in GitHub repository **Settings → Secrets and variables → Actions** using these names:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` (for the command above: `tv-voice`)
- `ANDROID_KEY_PASSWORD`

PowerShell Base64 command: `[Convert]::ToBase64String([IO.File]::ReadAllBytes('tv-voice-release.jks'))`

Do not commit or lose the keystore. Every APK update must use this same key. The workflow builds both APKs and uploads them as artifacts. On the first TV app launch after updating from an older version, it deletes only its old local speech-model folders. It keeps pairing details, permissions, and other app data.

## First use

1. Install and open **TV Voice Remote** on the TV.
2. Select **Enable media access** and enable its Notification access in Android settings.
3. Keep the phone and TV on the same Wi-Fi network. The TV screen displays its IP address and pairing PIN.
4. Install and open **TV Voice Phone** on the phone. Find the TV automatically or enter its address, enter the PIN, save, then test the connection.
5. Download or import the phone's speech model once. Choose push-to-talk or always listening and speak.

The phone app has **فتح إعداد استثناء البطارية**. On phones that restrict background apps, allow the app to ignore battery optimization so always-listening is less likely to be stopped. Push-to-talk remains available.

## Commands and editable phrases

The phone starts with Algerian Arabic alternatives. Edit them in the phone app under **عبارات الأوامر القابلة للتعديل**. Use one line per command in the form `command=phrase | alternative`, for example `play=شغل | كمل`. Keep the command IDs on the left unchanged. Supported IDs are `play`, `pause`, `next`, `previous`, `forward`, `back`, `volume_set`, `volume_up`, `volume_down`, `mute`, `unmute`, and `open_youtube`. Save after editing. The optional wake phrase `تي في` is editable separately.

Say **الصوت 10** to set the TV volume to 10%, or **زيد الصوت 10** / **نقص الصوت 10** to change it by 10 percentage points. Values from 0 to 100 are supported. The phone's seek slider sets the forward/backward jump from 5 to 60 seconds (10 seconds by default). Volume control may be restricted by some TV manufacturers; speech-number recognition depends on the installed model's vocabulary.

The app supports push-to-talk and always listening. Always listening uses a foreground service and notification. The wake word is required for commands in that mode; after hearing only the wake word, the command may follow within five seconds.

## Phone link and YouTube

The TV app starts a receiver (`PhoneLinkService`, TCP port 8765) when opened and when the TV boots. Commands use the local network; after five wrong PIN attempts from one address, the TV refuses it for 60 seconds. The link is not encrypted, so use it on a trusted private network.

`افتح يوتيوب` opens the YouTube app on the TV. If Android prevents opening it from the background, allow **Display over other apps** for TV Voice Remote. Some TVs may require granting `SYSTEM_ALERT_WINDOW` through ADB.

## Limits

Playback buttons and seeking depend on the active media app exposing controls through Android's media session. Seeking uses the current position adjusted for elapsed playback time. Some apps do not expose previous/next/seek. TV manufacturers can also block app-controlled volume changes. The small Arabic Vosk model is Tunisian Arabic; editing phrases changes the allowed grammar but cannot add words missing from its vocabulary. Check recognition and playback on the target devices.

The speech model is distributed by Vosk under Apache 2.0: https://alphacephei.com/vosk/models
