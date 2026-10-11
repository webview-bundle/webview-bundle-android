import { startFixtureRemote, type FixtureRemote } from "./remote.js";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { glob } from "tinyglobby";
import { remote } from "webdriverio";
import {
	APPIUM_PORT,
	type AppiumServer,
	ensureAppiumDrivers,
	startAppiumServer,
} from "./appium.js";
import { type AndroidDevice, ensureAndroidDevice } from "./device.js";

export const ROOT = path.resolve(
	path.dirname(fileURLToPath(import.meta.url)),
	"..",
);
export const APP_PACKAGE = "dev.wvb.android.testapp";
export const APP_ACTIVITY = "dev.wvb.testapp.MainActivity";

export type Driver = Awaited<ReturnType<typeof remote>>;

export interface TestContext {
	server: AppiumServer;
	fixtureRemote: FixtureRemote;
	device: AndroidDevice;
	driver: Driver;
}

/** Locates the built debug APK. */
export async function findApk(): Promise<string | undefined> {
	const fixed = path.join(
		ROOT,
		"testapp",
		"build",
		"outputs",
		"apk",
		"debug",
		"testapp-debug.apk",
	);
	if (await pathExists(fixed)) {
		return fixed;
	}

	const matches = await glob("testapp/build/outputs/apk/debug/*.apk", {
		cwd: ROOT,
		absolute: true,
	});
	return matches[0];
}

async function pathExists(p: string): Promise<boolean> {
	try {
		await fs.access(p);
		return true;
	} catch {
		return false;
	}
}

export async function createTestContext(): Promise<TestContext> {
	const apkPath = await findApk();
	if (!apkPath) {
		throw new Error(
			"Built testapp-debug.apk not found — the vitest globalSetup build step must run first.",
		);
	}

	await ensureAppiumDrivers(["uiautomator2"]);
	const device = await ensureAndroidDevice();

	const server = await startAppiumServer(APPIUM_PORT);
	const fixtureRemote = await startFixtureRemote("10.0.2.2");

	const sessionTimeout = process.env.CI ? 720_000 : 360_000;

	console.log(
		`[device] installing ${path.basename(apkPath)} -> ${device.serial}`,
	);
	const driver = await remote({
		hostname: "127.0.0.1",
		port: server.port,
		path: "/",
		logLevel: "error",
		connectionRetryTimeout: sessionTimeout,
		connectionRetryCount: 0,
		capabilities: {
			platformName: "Android",
			"appium:automationName": "UiAutomator2",
			"appium:udid": device.serial,
			"appium:app": apkPath,
			"appium:appPackage": APP_PACKAGE,
			"appium:appActivity": APP_ACTIVITY,
			"appium:appWaitActivity": "*",
			"appium:optionalIntentArguments": `--es wvbRemoteUrl ${fixtureRemote.endpoint}`,
			// The testapp's versionCode is constant, so without a full reset Appium
			// skips reinstalling a rebuilt APK and keeps stale bundles/assets.
			"appium:fullReset": true,
			"appium:autoGrantPermissions": true,
			"appium:newCommandTimeout": 240,
			"appium:adbExecTimeout": 120_000,
			"appium:uiautomator2ServerInstallTimeout": 120_000,
			"appium:uiautomator2ServerLaunchTimeout": 120_000,
			// Requires the server's `chromedriver_autodownload` insecure feature
			// (see `startAppiumServer`).
			"appium:chromedriverAutodownload": true,
			"appium:ensureWebviewsHavePages": true,
			"appium:nativeWebScreenshot": true,
		},
	});

	await switchToWebview(driver);

	return { server, device, driver, fixtureRemote };
}

/** ms to wait for the testapp's WebView to expose a debuggable WEBVIEW context. */
const WEBVIEW_CONTEXT_TIMEOUT = process.env.CI ? 120_000 : 60_000;

/** Switches `driver` into the testapp's WEBVIEW context. */
async function switchToWebview(driver: Driver): Promise<void> {
	let contexts: string[] = [];
	await driver.waitUntil(
		async () => {
			contexts = ((await driver.getContexts()) ?? []).map(String);
			return contexts.some((c) => c.startsWith("WEBVIEW"));
		},
		{
			timeout: WEBVIEW_CONTEXT_TIMEOUT,
			interval: 1000,
			timeoutMsg: `no WEBVIEW context appeared (saw: ${contexts.join(", ") || "none"})`,
		},
	);

	const webview = contexts.find((c) => c.startsWith("WEBVIEW"));
	if (!webview) {
		throw new Error("WEBVIEW context disappeared after being detected");
	}
	console.log(`[driver] switching to webview context: ${webview}`);
	await driver.switchContext(webview);
}

export async function disposeTestContext(
	context: TestContext | undefined,
): Promise<void> {
	if (!context) {
		return;
	}
	await context.driver.deleteSession().catch(() => {});
	await context.server.stop();
	await context.fixtureRemote.close();
	if (context.device.bootedByUs && !process.env.WVB_E2E_KEEP) {
		await context.device.shutdown();
	}
}

let active: TestContext | undefined;

/** Set by `vitest.setup.ts`'s `beforeAll` so specs can reach the live context. */
export function setActiveContext(context: TestContext | undefined): void {
	active = context;
}

export function getTestContext(): TestContext {
	if (!active) {
		throw new Error(
			"Test context not started — vitest.setup.ts beforeAll did not run.",
		);
	}
	return active;
}
