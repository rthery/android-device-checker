# Agent notes

Read README.md first: it covers what the app does, the device pack format and the adb interface.

## Build and test

- `./gradlew testDebugUnitTest lintDebug` before every commit. Lint must stay clean.
- Gradle is pinned to 9.4.1: Gradle 9.6 and later break AGP 8.x. Do not bump the wrapper without bumping AGP.
- minSdk is 26. Lint only recognizes inline `Build.VERSION.SDK_INT` checks, so put version guards inline, not behind a helper. API fallbacks live in `compat/`.

## Code layout rules

- A check is a `Check(id, title) { ... }` in `checks/`; requirements in `catalog/RequirementCatalog.kt` reference checks and tests by id. A test id never changes once released, since reports and packs refer to it.
- Inside a check, use `read(command, accept)`: it runs as the app first, then through Shizuku. Make `accept` reject error text, and end commands with `; true` when a partial failure is fine (`ls -R` exits 1 on one unreadable folder).
- `needsShell()` inside a check already handles the "Shizuku is connected but SELinux blocks adb shell" case.
- Lineage-only requirements set `lineageOnly = true` and become N/A on builds without `ro.lineage.version`.
- Anything only a person can judge (sound, image quality) must end with a tester verdict, never an automatic pass.

## Testing on a device

- Camera, microphone, GPS and sensors only work with the app in the foreground. Start those tests with `am start -n io.github.rthery.devicechecker/.AdbLinkActivity --es run <ids>`, not the background broadcast.
- `AdbLinkActivity` and `AdbCommandReceiver` require `android.permission.DUMP`, so only adb shell can call them. `MainActivity` is not exported and ignores intent extras; keep it that way.
- `status.json` keeps "done" from the previous run until the next one starts. Wait for its `time` to change, not for the state alone. Camera HAL logs can push result lines out of logcat, so read `report.json` for results.
- Do not reinstall or kill the app while a camera test runs: it can leave the camera HAL unable to drain until the device reboots.
- `logcat -c` erases the crash buffer the crash watcher reads. Prefer `logcat -T <time>` to read from a point in time; dropbox (`crashes.boot`) survives either way.
- The Android 8.0 emulator has no audio input on some hosts, so MediaRecorder and AudioRecord fail there. Verify mic and video on hardware.
- On stock ROMs, SELinux blocks some reads even for adb shell. Treat that as a limit of the build, not a failure.
