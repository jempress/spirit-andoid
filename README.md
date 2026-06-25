# Spirit (Android)

Turns your Android phone into a wireless trackpad for your Windows PC full mouse control, gestures and edge scrolling, with near zero perceptible latency.

This is the **client** half of Spirit. It detects touch gestures on your phone and sends them to the [Spirit PC app](#), which replays them as real Windows mouse input.

## Features

- Relative motion cursor control (real trackpad feel, not absolute tap-to-jump)
- Two-finger tap → right click
- Two-finger drag → scroll
- Two-finger pinch → zoom (Ctrl+Wheel)
- Long-press + drag → click-and-drag
- Edge gestures: drag near the right edge → vertical scroll; drag near the bottom edge → horizontal scroll
- Auto-discovers the PC over WiFi no manual IP entry needed in normal use
- USB connection option via `adb reverse`, for when WiFi isn't available
- Haptic feedback on click/tap so you get tactile confirmation without looking at the PC

## Requirements

- Android 7.0 (API 24) or newer
- The [Spirit PC app](https://github.com/jempress/spirit-pc) running on the same WiFi network (or connected via USB)

## Installing

Grab the latest debug APK from this repo's [GitHub Actions](../../actions) artifacts (`spirit-debug-apk`), or build from source in Android Studio.

You'll need to allow "install from unknown sources" once, since this isn't distributed through the Play Store.

## Building from source

Open the project root in Android Studio (not the `app/` subfolder), let Gradle sync, then run on a device or emulator. Or build from the command line:
```bash
./gradlew assembleDebug
```

## Using it

1. Start the Spirit PC app first (system tray icon should appear)
2. Open Spirit on your phone it auto searches for the PC on WiFi
3. Once found, it connects automatically and switches to the full-screen trackpad surface
4. If auto-discovery doesn't find your PC (different subnet, AP isolation, etc.), enter the PC's IP manually shown in the PC app's tray menu

### USB connection
If you'd rather not rely on WiFi, with the phone plugged in and USB debugging enabled, run on the PC once per session:
```
adb reverse tcp:6824 tcp:6824
```
then tap **"Connect via USB"** on the phone's connect screen.

## Gesture reference

| Gesture | Action |
|---|---|
| One-finger drag | Move cursor |
| One-finger tap | Left click |
| One-finger long-press + drag | Click-and-drag |
| Two-finger tap | Right click |
| Two-finger drag (vertical) | Scroll |
| Two-finger pinch | Zoom |
| One-finger drag near right edge | Vertical scroll (edge-scroll) |
| One-finger drag near bottom edge | Horizontal scroll (edge-scroll) |

Sensitivity and gesture thresholds live in one place `GestureTuning` at the top of `GestureSurface.kt` if anything feels too twitchy or sluggish.

## Architecture

| File | Responsibility |
|---|---|
| `MainActivity.kt` | App entry point, connect screen, orientation switching (portrait while connecting, landscape once connected) |
| `GestureSurface.kt` | Manual multi-touch gesture detection via `awaitPointerEventScope` translates raw touch into semantic commands |
| `SpiritConnection.kt` | TCP client; outbound frames funneled through a `Channel` so gesture callbacks never touch the socket directly |
| `DiscoveryClient.kt` | Listens for the PC's UDP discovery beacon; holds a `WifiManager.MulticastLock` so OEM power-saving doesn't silently drop broadcast packets |
| `Protocol.kt` | Wire format shared with the PC app (see PC repo's README for the full spec) |

## Known limitations

- Single PC connection at a time
- WiFi discovery requires both devices on the same subnet (won't cross routers or AP-isolated guest networks use manual IP entry or USB in that case)
- No authentication on the connection fine for personal/trusted-network use

## License

GNU
