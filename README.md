# PocketDesk for Android

A small full-screen app around the PocketDesk page your PC serves. It remembers your PC's address, keeps the
screen on, hides the system bars, keeps you logged in, and lets Audio start with one tap.

**Honest status:** this source was written without access to the Android SDK, so it has not been compiled
or run on a phone yet. It type-checks and the build config follows the standard layout, but your first build is
the first real compile. If anything errors, paste the message and it will be a quick fix.

## 1. Build the APK (pick one)

### A. GitHub builds it for you (no Android tools needed)
1. Create a free account at github.com, then a **private** repository (private, because the signing key
   password is in the project).
2. On your PC, in this folder:
   ```
   git init -b main
   git add .
   git commit -m "PocketDesk app"
   git remote add origin https://github.com/<your-name>/<repo>.git
   git push -u origin main
   ```
   (GitHub asks you to log in; `sudo pacman -S github-cli` then `gh auth login` is the easy way.)
3. Open the repo's **Actions** tab. The "Build APK" run takes about 3-5 minutes. Click the finished run and
   download **PocketDesk-apk** at the bottom. Unzip it to get `app-release.apk`.
   You can re-run it any time with **Run workflow**.

### B. Android Studio
Install it (`paru -S android-studio`), choose **Open** and select this folder, let it sync, then
**Build > Build APK(s)**. The file is at `app/build/outputs/apk/release/app-release.apk`.

### C. Command line on CachyOS (fiddlier)
Needs JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0, licenses accepted), with `ANDROID_HOME`
set. Then `./gradlew assembleRelease`.

## 2. Install on your phone
Get `app-release.apk` onto the phone (download it from a cloud drive, email it to yourself, or `adb install`).
Tap it. Android asks to allow "install unknown apps" for the app you opened it from; allow that once.
If Play Protect warns about an unknown developer, choose **Install anyway**. It is your own app.

## 3. First launch
Type your PC's Tailscale address (`tailscale ip -4` on the PC, e.g. `100.64.0.5`). The port 8765 is added for you.
The Tailscale app must be on. Press **Back** inside the app for a menu: reload, change address, exit.

## Updating
Build a new APK and install it over the old one. Every build uses the same key, so no uninstall is needed.
The key lives in `app/pocketdesk.keystore` (password `pocketdesk`); make your own with `keytool` and edit
`app/build.gradle` if you want a private one.

## If the keyboard does not appear
The keyboard button focuses a hidden text box in the page. That works in Chrome; Android WebView should do the
same, but it is the first thing to check. If it fails, say so and a native keyboard button can be added.
