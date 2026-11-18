# Silk Warp

Live wallpaper for Android: a silk photo that sits twisted on the lock screen, warps back into place when you unlock, and slides across the home-screen pages like a normal wallpaper.

- **Unlock morph** — a damped spring (prototype speed 0.5) drives a domain warp of the photo, a liquid flow that peaks mid-transition, and a light sweeping across the folds.
- **Page sliding** — the launcher's wallpaper offset moves a screen-sized crop across the wider image.
- **Battery** — draws continuously only while animating or on the lock screen (slow breathing); otherwise renders only when the launcher scrolls.

## Install

Every push to `main` builds a signed APK with GitHub Actions and publishes it under **Releases**. Builds share one signing key, so each new APK installs over the previous one.

Open the app icon → it jumps straight to the live-wallpaper preview → set it on Home (and Lock screen if offered). In the preview, tap to replay the morph.

## Tuning

`SilkWarpService.java`: `SPEED`, `STRENGTH`, `SHEEN`. The shader lives in `SilkShader.java` and matches the web prototype's *Twist* preset.
