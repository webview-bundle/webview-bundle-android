import { createAppiumDriver } from "@wvb-playground/testdriver/appium";
import {
	methodSel,
	sel,
	testCases,
	type WebviewDriver,
} from "@wvb-playground/webview-testbed/testing";
import { beforeAll, describe, expect, test } from "vitest";
import { type Driver, getTestContext } from "./context.js";

// Drives the `@wvb-playground/webview-testbed` bundle inside the Android WebView. The
// testapp ships the builtin `testbed` bundle and points its updater at the live
// playground remote (https://playground-remote.wvb.dev).
describe("testbed (android)", () => {
	const BASE_URL = "https://testbed.wvb";
	const BUNDLE = "testbed";
	const BUILTIN_VERSION = "0.2.0";
	const REMOTE_VERSION = "0.3.0";

	// Loading the app shell (a full navigation) and the network download need more
	// headroom than the driver's default.
	const NAV_TIMEOUT = 30_000;
	const RESULT_TIMEOUT = 90_000;

	let browser: Driver;
	let driver: WebviewDriver;

	beforeAll(() => {
		browser = getTestContext().driver;
		driver = createAppiumDriver(browser, {
			baseURL: BASE_URL,
			defaultTimeoutMs: NAV_TIMEOUT,
		});
	});

	async function open(): Promise<void> {
		await driver.goto("/");
		await driver.waitForVisible(sel.appShell, { timeoutMs: NAV_TIMEOUT });
	}

	/**
	 * Runs one testbed method card on a freshly-loaded page and returns its terminal
	 * outcome. Reloading first guarantees the card starts idle (its result element is
	 * only rendered once a call settles), so waiting for the result can never observe a
	 * stale value left by an earlier run of the same method.
	 */
	async function runMethod(
		id: string,
		params: Record<string, string> = {},
	): Promise<{ status: string | null; text: string }> {
		await open();
		for (const [name, value] of Object.entries(params)) {
			await driver.fill(methodSel.param(id, name), value);
		}
		await driver.click(methodSel.run(id));
		await driver.waitForVisible(methodSel.result(id), { timeoutMs: RESULT_TIMEOUT });
		const outcome = {
			status: await driver.getAttribute(methodSel.result(id), "data-status"),
			text: await driver.text(methodSel.result(id)),
		};
		console.log(`[testbed] ${id} -> ${outcome.status}: ${outcome.text}`);
		return outcome;
	}

	function parseJson(text: string): Record<string, unknown> {
		try {
			return JSON.parse(text) as Record<string, unknown>;
		} catch {
			throw new Error(`expected a JSON result, got: ${text}`);
		}
	}

	// The only test that *asserts* the real remote download succeeds. Skipped on CI:
	// this fill + reload + download sequence hangs on the GitHub Actions Android emulator
	// (the network is reachable — the generic suite below downloads over it — but this
	// specific flow stalls there). It passes locally, and the generic suite still
	// exercises the download (as ok|error) on CI.
	test.skipIf(!!process.env.CI)(
		"downloads + installs the real remote update (builtin 0.2.0 -> remote 0.3.0)",
		async () => {
			await open();
			expect(await driver.text(sel.platformType)).toBe("android");

			const before = parseJson(
				(await runMethod("source.loadVersion", { bundleName: BUNDLE })).text,
			);
			expect(before).toEqual({ type: "builtin", version: BUILTIN_VERSION });

			const update = await runMethod("updater.getUpdate", { bundleName: BUNDLE });
			expect(update.status).toBe("ok");
			const info = parseJson(update.text);
			expect(info.name).toBe(BUNDLE);
			expect(info.version).toBe(REMOTE_VERSION);
			expect(info.isAvailable).toBe(true);
			expect(info.localVersion).toBe(BUILTIN_VERSION);

			const download = await runMethod("updater.download", { bundleName: BUNDLE });
			expect(download.status).toBe("ok");
			expect(parseJson(download.text).version).toBe(REMOTE_VERSION);

			const install = await runMethod("updater.install", {
				bundleName: BUNDLE,
				version: REMOTE_VERSION,
			});
			expect(install.status).toBe("ok");

			const after = parseJson(
				(await runMethod("source.loadVersion", { bundleName: BUNDLE })).text,
			);
			expect(after).toEqual({ type: "remote", version: REMOTE_VERSION });

			// Drop the cached descriptor so the next request loads the freshly-installed
			// version, and cache-bust so the WebView can't serve stale HTML.
			await runMethod("source.unloadDescriptor", { bundleName: BUNDLE });
			await driver.goto(`/?updated=${Date.now()}`);
			await driver.waitForVisible(sel.appShell, { timeoutMs: NAV_TIMEOUT });
			expect(await driver.text(".app__version")).toContain(REMOTE_VERSION);
		},
		240_000,
	);

	// The platform-agnostic suite the testbed package ships (each `@wvb/bridge` method
	// asserted to render an ok|error outcome). The "raw invoke" case is the only one that
	// fills a text input, which hangs on the CI Android emulator; skip it there (its
	// coverage overlaps source.listBundles). Runs after the strict update test so it
	// can't perturb that test's clean starting state.
	for (const testCase of testCases) {
		const register =
			process.env.CI && testCase.name.startsWith("raw invoke") ? test.skip : test;
		register(testCase.name, async () => {
			await testCase.run(driver);
		});
	}
});
