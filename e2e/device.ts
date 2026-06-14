import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { execa } from "execa";

export interface AndroidDevice {
	serial: string;
	name: string;
	/** Whether this process booted the emulator (callers decide whether to shut it down). */
	bootedByUs: boolean;
	shutdown: () => Promise<void>;
}

/** Locates the Android SDK from the env or the platform's default install path. */
function resolveSdk(): string {
	const fromEnv = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
	if (fromEnv && existsSync(fromEnv)) {
		return fromEnv;
	}
	const home = os.homedir();
	const candidates = [
		path.join(home, "Library/Android/sdk"), // macOS
		path.join(home, "Android/Sdk"), // Linux
		path.join(home, "AppData/Local/Android/Sdk"), // Windows
	];
	for (const c of candidates) {
		if (existsSync(c)) {
			return c;
		}
	}
	throw new Error(
		"Android SDK not found. Set ANDROID_HOME (or ANDROID_SDK_ROOT).",
	);
}

const SDK = resolveSdk();
const EXE = process.platform === "win32" ? ".exe" : "";
const ADB = path.join(SDK, "platform-tools", `adb${EXE}`);
const EMULATOR = path.join(SDK, "emulator", `emulator${EXE}`);

async function adb(
	args: string[],
	opts: Record<string, unknown> = {},
): Promise<{ stdout: string }> {
	const { stdout } = await execa(ADB, args, { reject: false, ...opts });
	return { stdout: stdout ?? "" };
}

/** Serials of devices currently in the `device` (online) state. */
async function listSerials(): Promise<string[]> {
	const { stdout } = await adb(["devices"]);
	return stdout
		.split("\n")
		.slice(1)
		.map((line) => line.trim())
		.filter(Boolean)
		.map((line) => line.split(/\s+/))
		.filter(([, state]) => state === "device")
		.map(([serial]) => serial as string);
}

async function isBooted(serial: string): Promise<boolean> {
	const { stdout } = await adb([
		"-s",
		serial,
		"shell",
		"getprop",
		"sys.boot_completed",
	]);
	return stdout.trim() === "1";
}

async function waitFor<T>(
	fn: () => Promise<T | undefined>,
	timeoutMs: number,
	intervalMs: number,
	message: string,
): Promise<T> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		const value = await fn();
		if (value) {
			return value;
		}
		await delay(intervalMs);
	}
	throw new Error(`${message} within ${timeoutMs}ms`);
}

async function pickAvd(): Promise<string> {
	const { stdout } = await execa(EMULATOR, ["-list-avds"], { reject: false });
	const avds = stdout
		.split("\n")
		.map((s) => s.trim())
		.filter(Boolean);
	if (avds.length === 0) {
		throw new Error(
			"No Android AVDs found. Create one (e.g. via Android Studio or `avdmanager`).",
		);
	}
	return avds[0]!;
}

async function bootEmulator(avd: string): Promise<AndroidDevice> {
	console.log(`[device] booting AVD: ${avd}`);
	const before = new Set(await listSerials());

	const headless = Boolean(process.env.CI || process.env.WVB_E2E_HEADLESS);
	const args = [
		"-avd",
		avd,
		"-no-snapshot",
		"-no-boot-anim",
		"-no-audio",
		"-gpu",
		"swiftshader_indirect",
	];
	if (headless) {
		args.push("-no-window");
	}
	const proc = execa(EMULATOR, args, { detached: true, stdio: "ignore" });
	proc.unref();
	proc.catch(() => {});

	const serial = await waitFor(
		async () => {
			const now = await listSerials();
			return (
				now.find((s) => !before.has(s)) ??
				now.find((s) => s.startsWith("emulator-"))
			);
		},
		120_000,
		1000,
		"emulator did not appear",
	);

	await adb(["-s", serial, "wait-for-device"]);
	await waitFor(
		async () => ((await isBooted(serial)) ? true : undefined),
		180_000,
		2000,
		"emulator did not finish booting",
	);
	console.log(`[device] booted ${serial}`);

	return {
		serial,
		name: avd,
		bootedByUs: true,
		shutdown: async () => {
			await adb(["-s", serial, "emu", "kill"]);
		},
	};
}

/**
 * Reuses an already-booted device/emulator, or boots an AVD (`ANDROID_AVD`, or the
 * first available) and waits for it to finish booting.
 */
export async function ensureAndroidDevice(
	avdName = process.env.ANDROID_AVD,
): Promise<AndroidDevice> {
	await adb(["start-server"]);

	for (const serial of await listSerials()) {
		if (await isBooted(serial)) {
			console.log(`[device] reusing running device: ${serial}`);
			return {
				serial,
				name: serial,
				bootedByUs: false,
				shutdown: async () => {},
			};
		}
	}

	return bootEmulator(avdName ?? (await pickAvd()));
}
