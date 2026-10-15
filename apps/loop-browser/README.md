# Loop Browser

Standalone Android WebView browser prototype. This app is developed independently and is not based on another browser project.

## Current features
- URL/search field, Go, Back, Reload and manual Capture.
- HTTP/HTTPS navigation only.
- JavaScript and DOM storage enabled; local file/content access disabled.
- Manual visible-WebView PNG screenshot saved in app-specific external storage under `captures/`.

## Build
Use the Android APK Build GitHub Actions workflow in this repository. This subproject requires JDK 17 and Android SDK 35.

- Application ID: `com.pcontrol.loopbrowser`
- Minimum API: 26
- Version: 0.1.0

## Not implemented yet
- MCP bridge or agent loop.
- Automated screenshot polling and image delivery.
- DOM accessibility/state contract.

## Safety
Web content is untrusted. Future automation must use allowlisted actions, authenticated loopback-only communication, a visible stop control, action/time limits, and confirmation for sensitive operations. Never bypass CAPTCHA or access controls.
