# TV Voice Remote

An Android TV voice remote prototype. It listens locally with Vosk after the user starts it, and sends best-effort media commands to the active media session.

## Build APK

Open this folder in Android Studio, or run:

```bash
gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Build updateable APKs with GitHub Actions

Android only accepts an in-place update when the new APK has the same package ID and is signed with the same private key as the installed app. This project keeps the package ID stable (`com.codex.tvremote`) and uses the GitHub run number as the increasing Android version code.

Create a release key **once** on your PC and keep a private backup:

```bash
keytool -genkeypair -v -keystore tv-voice-release.jks -alias tv-voice -keyalg RSA -keysize 2048 -validity 10000
```

Convert the keystore to Base64 and save it, along with the passwords, in GitHub repository **Settings → Secrets and variables → Actions** using these names:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` (for the command above: `tv-voice`)
- `ANDROID_KEY_PASSWORD`

PowerShell Base64 command: `[Convert]::ToBase64String([IO.File]::ReadAllBytes('tv-voice-release.jks'))`

Do not commit or lose the keystore. Every APK update must use this same key. Each workflow run builds `app-release.apk`; install it over the existing app to update it and preserve app data. The model backup to shared storage is for uninstall/reinstall; ordinary in-place updates retain app data and the downloaded model.

## First use

1. Install and open **TV Voice Remote**.
2. Select **Enable media access** and enable its Notification access in Android settings.
3. Select **Start voice control** and grant microphone access. The persistent notification shows when the microphone is active.
4. The app downloads the Arabic Vosk model on first start (about 158 MB). The screen and the notification show the progress (percent and MB). If the connection drops, it retries up to 5 times and resumes from where it stopped; pressing **Start voice control** again also resumes. Recognition is then local and audio is not uploaded.
5. To keep the model when uninstalling, choose **Back up model to shared storage / USB** and select a shared folder (a USB drive is recommended). After reinstalling, choose **Restore model from shared storage / USB** and select that same folder. Android removes the saved folder permission on uninstall, so the folder must be selected again; the model files themselves remain outside the app and are not downloaded again.

### Import the model with ADB (TVs without a file picker)

If **Restore** says no app supports it, put the model in the app's own folder and use **Import model from ADB folder**:

```bash
adb shell mkdir -p /sdcard/Android/data/com.codex.tvremote/files
adb push vosk-model /sdcard/Android/data/com.codex.tvremote/files/
```

`vosk-model` is the unzipped model folder (it must contain `am/final.mdl`); the original name `vosk-model-small-ar-tn-0.1-linto` also works. After the import, delete the pushed copy to free space.

## Supported phrases

`تشغيل`, `إيقاف`, `التالي`, `السابق`, `تقديم دقيقة`, `إرجاع دقيقة`, `ارفع الصوت`, `اخفض الصوت`, `كتم`, `ألغ الكتم`.

The wake phrase `تي في` is optional, for example `تي في، التالي`.

## Limits

Playback buttons and seeking depend on what the current app exposes through Android's active media session. Some apps do not expose previous/next/seek. TV manufacturers can also block app-controlled volume changes. YouTube and other apps must be checked on the target TV. The available small Arabic Vosk model is Tunisian Arabic; simple formal Arabic commands may work but need testing on the actual TV and its microphone.

The model is distributed by Vosk under Apache 2.0: https://alphacephei.com/vosk/models
