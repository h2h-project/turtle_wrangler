# turtle_wrangler
The Android app for wrangling hope turtles and their bales.

**Turtle Wrangler** is the way that we talk and make requests to hope turtles.  Its 
a leap forward from the simple OLED and single action interface that we've been using so far.
That said, it doesn't take its place.  Instead, it compliments.  Wrangler talks to a 
Hope Turtle directly over Bluetooth Low
Energy from a few metres away. It's a full-screen upgrade from clicking
through the turtle's tiny OLED one button-press at a time. It doesn't
replace the shore tracking at [hopeturtles.org](https://hopeturtles.org),
and the turtle never needs a phone to navigate.

The turtle side is [turtleOS](https://github.com/h2h-project/turtleOS). The
wire format both sides build against is **GATT contract v1** in turtleOS's
[`docs/wrangler/README.md`](https://github.com/h2h-project/turtleOS/blob/main/docs/wrangler/README.md).
The app's phased plan is
[`docs/wrangler/02_android_app.md`](https://github.com/h2h-project/turtleOS/blob/main/docs/wrangler/02_android_app.md).

## Status

**App Phase 1: BLE core.** It scans for turtles, connects, checks the
contract version, reads Device Information and live values, pairs using
the 6-digit code on the turtle's OLED, and round-trips a test command.
The Dashboard, Navigate, GPS and Diagnostics tabs come next.

## Build

Requirements: Android Studio 2026.2+ (it bundles the JDK and SDK), and
a phone on **Android 12 or newer**.

- Open the folder in Android Studio and press Run, or build from a terminal:

```bash
export JAVA_HOME=/snap/android-studio/current/jbr   # Android Studio's bundled JDK
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone, enable **Developer options → USB debugging** and accept the
laptop's prompt.

## Using it with a turtle

1. A turtle only advertises while its **wrangle window** is open: for
   10 min after boot or wake, and whenever its Bluetooth screen is opened
   (triple-click, then single-click three times).
2. Tap the turtle in the list to connect.
3. To send commands, pair once: tap **Pair**, then type the 6-digit code
   the turtle shows on its Bluetooth screen.

## License

GPL-3.0 — see [LICENSE](LICENSE).
