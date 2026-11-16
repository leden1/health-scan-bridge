# Health Scan Bridge

Quick word from a human. I have Xiaomi Mijia Body Composition Scale S400 Pro, and it only
connects to Xiaomi Home app, which for some reason doesn't integrate with Health Connect.
So i vibe coded this app to 'fix the problem'. I export the scale Weight report to image,
then import that image in this app. U need to first allow access to Health Connect then
u can save the stats. App should work with other apps screen-capture, but it was not
tested. If it doesn't work i am sure u can copy this repo and vibe code support for new style
of image. Or share an image and i will add support when i find time.
The rest is all AI, i am sure there is no spelling mistakes :D

Import a body-composition scale **screenshot**, read every metric off it on-device with OCR,
review and correct the numbers, then write the confirmed values into **Health Connect**.
Everything that Health Connect cannot store stays in the app's own history.

Works with the long result screens printed by Tanita / Withings / Garmin / Xiaomi style scales
(English and Croatian labels), including multi-page screenshots.

## Flow

1. Pick a screenshot in the app, or share an image into Health Scan Bridge from the gallery.
2. ML Kit text recognition runs fully on-device (bundled model, no upload, works offline).
   Tall screenshots are processed in overlapping tiles and re-assembled in image coordinates.
3. The parser matches each number to its label by geometry (same line, above, or below),
   recognises both `12,3` and `12.3` decimal styles, and reads the measurement date/time
   printed on the screen (ISO, `14.11.2026 09:02`, `14. 11. 2026`, …).
4. The review screen shows every detected field with its OCR confidence, lets you edit values
   and the capture time, and lets you choose which fields go to Health Connect.
5. Confirmed fields are written as `manualEntry` records carrying a stable
   `clientRecordId` derived from the measurement id, so re-confirming the same screenshot
   updates the record instead of duplicating it. Non-selected values remain local only.

## What is written where

Health Connect (1.1.0 client, write + read permissions requested):

| Field | Record | Unit |
| --- | --- | --- |
| Weight | `WeightRecord` | kg |
| Height | `HeightRecord` | m (cm input is converted) |
| Body fat | `BodyFatRecord` | % |
| Fat-free body weight | `LeanBodyMassRecord` | kg |
| Body water mass | `BodyWaterMassRecord` | kg |
| Bone mass | `BoneMassRecord` | kg |
| BMR | `BasalMetabolicRateRecord` | kcal/day |

Local history only (17 fields — Health Connect has no record type for them in 1.1.0):
BMI, fat mass, muscle mass, muscle %, skeletal muscle mass, body water %, protein %, protein mass,
bone mineral %, visceral fat rating, body score, body age, waist-to-hip ratio, standard weight,
weight / fat / muscle control.

History is stored as JSON in app-private storage (`files/measurements.json`), with per-field
source text, confidence, derived flags and warnings, and can be deleted locally and/or in
Health Connect.

## Build

Requirements: JDK 17, Android SDK (platform 36, build-tools 36), Gradle 8.14+.
On this machine they live in `~/android-dev` (JDK, SDK, Gradle, `platform-tools`).

```bash
source ~/android-dev/env.sh
cd ~/Documents/KiloCode/HealthScan
gradle --no-daemon :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (~82 MB, it embeds the on-device OCR model).

Install on a connected device:

```bash
~/android-dev/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` points at the SDK (`sdk.dir=/home/branimir/android-dev`). If you open the
project in Android Studio instead, let it sync; the pinned dependency versions below are the
ones that resolve with AGP 8.13.2 / compileSdk 36.

## Tech stack

- Kotlin 2.4.21, AGP 8.13.2, Gradle 8.14.7, compileSdk/targetSdk 36, minSdk 29
- Jetpack Compose (BOM 2026.06.01, Material 3), ViewModel + StateFlow
- Google ML Kit text recognition (bundled, on-device)
- AndroidX Health Connect client 1.1.0
- `org.json` for the local store (no database, no extra dependency)

## Layout

```
app/src/main/java/com/healthscan/app/
├── MainActivity.kt              # Compose host, share-intent entry points
├── domain/BodyField.kt          # 24 field specs, EN + HR label synonyms, formatting
├── domain/Measurement.kt        # Measurement / FieldValue models, text normalisation
├── ocr/ImageLoader.kt           # sampling, EXIF orientation, upscaling of small images
├── ocr/OcrEngine.kt             # ML Kit with overlapping tiles, line merge + dedupe
├── parse/NumberParser.kt        # 12,3 / 12.3 / ranges / unit suffixes
├── parse/DateParser.kt          # capture date and time from the screenshot
├── parse/MeasurementParser.kt   # label → value geometry matching
├── parse/Consistency.kt         # cross-checks, derived fields, range warnings
├── health/HealthConnectRepository.kt  # availability, permissions, write/delete
├── store/MeasurementStore.kt    # JSON history persistence
├── viewmodel/ScanViewModel.kt   # scan → review → commit state machine
└── ui/                          # Import, Review, History screens, theme
```

## Notes

- Health Connect is built into Android 14+; on Android 10–13 install the Health Connect app
  from Play Store first. On a device without any Health Connect provider the app still runs and
  keeps the full history locally.
- Permissions are requested once, for all seven writable record types; granting a subset is fine,
  the rest are simply reported as skipped on commit.
- OCR quality depends on the screenshot. Anything unreadable or implausible is left out and
  reported as a warning rather than guessed; every written value is one you confirmed.
- Body fat, water and bone measurements are hydration-sensitive: take the photo from the same
  scale, same time of day, for comparable trends.

## Release

`v1.0` carries the debug-signed `app-debug.apk` (on-device ML Kit model bundled, ~78 MB). Install it
directly, or build it yourself with the commands below. The application id stays
`com.healthscan.app`, so updating over an existing install keeps its history and Health Connect
permissions.

## Status

`:app:testDebugUnitTest` — 12 parser tests, all passing (two-column value-above-label screens,
Croatian single-line screens, label-less hero weight, labels split over two lines, the suggestion
block below its labels, decimal/comma styles, date formats, consistency checks).
`:app:assembleDebug` produces a signed debug APK with the on-device OCR model bundled.

Validated on a Xiaomi 25010PN30G (Android 16) with a real 1440x9028 body composition screenshot
shared into the app: 98 OCR lines at 81% mean confidence, 23 of 24 fields attributed, height derived
from weight and BMI, and the Health Connect write reported
"Weight, Height, Body fat, Fat-free body weight, Body water mass, Bone mass, BMR" with no failures.
The screenshot itself has no height, so the derived value is the only estimated write.


