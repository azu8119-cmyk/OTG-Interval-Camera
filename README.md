# OTG Interval Camera v0.5 — Touch Lock

Samsung Galaxy S25 / USB OTG UVC. Native-resolution JPEG photos, selectable camera resolution (including 2560×1440 / 3840×2160 / 4624×3468 where supported), MJPEG/YUYV, photo interval 1–300 seconds, optional start/end time, selected save folder and USB diagnostics.

## Touch lock (without Knox, root or reset)

- Tap the **🔒** button in the top-left of the main camera screen.
- Touch input inside the app is blocked with a transparent overlay. Camera preview and timed photo capture continue.
- The **settings** and **Start/Stop** controls are hidden while locked.
- To unlock, swipe deliberately **left to right** across the screen, starting in its left half. A horizontal swipe of at least 140 dp unlocks.
- Screen stays on when the app is visible. Status and navigation bars are temporarily hidden using immersive mode; Android may reveal them with system gestures.

### Optional stronger Android screen pinning

- In app settings, toggle **Закреплять приложение (блокировать Home и Недавние)** (enabled by default).
- On Samsung Galaxy: Settings → Security and privacy → More security settings → Allow apps to be pinned. Turn it on.
- Upon locking, Android may show a screen pinning confirmation; **accept it**. Unlocking with the app's right swipe calls `stopLockTask()` to release pinning.
- System unpin gestures remain available and may require the phone PIN. This is normal Android app pinning, **not** Device Owner / Knox. It does not guarantee that the status shade or every system action is permanently disabled.
- Without screen pinning permission, the app-local touch shield still functions.

## Other controls

- Tap **▶ Старт / ■ Стоп** twice within 500 ms to start/stop a shooting session.
- Optional scheduled start/end uses phone time and keeps working while this activity remains running and responsive. It does not guarantee background execution.
- Screen stays awake while the app is visible.
- No PiP, no root, no accessibility or device-admin permission.

GitHub Actions → Build APK → Artifacts → OTG-Camera-APK.
