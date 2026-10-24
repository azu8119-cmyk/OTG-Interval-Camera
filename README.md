# OTG Interval Camera v0.4

Samsung S25 UVC USB camera. Native-resolution JPEG, save-folder selection, 1–300 second interval, MJPEG/YUYV, diagnostics and the existing high-resolution camera presets.

## Updated controls
- Status line raised from 36 dp to 64 dp above the bottom.
- Main-screen **Старт / Стоп** button located below the settings gear.
- **Two taps within 500 ms** to start, stop, or cancel a scheduled session. A single tap does nothing.
- Settings have an optional switch for start/end times (24-hour local phone time). Set them with time pickers, then press **Сохранить настройки**.
- When armed by double-tap, a single scheduled session starts at the configured start time (immediately if within the active time window) and stops at end time. Crossing midnight is supported. If a time has passed, it schedules the next occurrence. It is not an automatically recurring daily task.
- With the schedule switch off, double-tap starts immediately and stops only on another double-tap.
- **FLAG_KEEP_SCREEN_ON** remains active while the app is visible. No PiP, kiosk or touch blocking.

**Important:** This scheduling uses Android Activity timers and needs the app to remain open and active. It cannot guarantee running while the app is closed, backgrounded or killed, and it does not restore an armed session after a restart.

Build via GitHub Actions -> Build APK -> Artifacts -> OTG-Camera-APK.
