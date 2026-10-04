import { readFile } from "node:fs/promises";
import { readDashboardV1Contract, renderDashboardV1Contract } from "./dashboard-v1-generator.mjs";

const contract = await readDashboardV1Contract(new URL("../contracts/dashboard-v1.json", import.meta.url));
const expected = renderDashboardV1Contract(contract);
const generatedUrl = new URL("../src/dashboard/v1/generated.ts", import.meta.url);

let actual;
try {
  actual = await readFile(generatedUrl, "utf8");
} catch (error) {
  if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") {
    throw new Error("generated.ts不存在，请运行pnpm contract:generate", { cause: error });
  }
  throw error;
}

if (actual !== expected) {
  throw new Error("generated.ts与contracts/dashboard-v1.json不一致，请运行pnpm contract:generate");
}
