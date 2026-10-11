# webview-bundle-android e2e

Appium-driven end-to-end tests for the `testapp` module, mirroring the iOS e2e.

## How it works

The `testapp` (`MainActivity`) is a minimal WebView host: it serves the builtin
`testbed` bundle (0.4.0) over `https://testbed.wvb/` and loads it on launch, and
wires its updater to the playground remote for manual use. E2E supplies a local
remote endpoint through the test app's `wvbRemoteUrl` launch argument. The shared
suite fills the testbed's inputs, clicks its buttons and checks rendered results.
It downloads 0.4.1 and 0.4.2, verifies the page version after each installation,
and checks that rollback loads the previous UI. Downloads use the Android
emulator's `10.0.2.2` host alias.

The Appium session is switched into the testapp's **WEBVIEW** context, then the
suite runs through `createAppiumDriver(driver, { baseURL: "https://testbed.wvb" })`,
which drives the live DOM (CSS selectors, in-app navigation via `goto`).

`vitest` orchestrates it:

- **globalSetup** (`vitest.global-setup.ts`) builds the debug APK
  (`./gradlew :testapp:assembleDebug`).
- **setup** (`vitest.setup.ts`) installs the `uiautomator2` Appium driver, boots
  (or reuses) an emulator, starts an Appium server (with
  `chromedriver_autodownload` enabled), opens a session that installs and
  launches the testapp, and switches into its WEBVIEW context.
- **testbed.spec.ts** runs the shared UI suite for all 26 bridge methods, plus
  update-document, manifest, download/install/rollback and invalid-input scenarios.

## Prerequisites

- The Android SDK, with `platform-tools` (adb) and `emulator` available. Set
  `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) if it is not at the platform default.
- At least one AVD. The first one is used unless `ANDROID_AVD` is set; an
  already-booted emulator is reused. Physical devices are skipped because the
  fixture remote uses the emulator's `10.0.2.2` host alias.
- Node + a package manager (the repo pins toolchain via `mise`).
- Appium is installed as a dev dependency; the `uiautomator2` driver is installed
  into `~/.appium` on first run.

## Run

```sh
# from this directory
yarn install
yarn test
```

Environment knobs:

- `ANDROID_AVD` — AVD to boot when no device is already running.
- `WVB_E2E_HEADLESS=1` — boot the emulator with `-no-window` (default on `CI`).
- `WVB_E2E_KEEP=1` — leave a self-booted emulator running after the test.
- `APPIUM_PORT` — server port (default `4723`); choose different ports when running iOS and Android suites together.
