# Mohalla Monsoon Mission — Native 3D Android Game

This is a standalone Android game project intentionally isolated under `games/mohalla-monsoon-3d`. It does not modify the repository's existing TypeScript service or the `main` branch.

## Direction

- Native C++17 and OpenGL ES 2.0, using Android NativeActivity.
- Third-person character built from articulated 3D body parts with walk/run/jump cycles, turning, landing and foot splash feedback.
- Procedural Indian mohalla: plaster and brick houses, clay roofs, doors, windows, power poles/wires, curbs, drains, asphalt, mud and puddles.
- Dynamic monsoon rain, puddle reflections/specular response, lightning-like sky flashes, pickup effects and rain ambience synthesized on-device.
- Touch joystick, jump/sprint, health, score, supply objective, pause/retry, win and fail states.
- High-detail 2048x2048 procedural RGB texture maps are generated during CI. The original maps are packaged uncompressed to keep the distributable APK above 50 MB while the renderer downsamples them to 1024x1024 at runtime for balanced GPU memory usage.

## Build

Install Android SDK, NDK 27.0.12077973, CMake 3.22.1, JDK 17, Python 3 and NumPy. From this directory:

1. Run `python3 tools/generate_assets.py`.
2. Run `gradle --no-daemon assembleDebug`.
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions does the same build and uploads the APK as the `mohalla-monsoon-debug-apk` artifact. The workflow also checks that the final APK is larger than 50,000,000 bytes.

## Notes

This is an actively developed gameplay prototype, not a claim of photorealism. Its environment and animation are implemented in C++ rather than composed of flat placeholder rectangles. Device-specific visual and performance quality must still be verified by installing the APK on real Android hardware.
