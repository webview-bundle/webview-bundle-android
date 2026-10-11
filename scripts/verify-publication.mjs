#!/usr/bin/env node

// Run after :lib:assembleRelease :lib:generatePomFileForMavenPublication
// :lib:generateMetadataFileForMavenPublication. No registry credentials are needed.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
	mkdtempSync,
	readFileSync,
	readdirSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const build = join(root, "lib/build");
const aar = join(build, "outputs/aar/lib-release.aar");
const publication = join(build, "publications/maven");
const entries = (file) =>
	execFileSync("unzip", ["-Z1", file], { encoding: "utf8" }).trim().split("\n");
const readEntry = (file, entry) =>
	execFileSync("unzip", ["-p", file, entry], { maxBuffer: 64 * 1024 * 1024 });

const pom = readFileSync(join(publication, "pom-default.xml"), "utf8");
assert.doesNotMatch(pom, /<groupId>\s*org\.rustls\s*<\/groupId>/);
const metadata = JSON.parse(
	readFileSync(join(publication, "module.json"), "utf8"),
);
for (const variant of metadata.variants) {
	assert.ok(
		!variant.dependencies?.some((dep) => dep.group === "org.rustls"),
		variant.name,
	);
}

const manifest = readEntry(aar, "AndroidManifest.xml").toString();
assert.match(manifest, /android\.permission\.INTERNET/);
assert.match(
	manifest,
	/android:networkSecurityConfig="@xml\/network_security_config"/,
);
assert.match(manifest, /package="dev\.wvb"/);
assert.match(
	readEntry(aar, "proguard.txt").toString(),
	/-keep,\s*includedescriptorclasses class org\.rustls\.platformverifier\.\*\*/,
);

// Compare every embedded resource against the resolved upstream artifact, not a duplicated fixture.
function verifyResources(dir, prefix = "res") {
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		const name = `${prefix}/${entry.name}`;
		if (entry.isDirectory()) verifyResources(join(dir, entry.name), name);
		else
			assert.deepEqual(
				readEntry(aar, name),
				readFileSync(join(dir, entry.name)),
				name,
			);
	}
}
verifyResources(join(build, "generated/rustls/res"));

const temp = mkdtempSync(join(tmpdir(), "wvb-publication-"));
try {
	const packaged = new Map();
	for (const [index, name] of entries(aar)
		.filter((name) => name.endsWith(".jar"))
		.entries()) {
		const jar = join(temp, `${index}.jar`);
		writeFileSync(jar, readEntry(aar, name));
		for (const entry of entries(jar)) {
			if (
				!(entry.startsWith("org/rustls/") && entry.endsWith(".class")) &&
				!entry.includes("rustls-platform-verifier-LICENSE")
			)
				continue;
			assert.ok(
				!packaged.has(entry),
				`Duplicate packaged class/resource: ${entry}`,
			);
			packaged.set(entry, readEntry(jar, entry));
		}
	}
	const upstream = join(build, "generated/rustls/classes.jar");
	const verifierClasses = entries(upstream).filter((name) =>
		name.endsWith(".class"),
	);
	assert.ok(
		verifierClasses.includes(
			"org/rustls/platformverifier/CertificateVerifier.class",
		),
	);
	for (const entry of verifierClasses) {
		assert.deepEqual(packaged.get(entry), readEntry(upstream, entry), entry);
	}
	assert.deepEqual(
		packaged.get("META-INF/rustls-platform-verifier-LICENSE-MIT"),
		readFileSync(
			join(
				root,
				"lib/src/main/resources/META-INF/rustls-platform-verifier-LICENSE-MIT",
			),
		),
	);
} finally {
	rmSync(temp, { recursive: true, force: true });
}
console.log(
	"Publication verified: embedded verifier classes/resources/manifest/license/keep rules, no org.rustls dependency.",
);
