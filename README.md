# Ashu AppLock 🔒
Glassmorphism + gradient App Lock & Hider. Accessibility + Shizuku. Open source (MIT).

## Features (all implemented)
- Pre-built glass lock overlay (min delay) + window blur (Android 12+), 3 themes: Glass / Gradient / Normal
- PIN, Pattern, Password + fingerprint (auto prompt); relock on app close / screen off
- Intruder selfie (3 wrong tries), fake crash screen (long-press title = real lock)
- Focus mode, per-app daily timers, shared Games limit, Reels/Shorts blocker, Pause
- Hider via Shizuku (hide / unhide), works for any launcher app

## Build (no PC needed)
1. Push this folder to a GitHub repo. 2. Actions tab -> "Build release APK" -> Run workflow.
3. Download the `AshuLock-release-apk` artifact and install. Or: Android Studio -> Open -> Run.
Then: enable the accessibility service (OFF/ON after every update), set a lock, pick apps.

## Notes
Untested on real devices yet. Reels/Shorts detection uses view ids that Instagram/YouTube may rename.

## Credits
Made by **Ashutosh** - Telegram: https://t.me/ashuapps_07x - DM: @ashutosh_07x - GitHub: ashutoshnishad799-svg
If you use or fork this code, keep the LICENSE and this credit.
