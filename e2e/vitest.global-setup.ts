import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function run(cmd: string, args: string[], cwd: string): void {
	console.log(`\n$ ${cmd} ${args.join(" ")}  (cwd: ${cwd})`);
	const res = spawnSync(cmd, args, { cwd, stdio: "inherit" });
	if (res.status !== 0) {
		throw new Error(
			`command failed (${res.status ?? res.signal}): ${cmd} ${args.join(" ")}`,
		);
	}
}

export default function setup(): void {
	const gradlew = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
	run(gradlew, [":testapp:assembleDebug"], ROOT);
}
