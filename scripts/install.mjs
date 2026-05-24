#!/usr/bin/env node

//
// install.mjs — Install the WebViewBundle Android FFI bindings published by the
// webview-bundle/webview-bundle repository into this Gradle project.
//
// It fetches the GitHub release asset `android.zip` for a given tag and installs
// the Android variant (`lib-android`) into the `lib` module:
//   - the Kotlin bindings   -> lib/src/main/kotlin/...   (overwrite only —
//     existing files that are not part of the asset are kept)
//   - the JNI libraries     -> lib/src/main/jniLibs/...  (replaced wholesale,
//     so stale ABIs from a previous install are removed)
//
// `android.zip` is laid out as (see .output/android/ for a reference):
//   lib-android/src/main/jniLibs/<abi>/libwvb_ffi.so
//   lib-android/src/main/kotlin/dev/wvb/wvb_ffi.kt
//   lib-jvm/src/main/kotlin/dev/wvb/wvb_ffi.kt
//
// Tags follow the upstream convention:
//   - release:    ffi/<version>      e.g. ffi/0.1.0
//   - prerelease: prerelease/<sha>   e.g. prerelease/a3f693a
//
// Usage:
//   node scripts/install.mjs 0.1.0                 # -> tag ffi/0.1.0
//   node scripts/install.mjs ffi/0.1.0             # explicit release tag
//   node scripts/install.mjs prerelease/a3f693a    # explicit prerelease tag
//   node scripts/install.mjs --prerelease a3f693a  # -> tag prerelease/a3f693a
//   node scripts/install.mjs latest                # highest ffi/* release
//   node scripts/install.mjs --asset-file .output/android.zip   # local build
//
// Options:
//   --prerelease <sha>   Install the prerelease build for <sha> (tag prerelease/<sha>).
//   --repo <owner/repo>  Source repository (default: webview-bundle/webview-bundle).
//   --asset-file <path>  Install from a local android.zip instead of downloading
//                        a release (useful for locally-built artifacts).
//   -h, --help           Show this help.

import { execFileSync } from "node:child_process";
import {
	copyFileSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_DEFAULT = "webview-bundle/webview-bundle";
const ANDROID_ASSET = "android.zip";
// The Android library variant inside android.zip (ships Kotlin + jniLibs). The
// sibling `lib-jvm` variant is Kotlin-only and not used by this module.
const ANDROID_MODULE = "lib-android";
const USER_AGENT = "webview-bundle-android-install-ffi";

const ROOT_DIR = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const LIB_SRC_DIR = join(ROOT_DIR, "lib", "src");
const JNILIBS_DIR = join(LIB_SRC_DIR, "main", "jniLibs");

// --- logging ----------------------------------------------------------------

const color = process.stdout.isTTY
	? {
			blue: "\x1b[34m",
			green: "\x1b[32m",
			yellow: "\x1b[33m",
			dim: "\x1b[2m",
			reset: "\x1b[0m",
		}
	: { blue: "", green: "", yellow: "", dim: "", reset: "" };

const log = (msg) => console.log(`${color.blue}==>${color.reset} ${msg}`);
const ok = (msg) => console.log(`${color.green} ✓ ${msg}${color.reset}`);
const warn = (msg) => console.error(`${color.yellow} ! ${msg}${color.reset}`);
const dim = (s) => `${color.dim}${s}${color.reset}`;

class CliError extends Error {}
const die = (msg) => {
	throw new CliError(msg);
};

// --- argument parsing --------------------------------------------------------

function printHelp() {
	const lines = readFileSync(fileURLToPath(import.meta.url), "utf8").split(
		"\n",
	);
	const header = [];
	let started = false;
	for (const line of lines) {
		if (line.startsWith("#!")) continue; // skip shebang
		if (line.startsWith("//")) {
			started = true;
			header.push(line.replace(/^\/\/ ?/, ""));
		} else if (started) {
			break; // end of the leading comment block
		}
		// otherwise: blank line before the comment block — skip it
	}
	console.log(header.join("\n"));
}

function parseArgs(argv) {
	let ref = "";
	let prerelease = "";
	let repo = REPO_DEFAULT;
	let assetFile = "";

	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i];
		if (arg === "-h" || arg === "--help") {
			printHelp();
			process.exit(0);
		} else if (arg === "--repo") {
			repo = argv[++i] ?? die("--repo requires a value");
		} else if (arg === "--prerelease") {
			prerelease = argv[++i] ?? die("--prerelease requires a sha");
		} else if (arg === "--asset-file") {
			assetFile = argv[++i] ?? die("--asset-file requires a path");
		} else if (arg.startsWith("-")) {
			die(`unknown option: ${arg} (use --help)`);
		} else if (!ref) {
			ref = arg;
		} else {
			die(`unexpected extra argument: ${arg}`);
		}
	}
	return { ref, prerelease, repo, assetFile };
}

// --- github ------------------------------------------------------------------

function ghHeaders(extra = {}) {
	const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
	return {
		Accept: "application/vnd.github+json",
		"User-Agent": USER_AGENT,
		"X-GitHub-Api-Version": "2022-11-28",
		...(token ? { Authorization: `Bearer ${token}` } : {}),
		...extra,
	};
}

// Honors GITHUB_API_URL (set by GitHub Actions; also enables GitHub Enterprise).
const API_BASE = (
	process.env.GITHUB_API_URL || "https://api.github.com"
).replace(/\/+$/, "");

async function githubJson(path) {
	const res = await fetch(`${API_BASE}/${path}`, { headers: ghHeaders() });
	if (!res.ok) {
		throw new Error(`GitHub API /${path} -> ${res.status} ${res.statusText}`);
	}
	return res.json();
}

// Compare two dotted versions numerically (e.g. "0.10.0" > "0.2.0").
function compareVersions(a, b) {
	const pa = a.split(".").map(Number);
	const pb = b.split(".").map(Number);
	for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
		const diff = (pa[i] || 0) - (pb[i] || 0);
		if (diff !== 0) return diff;
	}
	return 0;
}

async function resolveLatestFfiTag(repo) {
	const releases = await githubJson(`repos/${repo}/releases?per_page=100`);
	const versions = releases
		.map((r) => r.tag_name)
		.filter((t) => typeof t === "string" && t.startsWith("ffi/"))
		.map((t) => t.slice("ffi/".length))
		.sort(compareVersions);
	const latest = versions.at(-1);
	return latest ? `ffi/${latest}` : "";
}

async function resolveTag({ ref, prerelease, repo }) {
	if (prerelease) {
		if (ref) die("pass either a positional ref or --prerelease, not both");
		return `prerelease/${prerelease}`;
	}
	if (!ref) {
		die(
			"a version or tag is required (e.g. '0.1.0', 'ffi/0.1.0', 'latest'). See --help.",
		);
	}
	if (ref === "latest") {
		const tag = await resolveLatestFfiTag(repo);
		return tag || die(`no ffi/* release found in ${repo}`);
	}
	if (ref.includes("/")) return ref; // already a fully-qualified tag
	return `ffi/${ref}`; // bare version -> ffi/<version>
}

async function getRelease(repo, tag) {
	// The tag may contain a slash; this endpoint accepts it verbatim.
	try {
		return await githubJson(`repos/${repo}/releases/tags/${tag}`);
	} catch (err) {
		die(`release '${tag}' not found in ${repo} (${err.message})`);
	}
}

async function fetchAsset(release, name) {
	const asset = release.assets?.find((a) => a.name === name);
	if (!asset) die(`asset '${name}' not found in release '${release.tag_name}'`);
	// Use the asset API url with octet-stream so this also works for private
	// repos; fetch follows the redirect to the CDN and drops the auth header
	// on the cross-origin hop.
	const res = await fetch(asset.url, {
		headers: ghHeaders({ Accept: "application/octet-stream" }),
		redirect: "follow",
	});
	if (!res.ok)
		die(`failed to download '${name}': ${res.status} ${res.statusText}`);
	return Buffer.from(await res.arrayBuffer());
}

// --- install steps -----------------------------------------------------------

function walkFiles(dir) {
	const out = [];
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		const full = join(dir, entry.name);
		if (entry.isDirectory()) out.push(...walkFiles(full));
		else if (entry.isFile()) out.push(full);
	}
	return out;
}

// Locate the `src/` directory of the Android variant inside the extracted
// android.zip (e.g. lib-android/src). BFS for the shallowest `lib-android`
// directory so an extra wrapping folder in the archive wouldn't break this.
function findAndroidSrcDir(root) {
	const queue = [root];
	while (queue.length > 0) {
		const dir = queue.shift();
		const subdirs = [];
		for (const entry of readdirSync(dir, { withFileTypes: true })) {
			if (!entry.isDirectory()) continue;
			const full = join(dir, entry.name);
			if (entry.name === ANDROID_MODULE) {
				const src = join(full, "src");
				if (existsSync(src)) return src;
			}
			subdirs.push(full);
		}
		queue.push(...subdirs);
	}
	return null;
}

function extractAsset(zipPath, destDir) {
	mkdirSync(destDir, { recursive: true });
	try {
		execFileSync("unzip", ["-q", "-o", zipPath, "-d", destDir]);
	} catch (err) {
		if (err.code === "ENOENT")
			die("'unzip' is required but was not found on PATH");
		die(`failed to unzip ${ANDROID_ASSET}: ${err.message}`);
	}
}

function installAndroidBindings(androidZipPath, tmpDir) {
	const extractDir = join(tmpDir, "android");
	extractAsset(androidZipPath, extractDir);

	const srcDir = findAndroidSrcDir(extractDir);
	if (!srcDir)
		die(`no '${ANDROID_MODULE}/src' directory found inside ${ANDROID_ASSET}`);

	const files = walkFiles(srcDir);
	if (files.length === 0)
		die(`no files found under '${ANDROID_MODULE}/src' in ${ANDROID_ASSET}`);

	// jniLibs is fully managed by this script (see .gitignore); wipe it so a
	// dropped or renamed ABI from a previous install does not linger.
	rmSync(JNILIBS_DIR, { recursive: true, force: true });

	// Mirror lib-android/src/<rel> into lib/src/<rel>, so e.g.
	//   main/kotlin/dev/wvb/wvb_ffi.kt  and  main/jniLibs/<abi>/libwvb_ffi.so
	// land in the right places. Kotlin files overwrite in place; any other
	// hand-written source under lib/src is left untouched.
	const installed = [];
	for (const file of files) {
		const rel = relative(srcDir, file); // path under src/
		const dest = join(LIB_SRC_DIR, rel);
		mkdirSync(dirname(dest), { recursive: true });
		copyFileSync(file, dest);
		installed.push(rel);
	}
	return installed;
}

// --- main --------------------------------------------------------------------

async function main() {
	const args = parseArgs(process.argv.slice(2));

	if (!existsSync(join(ROOT_DIR, "lib")))
		die(`'lib' module not found at ${join(ROOT_DIR, "lib")}`);

	let androidZip;
	let source;
	if (args.assetFile) {
		if (args.ref || args.prerelease)
			die("pass either a version/tag or --asset-file, not both");
		const path = resolve(args.assetFile);
		if (!existsSync(path)) die(`asset file not found: ${path}`);
		log(`Asset file  : ${dim(relative(ROOT_DIR, path) || path)}`);
		androidZip = readFileSync(path);
		source = relative(ROOT_DIR, path) || path;
		ok(`Loaded ${ANDROID_ASSET}`);
	} else {
		const tag = await resolveTag(args);
		log(`Source repo : ${dim(args.repo)}`);
		log(`Release tag  : ${dim(tag)}`);
		const release = await getRelease(args.repo, tag);
		log("Fetching release…");
		androidZip = await fetchAsset(release, ANDROID_ASSET);
		source = tag;
		ok(`Downloaded ${ANDROID_ASSET}`);
	}

	const tmpDir = mkdtempSync(join(tmpdir(), "install-ffi-"));
	try {
		const androidZipPath = join(tmpDir, ANDROID_ASSET);
		writeFileSync(androidZipPath, androidZip);

		log(
			`Installing Android bindings into ${dim(`${relative(ROOT_DIR, LIB_SRC_DIR)}/`)}`,
		);
		const installed = installAndroidBindings(androidZipPath, tmpDir);

		const kotlin = installed.filter((f) => f.endsWith(".kt"));
		const jniLibs = installed.filter((f) => f.endsWith(".so"));
		for (const name of [...kotlin, ...jniLibs]) console.log(`   - ${name}`);
		ok(
			`Installed ${kotlin.length} Kotlin file(s) and ${jniLibs.length} native librar(ies)`,
		);
	} finally {
		rmSync(tmpDir, { recursive: true, force: true });
	}

	log(`Done. Installed FFI bindings ${color.green}${source}${color.reset}.`);
}

main().catch((err) => {
	if (err instanceof CliError) {
		console.error(`error: ${err.message}`);
	} else {
		console.error(`error: ${err.message ?? err}`);
	}
	process.exit(1);
});
