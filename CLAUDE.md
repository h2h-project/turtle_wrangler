# Turtle Wrangler — developer guide

Android app (Kotlin + Jetpack Compose, MVVM) that is the BLE central for
Hope Turtles running turtleOS. The firmware lives next door at
`../turtleOS` (also checked out on this machine).

## Source of truth

- **Wire format:** GATT contract v1, `../turtleOS/docs/wrangler/README.md`.
  Change the contract there first, then both sides. Never invent a field,
  opcode or result code in the app.
- **Plan / phases:** `../turtleOS/docs/wrangler/02_android_app.md`.
- **Firmware reference** for exact byte layouts:
  `../turtleOS/device/src/net/ble_telemetry.py` (characteristics) and
  `ble_commands.py` (commands). `TurtleCodec` mirrors them byte for byte.
- **Visual identity:** the palette in `ui/theme/Theme.kt` comes from
  hopeturtles.org. Pink is a spotlight: at most one pink element per
  screen, ghost or tint only, never a solid fill.

## Layout

```
app/src/main/java/org/hopeturtles/wrangler/
├── MainActivity.kt        permission + Bluetooth-on gates, screen switching
├── WranglerViewModel.kt   scan, connect, pair, commands; the only UI-facing API
├── ble/
│   ├── TurtleUuids.kt     contract UUIDs (base 26d0XXXX-5890-45f2-b0be-090d35436a95)
│   ├── TurtleCodec.kt     decoders → data classes; sentinels → null
│   ├── TurtleScanner.kt   scan filtered on the Turtle service UUID
│   ├── GattQueue.kt       ONE outstanding GATT op at a time (Android rule)
│   ├── TurtleConnection.kt  GATT link: MTU → discover → contract check → read → subscribe
│   ├── CommandClient.kt   opcode/seq framing, IN_PROGRESS, timeouts, result messages
│   └── Payloads.kt        command payload encoders + OK-result payload decoders
├── data/LastSeenStore.kt  per-turtle "last seen" cache (never shown as live)
└── ui/                    Compose screens + theme
app/src/test/              JVM unit tests (decoders vs real Applemore bytes)
```

## Rules

- **Screens never touch `BluetoothGatt`.** They read `TurtleConnection.state`
  / `.telemetry` and act through `WranglerViewModel`.
- **Every GATT operation goes through `GattQueue`.** A second op before
  the previous callback fails silently on Android.
- **minSdk 31 < API 33:** implement both the byte-array GATT callbacks and
  write calls (33+) and the deprecated ones; ignore the deprecated
  callbacks on 33+ to avoid double delivery (see `TurtleConnection`).
- **Sentinels are "no data":** decoders return null, and the UI shows "—".
  Accept values longer than the contract says (ignore the tail), reject
  shorter ones.
- **Every command shows its outcome.** `ResultCode.message` is the one
  place error wording lives. On `NOT_BONDED`, start pairing and explain
  that the code is on the turtle's Bluetooth screen, which is the only
  place the turtle accepts pairing.
- **"Here" means the turtle's position**, never the phone's
  (`DEST_SET_HERE`, journeys). Say so in the UI.
- **Ask before changing where the turtle goes.** Destination and journey
  commands go through `ConfirmDialog`; `vm.busy` disables buttons while a
  command is in flight (the firmware runs one at a time).
- **The map is the only internet use** (OpenFreeMap tiles, no key). Never
  use the phone's location; the BLE permission text promises that.
- **Public repo:** no keystores, `local.properties`, API keys or turtle
  credentials in git (see `.gitignore`).

## Build & test

```bash
export JAVA_HOME=/snap/android-studio/current/jbr
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s TurtleConnection
```

Versions are in `gradle/libs.versions.toml`. AGP must not be newer than
what the installed Android Studio knows (2026.2 knows 9.4.x). AGP 9
compiles Kotlin itself, so there's no `org.jetbrains.kotlin.android`
plugin, only the Compose compiler plugin.

When the firmware side is unavailable, `../turtleOS/tests/ble_decode.py`
decodes hex copied from nRF Connect, and `ble_fuzz_host.py` exercises the
firmware's command channel on the host.
