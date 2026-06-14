# webview-bundle-android e2e

Appium-driven end-to-end tests for the `testapp` module, mirroring the iOS e2e.

## How it works

The `testapp` (`MainActivity`) is a minimal WebView host: it serves the builtin
`hacker-news` bundle over `https://hacker-news.wvb/` and loads it on launch. It
carries **no** test logic of its own. The scenarios live in the shared
`@wvb-playground/webview-hacker-news` package (the same suite the iOS e2e runs)
and are expressed against the platform-agnostic `WebviewDriver` from
`@wvb-playground/testing`.

The Appium session is switched into the testapp's **WEBVIEW** context, then each
exported `testCase` is run through `createAppiumDriver(driver, { baseURL:
"https://hacker-news.wvb" })`, which drives the live DOM (CSS selectors,
in-app navigation via `goto`).

`vitest` orchestrates it:

- **globalSetup** (`vitest.global-setup.ts`) builds the debug APK
  (`./gradlew :testapp:assembleDebug`).
- **setup** (`vitest.setup.ts`) installs the `uiautomator2` Appium driver, boots
  (or reuses) an emulator, starts an Appium server (with
  `chromedriver_autodownload` enabled), opens a session that installs and
  launches the testapp, and switches into its WEBVIEW context.
- **smoke.spec.ts** runs every `testCase` from
  `@wvb-playground/webview-hacker-news/testing`.

## Prerequisites

- The Android SDK, with `platform-tools` (adb) and `emulator` available. Set
  `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) if it is not at the platform default.
- At least one AVD. The first one is used unless `ANDROID_AVD` is set; an
  already-booted device/emulator is reused.
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
