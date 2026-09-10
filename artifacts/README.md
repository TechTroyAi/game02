# Build artifacts

The signed-by-debug-key installable build is retained here by the **Build Android APK** GitHub Actions workflow after each source push to the Arena work branch.

- `Neon-Sector-Run-v1.0.0-debug.apk` — Android 7.0+ (API 24+) debug APK, application ID `ai.techtroy.neonsector`.

The same workflow also uploads the APK as a GitHub Actions artifact called `Neon-Sector-Run-v1.0.0-debug-apk` for 30 days. The workflow always runs `assembleDebug` and checks that the resulting APK is non-empty before publishing it.

Install over USB debugging with:

```bash
adb install -r artifacts/Neon-Sector-Run-v1.0.0-debug.apk
```

For direct device installation, copy the APK to the Android device, open it in a file manager, and allow the installer permission for that file manager if Android asks.
