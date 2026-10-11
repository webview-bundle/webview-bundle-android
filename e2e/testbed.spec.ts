import { createAppiumDriver } from "@wvb-playground/testdriver/appium";
import {
	defineTestbedSuite,
	defineTestbedUpdateSuite,
	type WebviewDriver,
} from "@wvb-playground/webview-testbed/testing";
import { beforeAll, describe } from "vitest";
import { getTestContext } from "./context.js";

describe("testbed UI (android)", () => {
	let driver: WebviewDriver;
	beforeAll(() => {
		driver = createAppiumDriver(getTestContext().driver, {
			baseURL: "https://testbed.wvb",
			defaultTimeoutMs: 30_000,
		});
	});

	defineTestbedUpdateSuite(
		() => driver,
		() => getTestContext().fixtureRemote,
	);
	defineTestbedSuite(() => driver);
});
