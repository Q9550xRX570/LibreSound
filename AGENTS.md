# Agent Instructions (`LibreSound`)

Read [AGENT_VISION_HANDOFF.md](./AGENT_VISION_HANDOFF.md) before making changes.

## Mandatory Workflow & Quota Rules
1. **CI-First APK Builds:** Do **NOT** run `assemble*Release`, `assemble*Preview`, or `assemble*Debug` in the local/cloud agent VM unless explicitly requested by the user or diagnosing a packaging/R8 issue.
2. **Verify Fast & Push:** Run unit tests (`./gradlew :core:dsp:test :app:testLiteDebugUnitTest :app:testFullDebugUnitTest`) and lint (`:app:lintLiteDebug :app:lintFullDebug` when manifests, resources, or Android API calls change), then commit and `git push origin main`.
3. **Artifact Delivery:** `.github/workflows/android.yml` automatically verifies and uploads `LibreOpus-Preview-APKs` (`lite` + `full` ABI splits) on every push to `main`.
4. **Focus on App Code:** Skip editing `README.md` or store copy unless explicitly requested. Keep `values/` (`en`) and `values-tr/` (`tr`) synchronized, and keep `lite` strictly offline (zero `INTERNET` permission, zero `youtubedl-android`).
5. **Upfront Time Estimate (ETA):** Before writing code, running Gradle verification, or pushing CI builds, always give the user a concise estimated duration upfront in Turkish (e.g. code edit ~1–2 dk, local unit tests + lint ~2–4 dk, GitHub Actions APK build ~4–8 dk on Public 4 vCPU runners) so they know when to check back.

