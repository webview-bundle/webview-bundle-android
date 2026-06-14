import { afterAll, beforeAll } from "vitest";
import {
	createTestContext,
	disposeTestContext,
	setActiveContext,
	type TestContext,
} from "./context.js";

let context: TestContext | undefined;

beforeAll(async () => {
	context = await createTestContext();
	setActiveContext(context);
});

afterAll(async () => {
	await disposeTestContext(context);
	setActiveContext(undefined);
});
