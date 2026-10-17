# CI notes (fork)

`.github/workflows/android.yml` builds three single-ABI APKs in parallel and uploads them as artifacts
(`holo-telegram-<abi>-<variant>-<short sha>`):

| artifact | Gradle | package |
|---|---|---|
| x86_64 debug (emulator) | `:TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=x86_64` | `io.holo.telegram.beta` |
| arm64-v8a debug | `:TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a` | `io.holo.telegram.beta` |
| arm64-v8a release, **unsigned** | `:TMessagesProj_App:assembleAfatRelease -Pandroid.injected.build.abi=arm64-v8a -PunsignedRelease=true` | `io.holo.telegram` |

Gradle changes (`TMessagesProj_App/build.gradle`, defaults unchanged): `-PunsignedRelease=true` sets the release
`signingConfig` to null; with `-Pandroid.injected.build.abi=<abi>` the prebuilt libs of the other ABIs are excluded.
With the injected-ABI property AGP leaves the APK in `build/intermediates/apk/afat/<type>/app.apk`
(not `build/outputs`); the workflow handles both.

## Signing

Nothing in the workflow uses secrets.

- **Debug APKs** are signed with `TMessagesProj/config/release.keystore` (`signingConfigs.debug`, password from
  `gradle.properties`), the public Telegram test key, not a per-run debug keystore. So debug APKs from different CI runs
  can update each other, but anyone can sign an APK with the same key.
- **Release** (default, without `-PunsignedRelease`) is signed with the same public keystore. The CI release APK is
  built unsigned on purpose.
- To sign the unsigned release APK in CI add repository secrets `KEYSTORE_BASE64` (`base64 -w0 my.jks`),
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, and a step like:

  ```yaml
  - name: Sign release APK
    if: matrix.variant == 'release-unsigned' && github.event_name != 'pull_request'
    env:
      KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
      KS_PASS: ${{ secrets.KEYSTORE_PASSWORD }}
      KEY_PASS: ${{ secrets.KEY_PASSWORD }}
      KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
    run: |
      BT="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
      echo "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.jks"
      "$BT/zipalign" -f -p 4 "${{ steps.apk.outputs.path }}" "$RUNNER_TEMP/aligned.apk"   # align BEFORE signing
      "$BT/apksigner" sign --ks "$RUNNER_TEMP/release.jks" --ks-key-alias "$KEY_ALIAS" \
        --ks-pass env:KS_PASS --key-pass env:KEY_PASS --out dist/signed.apk "$RUNNER_TEMP/aligned.apk"
      "$BT/apksigner" verify --print-certs dist/signed.apk
      rm -f "$RUNNER_TEMP/release.jks"
  ```

  apksigner signs with v2/v3 by default (minSdk is 23, so v1 is not needed). Never commit the keystore.
- Security: pull requests from forks get no secrets, so signing must be restricted to push / tag / workflow_dispatch;
  keep `permissions: contents: read`; use an environment with required reviewers for the signing job if wanted.
- A new key means CI-signed APKs cannot update our local builds signed with the throwaway `holo-debug.jks`
  (same package `io.holo.telegram`): uninstall once. To avoid that, put `holo-debug.jks` into the secrets instead.
  The debug variant has its own package (`.beta`), so it installs next to the release one.
