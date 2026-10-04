import { mkdir, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { readDashboardV1Contract, renderDashboardV1Contract } from "./dashboard-v1-generator.mjs";

const contractUrl = new URL("../contracts/dashboard-v1.json", import.meta.url);
const outputUrl = new URL("../src/dashboard/v1/generated.ts", import.meta.url);
const contract = await readDashboardV1Contract(contractUrl);
const output = renderDashboardV1Contract(contract);

await mkdir(dirname(fileURLToPath(outputUrl)), { recursive: true });
await writeFile(outputUrl, output, "utf8");
