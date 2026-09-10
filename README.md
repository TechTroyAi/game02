# Neon Sector Run

**Neon Sector Run** is an original, offline, native Android arcade game. Dodge hazards across shifting neon sectors, collect energy and rare rifts, then spend banked Bits in the **Fabrication Bay** to improve future runs.

The game is deliberately compact and complete: it has one responsive core mechanic, four visual sectors to discover, persistent high score/progression, touch/swipe controls, and zero network, advertising, account, or permission requirements.

## Play

1. Tap **START RUN**.
2. Tap the left or right side of the display (or swipe) to switch between three lanes.
3. Avoid blocks, drones, and mines. Collect blue Energy and rare circular Rifts.
4. After a crash, Bits are banked permanently.
5. Open the **Fabrication Bay** to build three upgrades, each with three levels:
   - **Reactor Tune** — increases score per run.
   - **Phase Shell** — begins a run with crash-saving shields.
   - **Salvage Array** — increases Bits banked after a run.

## Android build

Requirements: JDK 17 and Android SDK Platform 35 / Build Tools 35.0.0.

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The installable debug APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To install with Android Debug Bridge:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Application ID: `ai.techtroy.neonsector`
- Min Android version: Android 7.0 / API 24
- Target Android version: API 35
- Orientation: portrait
- Permissions: none
- Network requirement: none

## Implementation

The game is a fresh native Kotlin implementation built directly with Android's Canvas APIs. It does not reuse a game, game assets, or game code from the referenced App repository.
