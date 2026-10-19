# Android Device Checker

Android app (Android 8.0+) that tests an Android build, custom ROM or stock, on real hardware against a device support checklist, and saves Markdown and JSON reports.

The checklist is paraphrased and adapted from the [LineageOS Device Support Requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md) ([CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)), which itself adapts parts of Google's Android Compatibility Definition Document. Tests that come from elsewhere say so in their details: the kernel floor from the LineageOS 23.0 and 24.0 release notes, and tests adapted from the [/e/OS QA plans](https://gitlab.e.foundation/e/qa/test-cases) (SMS and MMS, VoLTE and VoWiFi, dual SIM and eSIM, multitouch, battery and charging, USB OTG, factory reset). Those apply to every ROM as SHOULD.

The app detects the ROM family. A build with `ro.lineage.version` is LineageOS-based; it is /e/OS when `ro.elegal.url` also points to e.foundation or Murena, and LineageOS otherwise.

- LineageOS project rules (userdebug build type, Updater, Recovery and add-ons, Jelly, device tree, wiki and maintainer rules) apply to LineageOS only.
- /e/OS rules apply to /e/OS only: core apps and browser (MUST), microG and its self-check, Advanced Privacy, default DNS, Play Integrity basic, a relocked bootloader on lockable devices, OTA updates and the install guide (SHOULD). /e/OS publishes no officialization checklist, so these come from its build types documentation, relocking guide and QA plans.
- The rest of the charter, including LiveDisplay and the kernel floor, applies to both. The app is not affiliated with LineageOS or Google; credits are in the in-app About screen.

Each requirement has a test mode:

- Automated: the app decides pass or fail (SELinux, verity, su, governors, I/O schedulers, MACs, resolution, Wi-Fi and mobile data, SD write/read, codec encode/decode...).
- Assisted: the app gathers data (clocks, charging current, Bluetooth codecs, sensor list) and you compare it with stock.
- Interactive: an in-app test does the work and asks at most a yes/no (tones per audio output, mic levels, a photo per camera, video clips, GPS fix, NFC tag, fingerprint, Bluetooth scan, sensor reactions, IR).
- Manual: a guided step list with buttons that open the right settings screen or run the action through Shizuku (USB mode, tethering state, reboot to recovery).
- Not on device: source tree, wiki and maintainer items, listed so the checklist is complete.

The app groups related checks and actions into entries such as Wi-Fi, Cameras and Microphones. Open an entry to run its automatic checks or start an individual guided test. Categories remember whether they are collapsed, including after restarting the app, and their headers show counts for every result state. Individual tests retain their requirement level (MUST, SHOULD), result and action button. The priority filter selects required (MUST / MUST NOT), recommended (SHOULD / SHOULD NOT) or optional (MAY) tests and also limits the bulk automatic run. Tests covering several requirements use their strongest level. A test's details show the requirement it covers, what the app does, and the raw output of the last run. A test runs once and its result counts for every requirement it covers. A tester can mark any test passed, failed or not applicable.

Anything a machine cannot judge (how a recording sounds, whether a photo or clip looks right, where the fingerprint prompt says the sensor is) needs a tester answer before the test can be saved.

## Install

Download the APK from [Releases](https://github.com/rthery/android-device-checker/releases) and install it, or run `adb install` with it. Releases are signed with the project's release key, while a local debug build is signed with your machine's debug key: uninstall one before installing the other.

## Crash watcher and stress tests

With Shizuku connected, every check and every test reads the crash log buffer for the time it ran. Any process crash during it (camera daemon, camera provider, audio HAL, Bluetooth, RIL...) fails it and the crash is attached to the result. `crashes.boot` scans dropbox, which survives `logcat -c` and holds tombstones, app crashes, ANRs, runtime restarts and repeated wtf reports since boot.

Interactive test screens keep the display awake until you leave the test, so the normal screen timeout cannot interrupt camera, audio or sensor work. This does not prevent manually locking the phone.

The stress tests target bugs that only show up with fast or repeated use:

- Camera switching: random camera, then a photo, a 0.5 to 3 s video, a close mid-recording, autofocus churn, or a photo, video and photo switch on one open camera, with 0.1 to 0.8 s gaps. It checks first frame latency, that every clip has audio missing from at most 300 ms of its video, and a stable camera list, then reopens every camera.
- Camera HAL and API coverage: reads the camera-service HAL inventory through Shizuku, then takes photos and records video with sound through Camera2 and legacy Camera1 on every accessible camera. Saved media requires a tester verdict. API coverage does not force alternate HAL versions; unavailable inventory or additional vendor HAL paths need separate review. Run it with `--es run hw.camera.hal`.
- Audio churn: random speaker and earpiece beeps, recordings and start/stop bursts. A first mic buffer slower than 500 ms fails (audio HAL blocking on a missing service).

## Stock profile

Many checks compare the build with stock: MAC addresses, resolution, cameras, sensors, codecs, CPU frequencies. In Device > Stock baseline, use **Capture this ROM** while running the original stock ROM and keep the JSON file off the phone before changing ROMs. On the ROM being tested, choose **Import stock file**. The app validates the device, loads the baseline and profile, and reruns its stock-based comparisons while preserving tester verdicts. Listening, photos, tag scans and other interactive tests still need their own results. Device > Stock profile and device packs can also supply basic stock values; they do not include the full captured measurements.

## Shizuku

Optional. Some reads (Bluetooth MAC, and sysfs nodes on enforcing builds) are denied to the app uid. With [Shizuku](https://shizuku.rikka.app/) running and the permission granted, those commands run through a Shizuku user service with the shell uid. Without it they show "Needs Shizuku". When Shizuku is connected and the build's SELinux policy still blocks adb shell (common on stock ROMs), the check warns instead.

## Device packs

A device pack is a JSON file a ROM maintainer ships so the app adapts to their device and ROM: stock profile values, extra tests, granted exceptions and theme hints. It is data only; nothing in it is executed as code. Samples: [docs/packs/eos-bullhead.json](docs/packs/eos-bullhead.json) (/e/OS 3 on the Nexus 5X) and [docs/packs/lineage-bullhead.json](docs/packs/lineage-bullhead.json) (LineageOS on the Nexus 5X).

The app loads every `*.json` in `/product/etc/devicechecker/` and `/system_ext/etc/devicechecker/`, then the files imported from Device > Device packs. A pack applies when `ro.product.device` is in `devices` and every `match` condition holds. Before a pack is enabled, the user sees every shell command, file read and screen it can open, its profile values and its waivers, and approves them. The approval is tied to the file's SHA-256, so an edited pack needs a new one. Enabling merges `profile` into the stock profile once; the user can still edit it. When several packs are enabled, a later one (imported after system) overrides earlier waivers and theme.

Without a pack the app keeps its default look (dynamic colors, the ROM's DeviceDefault framework theme and the AOSP-style icon).

`theme.icon` switches the launcher icon between the ones bundled in the app (AOSP, LineageOS-style teal, /e/OS-style blue gradient), by enabling one launcher activity-alias and disabling the others. A pack cannot ship its own image: launchers only show icons from the APK. Some launchers drop the home screen shortcut when the alias changes, so the user may need to add it again. All three have a monochrome layer for themed icons.

### Format (schema version 1)

Unknown keys are errors. A file with another `schemaVersion` is refused.

| Key | Meaning |
| --- | --- |
| `schemaVersion` | `1` |
| `id` | Lowercase id (`[a-z0-9][a-z0-9._-]*`). An imported pack with the same id replaces the ROM's copy. |
| `name`, `rom`, `romVersion`, `maintainer` | Shown in the app and the reports. |
| `devices` | `ro.product.device` values. |
| `match` | Optional property conditions, `{"prop", "equals"}` or `{"prop", "matches"}` (regex, searched). An unset property reads as `""`. |
| `profile` | Partial stock profile, same keys as `DeviceProfile` (and as adb `SET_PROFILE`). |
| `tests` | Extra tests, see below. |
| `waivers` | `{"requirement", "reason", "checks"?, "reference"?}`. Without `checks` the whole requirement shows as Waived; with `checks`, only those check or test results are ignored. A tester verdict still wins. |
| `theme` | `accentSeed` (`#RRGGBB`), `cornerScale` (0 to 3), `fontFamily` (a system font family name such as `sans-serif-condensed`), `icon` (`aosp`, `lineage` or `eos`). |

Every test has `type`, `id`, `title`, `category` (a requirement section: `GENERAL`, `AUDIO`, `RIL`, `ENCRYPTION`, `WIFI`, `USB`, `GPS`, `BLUETOOTH`, `CAMERA`, `CODECS`, `DISPLAY`, `NFC`, `FINGERPRINT`, `IR`, `SD_CARD`, `SENSORS`, `BATTERY`, `ACCESSORIES`, `DEVICE_TREE`, `KERNEL`, `SECURITY`, `UPDATES`, `BLOBS`, `VENDOR`, `FEATURES`, `PROJECT`), `level` (`MUST`, `MUST_NOT`, `SHOULD`, `SHOULD_NOT`, `MAY`), an optional `description` and an optional `replaces`. It appears in the test list under its category with a "From <pack>" label, its id is `pack.<pack id>.<test id>`, and its results are stored and reported like built-in tests.

`replaces` names a built-in requirement the test stands in for, when the ROM meets it another way: a pack for a LineageOS fork that ships its own browser can replace "Jelly or another LineageOS-sourced browser ships" with a check for that browser. The built-in requirement disappears, and so do the built-in checks that covered only it. The approval dialog, the test details and the reports show what was replaced. Use a waiver instead when the requirement cannot be met at all; a requirement cannot be both.

| `type` | Fields | Runs |
| --- | --- | --- |
| `prop` | `prop`, one of `equals` or `matches` | Automatic |
| `file` | `path` under `/sys`, `/proc`, `/system`, `/system_ext`, `/product`, `/vendor` or `/odm` (same `/sys` and `/proc` rules as `cat` below); one of `contains` or `matches` | Automatic; falls back to Shizuku `cat` for `/sys` and `/proc` |
| `shell` | `command`, `expect` (regex) | Automatic, through Shizuku only |
| `settings` | `intent` (`label`?, `action`, or `packageName` and `className`), `question` | Manual, opens the screen and asks |
| `guided` | `intro`, `steps` of `{"text", "shortcut"?, "command"?}` | Manual step list, like the built-in ones |

```json
{ "type": "shell", "id": "battery.health", "title": "Battery reports good health", "category": "BATTERY", "level": "SHOULD",
  "command": "dumpsys battery | grep health", "expect": "health: 2" }
```

### Shell command allowlist

Pack commands are checked when the pack loads and again before each run. A command is one source, optionally piped (`|`) into filters:

- Sources: `getprop [name]`, `cat` of paths under `/sys/` or `/proc/` (no `.` or `..` segments, nothing under `/proc/self`, `/proc/thread-self` or `/proc/<pid>`, no `root`, `cwd`, `exe`, `fd` or `map_files` links), `dumpsys <service>` with only `-a`, `-l`, `--charged`, `--brief` or `--short` (not `--checkin`, which commits and clears saved state in some services), `settings get <system|secure|global> <key>`, `settings list <namespace>`, and `cmd` with `package list`, `overlay list`, `wifi get-country-code|list-networks|list-scan-results`, `settings get|list`, `location is-location-enabled`.
- Filters: `grep` (one pattern, no `-e`, `-f` or `-r`), `head`, `tail` (no `-f`), `wc`, `cut`, `tr`, `sort` (only `-n`, `-r`, `-u`). Each filter has a fixed set of options, and filters cannot take file arguments.
- No `;`, `&`, `||`, redirection, `$`, backticks, subshells, unquoted globs (`*`, `?`, `[`) or line breaks. Single quotes are literal; double quotes cannot hold `$`, backticks or backslashes.

Filter with `grep` instead of passing arguments to `dumpsys`: subcommand arguments are refused because some of them change state (`dumpsys battery unplug`, `dumpsys batterystats --reset`).

### Later option: plugin APKs

A pack cannot run code, which keeps it reviewable. Tests that need code (a HAL call, a custom sensor sequence) could later come from a signed plugin APK exposing a bound service, with the same approval step. That is not built.

## Reports

Every run, test result, verdict and profile change rewrites the reports, and every run or test adds a snapshot. All files are under `/sdcard/Android/data/io.github.rthery.devicechecker/files/reports/`:

- `report.md` and `report.json`: every requirement with its outcome, the result of each test, and `nextSteps`, what is left to do with the adb command for each.
- `catalog.json`: every test with its kind, requirements, what it does, whether it runs headless or needs a human, and the exact commands to run, open and record it. Start here when driving the app from a script or an LLM.
- `artifacts/<test id>/`: photos, video clips and microphone recordings left by tests for review.
- `history/<timestamp>.json` (last 50) and `status.json` (`running`, `done` or `stopped`).
- `stock.json`: written by a stock capture.

The Report tab shares the Markdown or JSON text.

## adb control

For scripts and LLM agents: nothing needs a tap except what only a person can judge or do. The receiver only accepts broadcasts from holders of `android.permission.DUMP`, which adb shell has and normal apps do not. Results also go to logcat with the tag `DeviceChecker`.

Automatic checks run in the background:

```bash
adb shell am broadcast -n io.github.rthery.devicechecker/.AdbCommandReceiver -a io.github.rthery.devicechecker.RUN_CHECKS --es ids selinux,kernel.governors
```

Tests that use the camera, microphone, GPS or Bluetooth run with the app in the foreground, so start them through `AdbLinkActivity`, which only adb can open: the app comes to the front, shows over the lock screen and keeps the screen on until the run ends. `run` takes `checks`, `headless`, `all` or a list of ids:

```bash
adb shell am start -n io.github.rthery.devicechecker/.AdbLinkActivity --es run headless --ei iterations 10
```

With Shizuku connected the app grants its own runtime permissions; without it, grant them with `adb shell pm grant io.github.rthery.devicechecker android.permission.CAMERA` (and `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`).

Record a verdict on any test, for example after looking at its artifacts:

```bash
adb shell "am broadcast -n io.github.rthery.devicechecker/.AdbCommandReceiver -a io.github.rthery.devicechecker.SET_RESULT --es test camera.capture --es verdict pass --es note 'Both photos sharp'"
```

Open a test or tab for the parts a person must do (`--es tab <tests|report|device>` works too):

```bash
adb shell am start -n io.github.rthery.devicechecker/.AdbLinkActivity --es test usb.mtp
```

Capture stock data on the stock ROM, then import it on the custom ROM. The app runs on Android 8.0 and later, so it installs on most stock ROMs:

```bash
adb shell am broadcast -n io.github.rthery.devicechecker/.AdbCommandReceiver -a io.github.rthery.devicechecker.CAPTURE_STOCK
```

```bash
adb pull /sdcard/Android/data/io.github.rthery.devicechecker/files/reports/stock.json
```

```bash
adb push stock.json /sdcard/Android/data/io.github.rthery.devicechecker/files/import/stock.json
```

```bash
adb shell am broadcast -n io.github.rthery.devicechecker/.AdbCommandReceiver -a io.github.rthery.devicechecker.IMPORT_STOCK
```

The import replaces the stock profile and makes the CPU frequency, sensor, codec and audio device checks compare with stock instead of asking you. A pack imports the same way: push it to `files/import/pack.json` and send `IMPORT_PACK`, adding `--ez approve true` to enable it.

`SET_PROFILE --es json '<partial profile json>'` merges profile values, `SET_VERDICT` sets a requirement verdict, and `EXPORT` rewrites the reports without running anything. Verdicts are `untested`, `pass`, `fail` or `not_applicable`.

## Build

Needs JDK 17 or later and the Android SDK (platform 36), with `sdk.dir` in `local.properties` or `ANDROID_HOME` set. The Gradle wrapper is pinned to a version AGP 8 supports.

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest lintDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For scrolling and responsiveness checks on hardware, use `./gradlew assemblePerformance` and install `app/build/outputs/apk/performance/app-performance.apk`. This build uses release runtime behavior and the local debug signing key, so it can update a local debug install without deleting its data. Use debug builds when you need a debugger.

`./gradlew assembleRelease` builds an unsigned APK unless `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` are set. Pushing a `vMAJOR.MINOR.PATCH` tag builds the signed APK in GitHub Actions and publishes it as a release. The tag must match `versionName` in `app/build.gradle.kts`, and `versionCode` must be `MAJOR * 10000 + MINOR * 100 + PATCH`. Store listing texts and screenshots for F-Droid and IzzyOnDroid live in `fastlane/metadata/android/`, with one `changelogs/<versionCode>.txt` per release.

## Layout

- `catalog/`: the requirements, paraphrased from the LineageOS charter, with Lineage-only items flagged.
- `checks/`: one `Check` per probe; requirements reference them by id.
- `profile/`: stock values the checks compare against.
- `shell/`: app-uid shell and the Shizuku shell service.
- `interactive/`: in-app tests, guided steps and settings shortcuts.
- `pack/`: device pack schema, parser, command allowlist, loader and the packs screen.
- `data/`: repository, persistence, the unified test list (`UnifiedTests.kt`), outcome evaluation, Markdown and JSON reports.
- `ui/`: Compose screens: Tests, Report and Device tabs, the test details sheet and the test runner.

## License

The code is under the [Apache License 2.0](LICENSE). The requirement texts in `catalog/` are paraphrased from the [LineageOS Device Support Requirements](https://github.com/LineageOS/charter/blob/main/device-support-requirements.md) and stay under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
