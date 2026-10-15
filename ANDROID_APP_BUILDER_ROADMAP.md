# Android App Builder Roadmap — PIN File Share

> **Purpose:** Keep the direction, safety rules, and next steps in one place so work on the Android app and MCP bridge does not drift or get forgotten.

## 1. Final goal

Build a minimal Android app that transfers files from one phone to another using a PIN. Use ChatGPT + the existing GIT MCP bridge + GitHub Actions to write code, build the APK, inspect errors, fix them, and provide the resulting APK artifact.

The user should not need to compile the Android project on their phone.

## 2. Non-negotiable safety rules

- Work only in the `Pagal5544/git-mcp` repository when changing the bridge.
- **Never modify the separate `Quiz` website repository or `termux-quiz-mcp` as part of this project.**
- Do not delete, rename, overwrite, or casually merge `main` or `feat/github-oauth-mcp`.
- Treat `feat/github-oauth-mcp` as important bridge work. Inspect both branches before changes; do not assume their contents are identical.
- Do not change the live Cloudflare Worker or deploy automatically while developing/testing.
- Never put GitHub tokens, OAuth client secrets, cookie-encryption keys, or MCP auth tokens in source files, commits, logs, or this document. Keep secrets in the appropriate GitHub/Cloudflare secret settings.
- Make changes in a clearly named, separate feature branch where practical. Read back every committed file and verify the resulting commit.
- Before any deployment or merge, test the bridge's existing OAuth connection and existing repository operations. If a change fails, stop and fix it before deployment.
- Do not claim a build, test, or deployment succeeded unless the corresponding result was actually checked.

## 3. Current known state (checked 2026-10-15)

- GitHub repository: [`Pagal5544/git-mcp`](https://github.com/Pagal5544/git-mcp)
- Relevant branches: `main` and `feat/github-oauth-mcp`.
- Existing Cloudflare Worker endpoint: `https://git-mcp.dolatrammpi.workers.dev/mcp`.
- The bridge's `main` and `feat/github-oauth-mcp` branches are to remain protected and unchanged by app implementation work.
- Android app development is currently isolated on `feat/pin-file-share-android` in this same repository, as the chosen temporary approach. Do not move app work onto `main` or `feat/github-oauth-mcp`.
- The app branch contains a Gradle Android project (`settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, manifest, and `MainActivity.java`) plus `.github/workflows/android-build.yml`.
- **Verified success (2026-10-15):** GitHub Actions run [38674327285](https://github.com/Pagal5544/git-mcp/actions/runs/38674327285) completed successfully. The `Build debug APK` step reported `BUILD SUCCESSFUL`, and the `android-debug-apk` artifact uploaded successfully.
- Artifact ID: `11945014071`; size: 8,887 bytes; artifact download page: [Download android-debug-apk](https://github.com/Pagal5544/git-mcp/actions/runs/38674327285/artifacts/11945014071). It expires on 2026-10-22 unless downloaded or a new artifact is produced.
- The workflow installs Android SDK platform/build tools, sets up JDK 17 and Gradle 8.9, runs `gradle --no-daemon assembleDebug`, and uploads `app/build/outputs/apk/debug/*.apk`.
- The current APK is an **early UI prototype only**. The Send file picker and Receive UI are present, but PIN pairing and actual file transfer are not implemented; a successful build does not mean a real transfer has been tested.
- Recent Worker Type Check runs succeeded, but that alone does not prove the live Worker was deployed with the exact current branch code.
- GIT MCP read/write operations were tested successfully. Keep preserving those working operations.
- The builder workflow is currently in the bridge repository; do not assume this automatically makes the MCP capable of targeting arbitrary repositories.

Re-check the live repository and branch state before acting on any of these notes; they are a snapshot, not a guarantee that nothing has changed.

## 4. Architecture needed

### A. Preserve the existing bridge

Keep the existing OAuth-enabled bridge and its current default target working. Its current default target is configured by repository variables such as `GITHUB_OWNER` and `GITHUB_REPO`.

### B. Add safe target-repository support

The goal is to let ChatGPT select an authorized target repository for app work without breaking existing calls.

- Add optional target owner/repository parameters to appropriate tools, or provide a clearly scoped target-repository selection mechanism.
- If no target is supplied, preserve backward compatibility: use the currently configured default repository.
- Validate repository names and restrict operations to repositories the connected GitHub identity is authorized to access.
- Do not let user input override credentials, secret settings, or authentication policy.
- Keep file operations and workflow triggers scoped to the selected repository and branch.
- Test reads, writes, and workflow triggering against a dedicated test repository/branch before using the app repository.
- Confirm the actual OAuth/GitHub permission model supports the intended target repositories; do not assume it does.

Do not start implementation until the current source, tool schemas, auth flow, and both branches have been inspected.

## 5. PIN File Share app requirements

### Minimum first version

- Native Android app with a clean, minimal interface.
- Two main actions: **Send file** and **Receive file**.
- Sender selects a file and creates/shares a short-lived PIN or pairing code.
- Receiver enters the PIN and explicitly accepts the incoming transfer.
- Show connection, transfer progress, success, cancellation, and readable error states.
- Handle permission denial, interrupted connections, and expired/invalid PINs.
- Do not upload or transfer a file until the user confirms the intended transfer.
- Keep the implementation simple; add advanced features only after the first build and transfer tests pass.

### Connectivity decision

Do not promise that a PIN alone can connect two phones over every network. Choose and document a real transfer design before implementation:

- For same-network/local transfers, evaluate a direct local connection with clear connection instructions.
- For transfers across different networks, determine whether a reachable rendezvous/signalling service and/or relay is needed.
- Never silently route files through an unapproved third-party service.
- PINs must expire, be rate-limited against guessing, and be bound to the intended transfer. Use encrypted transport and verify the peer/session before sending data.

### Initial acceptance tests

1. Both phones can open the app.
2. Invalid, expired, and repeated PIN attempts are rejected safely.
3. Receiver must accept before the sender transmits the selected file.
4. A small test file arrives intact; compare size and a cryptographic hash.
5. Cancellation and network interruption show a useful result without corrupting an existing file.
6. No unrelated files are accessed or sent.
7. The APK installs on a supported Android device.

## 6. Build and debug loop

1. For now, keep the Android app isolated on `feat/pin-file-share-android` in `Pagal5544/git-mcp`; this is a deliberate temporary choice and does not authorize changes to bridge code.
2. Keep the Android Gradle project, manifest, Java source, and workflow together on that app branch.
3. Build a debug APK with GitHub Actions (`assembleDebug`). **Completed successfully on 2026-10-15.**
4. Upload the APK as the `android-debug-apk` GitHub Actions artifact. **Completed successfully; artifact ID `11945014071`.**
5. Trigger workflows through GIT MCP, inspect statuses/logs, and fix build failures only on the app branch. **First successful build verified.**
6. Download the artifact, install it on a supported Android phone, and inspect the UI. **Still to do; do not equate artifact upload with device installation/testing.**
7. Keep source changes, build results, device test results, and deployment state clearly distinguished.
8. Revisit whether to move the app to a separate repository later, only as a planned step that preserves the working MCP bridge.

## 7. Work sequence / checklist

- [ ] Inspect current source and tool schemas on both `main` and `feat/github-oauth-mcp`.
- [ ] Identify which exact code is deployed to the live Worker; verify without deploying changes.
- [ ] Document existing tool behavior and regression tests.
- [ ] Design optional target-repository support with backward-compatible defaults.
- [ ] Implement it on a separate bridge feature branch.
- [ ] Run type checks and tests; test existing default-repository operations.
- [ ] Test repository selection/read/write/workflow dispatch on a dedicated test repository.
- [ ] Review security, authorization, and secret handling.
- [ ] Only after explicit verification, plan merge/deployment and re-test the live bridge.
- [x] Create the initial Android app project on the isolated `feat/pin-file-share-android` branch (temporary same-repository approach).
- [x] Add the initial Send/Receive UI and Android GitHub Actions build workflow.
- [x] Fix Android SDK package installation in the workflow and obtain a successful `assembleDebug` build.
- [x] Verify successful upload of the `android-debug-apk` artifact (ID `11945014071`).
- [x] Download the artifact ZIP, extract the APK, install it on the user's Android phone, and launch it successfully (user-provided screenshot, 2026-10-15).
- [x] Visually verify the initial Send/Receive home screen on-device; both buttons and the prototype notice are displayed.
- [ ] Test file picker interaction on-device and address any install/runtime issues.
- [ ] Design the actual PIN pairing and transfer architecture, including same-network vs cross-network behavior.
- [ ] Implement expiring PINs, rate limiting, receiver consent, encrypted transport, integrity/hash verification, progress, cancellation, and error handling.
- [ ] Test a real file transfer between two phones, including invalid/expired PINs and interrupted transfers.
- [ ] Consider moving app code to a separate repository later, without touching protected bridge branches or changing the live Worker without explicit approval.

## 8. Current implementation status

### Completed

- [x] Isolated app branch created: `feat/pin-file-share-android`.
- [x] Minimal native Java Android project and Send/Receive prototype UI added.
- [x] GitHub Actions workflow configured for JDK 17, Android SDK platform/build tools, and Gradle 8.9.
- [x] Workflow SDK setup issue corrected by installing `platforms;android-35` and `build-tools;35.0.0` with `sdkmanager --install` separately.
- [x] Build run `38674327285` succeeded (`BUILD SUCCESSFUL`, 1m 44s) and artifact upload succeeded.
- [x] Artifact metadata verified: `android-debug-apk`, ID `11945014071`, 8,887 bytes, expires 2026-10-22.

### Not yet completed

- [x] APK installed and app launched on a real Android phone; initial UI confirmed from user screenshot (2026-10-15).
- [ ] Implement PIN creation, expiry, validation, and phone-to-phone pairing.
- [ ] Implement actual transfer transport and receiving/saving files.
- [ ] Add receiver confirmation, encryption, integrity verification, and transfer-state/error handling.
- [ ] Test end-to-end transfer between two phones.

The successful build and artifact upload are verified, and the user has confirmed the APK installs and launches on an Android phone. This verifies the initial UI only; it does **not** prove PIN pairing or file-transfer functionality.

## 9. Definition of done

This project is complete only when:

- The existing GIT MCP bridge and OAuth connection still work.
- Existing default-repository behavior remains compatible.
- ChatGPT can safely operate on a separately selected authorized app repository.
- The PIN File Share Android app builds successfully in GitHub Actions. **Initial debug build verified on 2026-10-15.**
- A downloadable APK artifact is available. **Initial artifact verified; ID `11945014071`.**
- The APK has been installed and tested on a supported device, and a real file transfer between two phones has been verified.
- A real file transfer between two phones has been tested, including invalid/expired PIN handling.
- Neither `Quiz` nor `termux-quiz-mcp` was modified.

---

**Operating rule:** Inspect first, make the smallest isolated change, verify it, and only then proceed. Never trade the working MCP bridge for a faster shortcut.
