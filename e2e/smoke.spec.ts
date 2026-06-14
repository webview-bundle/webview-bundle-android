import { createAppiumDriver } from "@wvb-playground/testing/appium";
import { testCases } from "@wvb-playground/webview-hacker-news/testing";
import { describe, test } from "vitest";
import { getTestContext } from "./context.js";

describe("hacker-news tests", () => {
	for (const testCase of testCases) {
		test(testCase.name, async () => {
			const { driver } = getTestContext();
			const webviewDriver = createAppiumDriver(driver, {
				baseURL: "https://hacker-news.wvb",
			});

			await testCase.run(webviewDriver);
		});
	}
});
