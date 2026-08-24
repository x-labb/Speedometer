# Speedometer

A GPS speedometer for Android. No ads, no trackers, no network access — the app does
not declare `android.permission.INTERNET`, so it is structurally incapable of loading
an ad or sending your location anywhere.

- Kotlin + Jetpack Compose, no Google Play Services dependency
- minSdk 26 (Android 8.0), targetSdk 36 (Android 16)
- Release APK: **~0.8 MB**

## How it measures speed

Speed comes from `Location.getSpeed()` on the raw `GPS_PROVIDER`, not from the fused
provider and not from differencing successive positions. On essentially every modern
chipset that value is derived from the Doppler shift of the satellite carrier signal,
which makes it both smoother and considerably more responsive than position
differencing. Position differencing exists only as a fallback for fixes that arrive
without a speed field.

## Features

- Large speed readout with a 270° arc dial, sized to the screen
- km/h ↔ mph toggle, remembered between launches
- Trip max speed, moving average, and distance; reset button
- Live GPS status: acquiring / fix / weak / lost, with accuracy and satellites-in-fix
- Screen stays awake while the app is open
- Portrait and landscape layouts
- English and French

## Edge cases that are handled

Signal quality:

| Case | Behaviour |
|---|---|
| Cold start, no fix yet | Shows `––` and "Acquiring GPS", never a confident `0` |
| Updates stop (tunnel, garage) | 5 s watchdog flips to "Signal lost" instead of freezing the last speed on screen |
| Fix carries no speed field | Falls back to differencing positions |
| GPS jitter while parked | Anything under 1.0 m/s (3.6 km/h) displays a hard zero |
| Implausible reading | Above 120 m/s (432 km/h) the sample is discarded entirely |
| Poor horizontal accuracy | Above 50 m the status shows "Weak signal" |
| Stale fix replayed by the provider | Rejected using `elapsedRealtimeNanos`, which is monotonic — unlike `getTime()`, which follows the wall clock and jumps when the network updates it |
| Mock location provider | Explicitly flagged on screen |

Trip statistics — the ones that silently corrupt naive implementations:

- **Distance is only integrated when accuracy is ≤ 25 m and the phone is actually
  moving.** Otherwise a phone sitting on a desk accumulates kilometres overnight.
- **A step larger than twice what the reported speed allows is discarded** as a fix
  discontinuity (a re-lock after a tunnel), not as distance travelled.
- **Max speed only updates from trustworthy fixes** (≤ 25 m accuracy, and speed
  uncertainty ≤ 2 m/s where the device reports it). Without this gate a single bad
  fix permanently records a 200 km/h "record".
- **Average is a moving average** — distance divided by time actually spent moving,
  not by wall-clock time including stops.
- **The reference fix is dropped when the app is backgrounded**, so returning after
  ten minutes in a pocket does not integrate one enormous bogus step.

Permissions and system state:

- Runtime permission request, with distinct copy for first ask, plain denial, and
  "Don't ask again" (which deep-links to app settings, since further requests are
  silently denied by the system at that point).
- Android 12+ "Approximate" location is detected and explained — coarse location
  cannot measure speed at all.
- Location switched off system-wide is detected, with a deep link to the setting.
- Everything is re-evaluated on every return to the foreground, so granting the
  permission or flipping the Location switch in settings is picked up on return.
- Permission revoked while running is caught rather than crashing.

Platform:

- `LocationListener.onStatusChanged` / `onProviderEnabled` / `onProviderDisabled` are
  explicitly overridden. They only gained default implementations in API 30; omitting
  them would throw `AbstractMethodError` on an API 26–29 device.
- Edge-to-edge is enabled (mandatory from Android 15) with `safeDrawingPadding()`.
- Rotation preserves the trip via a ViewModel; the activity also handles the config
  change directly.
- GPS updates stop when the screen is not visible, so it does not drain the battery
  in the background.
- The speed digits are sized to the dial and divide out the system font scale, so a
  2× accessibility font setting does not clip the number against the arc.
- Distances format with the device locale (`1,25 km` on a French phone).

## Known limitations

- **Trip stats do not survive process death.** Rotation and backgrounding are fine,
  but if Android kills the process to reclaim memory, max/average/distance reset. The
  unit preference does persist.
- **No background operation.** There is no foreground service, so speed is only
  tracked while the app is on screen. This is deliberate — it keeps the app simple and
  avoids a persistent notification.
- **GPS speed lags reality by roughly a second**, and is not a legally certified
  measurement.
- **Continuous 1 Hz GPS with the screen on uses real battery.** Plan on being plugged
  in for long drives.

## Building

Requirements:

- JDK 17 (the Android Gradle Plugin 8.x does not support JDK 22+)
- Android SDK with platform 36 and build-tools 36.1.0

Point the build at your SDK with either an `ANDROID_HOME` environment variable or a
`local.properties` file containing `sdk.dir=/path/to/android/sdk`. That file is
gitignored because it is machine-specific.

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

### Signing

No keystore is committed — signing material must never live in a public repository.
If `keystore.properties` is missing, or the keystore its `storeFile` points to does not
exist, the release build falls back to the debug key, which still produces a perfectly
installable APK for personal use.

To sign with your own key, generate one and place it in the project root:

```bash
keytool -genkeypair -v -keystore speedometer-release.jks -alias speedometer \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then copy `keystore.properties.example` to `keystore.properties` (gitignored) and
fill in your passwords. Keep the keystore safe: Android refuses to install an
update signed with a different key than the version already on the device.

## Installing

Sideloading on a Samsung device requires turning off **Auto Blocker**
(Settings → Security and privacy → Auto Blocker), which blocks installs from unknown
sources by default. Then either copy the APK to the phone and tap it, or install over
USB with `adb install -r app-release.apk`.

On first launch, go outside — a cold GPS start takes 5–30 seconds.

## Project layout

```
app/src/main/java/io/github/xlabb/speedometer/
  MainActivity.kt        Activity: edge-to-edge, keep-screen-on, Compose host
  LocationEngine.kt      LocationManager wrapper exposing fixes as a Flow
  SpeedViewModel.kt      Signal filtering, trip integration, permission state
  SpeedUiState.kt        UI state model and unit definitions
  Format.kt              Locale-aware number formatting
  ui/SpeedometerScreen.kt  Compose UI, dial, permission and location gates
  ui/Theme.kt            Dark colour scheme
```

## License

MIT — see [LICENSE](LICENSE).
