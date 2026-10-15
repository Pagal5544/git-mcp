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
- The repository has an `Android APK Build` workflow at `.github/workflows/android-build.yml` on both checked branches.
- That workflow runs `./gradlew --no-daemon assembleDebug` and uploads `app/build/outputs/apk/debug/*.apk` as the `android-debug-apk` artifact.
- The checked repository root did **not** contain the Android Gradle project files such as `gradlew` and `app/`. Therefore, the workflow exists, but a successful APK build has **not** been verified.
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

1. Create/use a **separate app repository**; do not place the Android app source in the MCP bridge repository.
2. Ensure the app repository has a valid Gradle wrapper, Android Gradle configuration, `app/` module, manifest, and source code.
3. Add or adapt an Android GitHub Actions workflow in the app repository. Build a debug APK with `assembleDebug`.
4. Upload the APK as a GitHub Actions artifact.
5. Trigger the workflow through GIT MCP, inspect its status and logs, and fix failures in the app repository only.
6. Download/check the artifact and install/test it before calling the prototype complete.
7. Keep source changes, build results, and deployment state clearly distinguished.

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
- [ ] Create a separate PIN File Share app repository.
- [ ] Implement the minimum Android prototype and build it with GitHub Actions.
- [ ] Verify the APK artifact and test a real transfer between two phones.

## 8. Definition of done

This project is complete only when:

- The existing GIT MCP bridge and OAuth connection still work.
- Existing default-repository behavior remains compatible.
- ChatGPT can safely operate on a separately selected authorized app repository.
- The PIN File Share Android app builds successfully in GitHub Actions.
- A downloadable APK artifact is available.
- A real file transfer between two phones has been tested, including invalid/expired PIN handling.
- Neither `Quiz` nor `termux-quiz-mcp` was modified.

---

**Operating rule:** Inspect first, make the smallest isolated change, verify it, and only then proceed. Never trade the working MCP bridge for a faster shortcut.
