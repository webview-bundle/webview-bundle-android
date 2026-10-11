import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";

export async function startFixtureRemote(host: string) {
	const bundles = new Map(
		await Promise.all(
			["0.4.1", "0.4.2"].map(
				async (version) =>
					[
						version,
						await readFile(
							new URL(`./fixtures/${version}.wvb`, import.meta.url),
						),
					] as const,
			),
		),
	);
	let version = "0.4.1";
	const server = createServer((request, response) => {
		const path = new URL(request.url ?? "/", "http://localhost").pathname;
		if (path === "/update") {
			const etag = `"testbed-${version}"`;
			if (request.headers["if-none-match"] === etag) {
				response.writeHead(304).end();
				return;
			}
			const bytes = bundles.get(version)!;
			response.writeHead(200, { "content-type": "application/json", etag });
			response.end(
				JSON.stringify({
					id: `testbed-${version}`,
					createdAt: "2026-10-09T00:00:00Z",
					runtimeVersion: 1,
					bundles: [
						{
							name: "testbed",
							version,
							integrity: `sha256:${createHash("sha256").update(bytes).digest("base64")}`,
						},
					],
					metadata: { channel: "stable" },
				}),
			);
			return;
		}
		const match = /^\/bundles\/testbed\/([^/]+)$/.exec(path);
		const bytes = match ? bundles.get(match[1]!) : undefined;
		if (!bytes) {
			response.writeHead(404).end();
			return;
		}
		response.writeHead(200, {
			"content-type": "application/octet-stream",
			"content-length": bytes.length,
		});
		response.end(bytes);
	});
	await new Promise<void>((resolve, reject) => {
		server.once("error", reject);
		server.listen(0, "0.0.0.0", resolve);
	});
	return {
		endpoint: `http://${host}:${(server.address() as AddressInfo).port}`,
		setVersion(next: string) {
			if (!bundles.has(next))
				throw new Error(`Unknown fixture version: ${next}`);
			version = next;
		},
		close: () =>
			new Promise<void>((resolve, reject) =>
				server.close((error) => (error ? reject(error) : resolve())),
			),
	};
}
export type FixtureRemote = Awaited<ReturnType<typeof startFixtureRemote>>;
