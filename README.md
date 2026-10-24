# OTG Interval Camera 0.3

Samsung Galaxy S25 / OTG UVC camera. Real JPEG frames, user-selected save directory, 1–300 second intervals, MJPEG/YUYV, USB diagnostics, and resolution selection (including 2560×1440, 3840×3104, 3840×2160, 4624×3468, 4168×3126, if supported by hardware).

The screen stays awake while the app is displayed (FLAG_KEEP_SCREEN_ON). Picture-in-Picture, kiosk mode, touch lock, and system button blocking are not used. The settings gear is shifted 32 dp lower; status text is raised 36 dp from the bottom.

The camera preview and interval shooting are intended to run with the app visible; the app does not provide a persistent background USB capture service.

Build with GitHub Actions -> Build APK -> Artifacts -> OTG-Camera-APK.
