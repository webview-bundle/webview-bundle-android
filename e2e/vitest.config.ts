import { defineConfig, type ViteUserConfig } from "vitest/config";

const config: ViteUserConfig = defineConfig({
	test: {
		name: "webview-bundle-android/e2e",
		include: ["**/*.spec.ts"],
		fileParallelism: false,
		pool: "forks",
		testTimeout: 120_000,
		// Retry flaky cases on CI (the Android emulator + Appium webview intermittently
		// stalls); no retries locally so real failures surface fast.
		retry: process.env.CI ? 2 : 0,
		hookTimeout: 1_800_000,
		globalSetup: ["./vitest.global-setup.ts"],
		setupFiles: ["./vitest.setup.ts"],
	},
});

export { config as default };
