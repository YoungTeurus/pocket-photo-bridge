# Codex Cloud task — Android MVP 1

Repository: `YoungTeurus/pocket-photo-bridge`.
Read `README.md`, `docs/architecture.md`, and [Issue #1](https://github.com/YoungTeurus/pocket-photo-bridge/issues/1) first.

## Objective

Implement an actual Android app (Kotlin, Compose or a simple native UI) that can receive a **JPEG selected on the Sony RX100 VII camera** using `Send to Smartphone → Select on This Device`, show its preview, and save it to the Gallery. Xiaomi printing is explicitly out of scope.

The user has only an Android tablet and GitHub/Codex Cloud. You MUST provide an Android GitHub Actions workflow building a sideloadable **debug APK** and uploading it as an artifact.

## Engineering plan

1. Inspect repository and research Sony DSC-RX100M7 Wi-Fi/Send-to-Smartphone behavior; compare official Sony information and open-source implementations. Distinguish camera-initiated sharing from phone-initiated browsing. Review candidate Sony transfer repos critically and cite findings in the docs.
2. Scaffold buildable Android Kotlin/Gradle project. Choose maintained compatible versions and include Gradle wrapper, Android manifest, app ID, reproducible CI.
3. Implement a foreground network/pairing and Connect flow using supported Android APIs, with user-visible status; manual Wi-Fi association is an acceptable fallback if Android limits programmatic connection.
4. Add a `SonyTransferSession` abstraction and implement only protocol behavior supported by evidence. Expose an on-device diagnostics screen showing safe, redacted connection and negotiation information. Gracefully handle timeouts, errors, and camera Wi-Fi without internet.
5. If camera-selected transfer protocol is understood, implement JPEG retrieval, preview and MediaStore Save. If not, **do not fake successful transfer** or silently replace it with an unrelated photo browser: deliver the working Wi-Fi connectivity/diagnostic foundation, document the blocker precisely and supply a short, actionable on-device experiment for the user.
6. Build/test what can be built in Codex environment, configure `.github/workflows/android.yml`, and document install/test steps for the user on an Android tablet. If emulator/hardware is unavailable, clearly state this.

## Constraints

- Minimal dependency surface and concise UI; no sign-in/server/cloud uploads.
- Keep Wi-Fi/QR credentials and camera photos out of logs and Git.
- No Xiaomi functionality, no requirement to background scan Wi-Fi.
- Isolate Sony protocol and Android-specific networking for future Linux port.
- Do not claim hardware-level success without user testing.

## Deliverables

- Code and tests where feasible.
- CI-generated APK artifact.
- README instructions, troubleshooting, and concise report in PR including what is implemented vs what requires testing.
- Open a pull request for review rather than pushing directly to `main`.

Start with the smallest vertical slice that builds and provides actionable camera-network diagnostics.
