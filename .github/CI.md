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

**`-Pandroid.injected.testOnly=false` is mandatory** whenever `-Pandroid.injected.build.abi` is used. AGP's
`isTestApk` returns true when `android.injected.build.abi` is set and `android.injected.testOnly` is not, so the
manifest gets `android:testOnly="true"` (debug and release alike) and phones refuse the install
(`INSTALL_FAILED_TEST_ONLY`, shown by some ROMs as a bare `-15`). The workflow passes it and fails the job if
`aapt2 dump badging` still reports `testOnly`.

## Telegram api_id / api_hash

The public api_id 4 is rejected by Telegram (`API_ID_PUBLISHED_FLOOD`), so the app needs your own pair from
my.telegram.org. They are not stored in the repo: `TMessagesProj/build.gradle` reads `TG_APP_ID` / `TG_APP_HASH`
(Gradle property or environment variable), puts them into `BuildConfig.APP_ID` / `APP_HASH`, and
`BuildVars` uses those. Configuration (`gradlew tasks`, Android Studio sync) works without them; the build (`preBuild`) fails with a clear message when either is missing or malformed (id: digits,
hash: 32 hex chars). For a throwaway debug build with the public key add `-PallowPublicApiKey=true` (login will
most likely fail).

- CI: add repository secrets `TG_APP_ID` and `TG_APP_HASH` (Settings > Secrets and variables > Actions). The workflow
  passes them as environment variables and checks first that they exist. Pull requests from forks get no secrets,
  so they fail on purpose.
- Local, either way:
  - environment variables: `TG_APP_ID=... TG_APP_HASH=... ./gradlew ...`
  - `~/.gradle/gradle.properties` (not in the repo): `TG_APP_ID=...` and `TG_APP_HASH=...`, or `-PTG_APP_ID=...
    -PTG_APP_HASH=...` on the command line.

  Android Studio reads `gradle.properties` but **not** environment variables of a terminal, so use
  `gradle.properties` for builds started from the IDE (or start the IDE from a shell that has them exported).
- The key is compiled into the APK (it can be extracted from it), so do not publish the CI artifacts.

## Signing

The only secrets the workflow needs for building are `TG_APP_ID` / `TG_APP_HASH`; signing (below) is optional.

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
- CI debug APKs are signed with the public Telegram test key `TMessagesProj/config/release.keystore`: anyone can
  sign an update for the same package with it, so do not share the artifacts. Local builds (`CN=Holo Debug`) and CI
  APKs have different signatures, so switching between them needs an uninstall once (app data and login are lost).
