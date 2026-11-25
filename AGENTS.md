# Agent Instructions (`LibreSound`)

Read [AGENT_VISION_HANDOFF.md](./AGENT_VISION_HANDOFF.md) before making changes.

## Mandatory Workflow & Quota Rules
1. **CI-First APK Builds:** Do **NOT** run `assemble*Release`, `assemble*Preview`, or `assemble*Debug` in the local/cloud agent VM unless explicitly requested by the user or diagnosing a packaging/R8 issue.
2. **Verify Fast & Push:** Run unit tests (`./gradlew :core:dsp:test :app:testLiteDebugUnitTest :app:testFullDebugUnitTest`) and lint (`:app:lintLiteDebug :app:lintFullDebug` when manifests, resources, or Android API calls change), then commit and `git push origin main`.
3. **Artifact Delivery:** `.github/workflows/android.yml` automatically verifies and uploads two separate lightweight artifacts on push to `main`: `LibreOpus-Full-ARM64-Preview` (~20 MB, `arm64-v8a` only via `-Plibreopus.previewArm64Only=true`) and `LibreOpus-Lite-Preview` (~3 MB).
4. **Focus on App Code:** Skip editing `README.md` or store copy unless explicitly requested. Keep `values/` (`en`) and `values-tr/` (`tr`) synchronized, and keep `lite` strictly offline (zero `INTERNET` permission, zero `youtubedl-android`).

