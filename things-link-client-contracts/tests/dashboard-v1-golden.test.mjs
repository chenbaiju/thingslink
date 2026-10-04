import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { isAbsolute, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import {
  DASHBOARD_SCHEMA_SCOPE,
  DashboardContractViolation,
  dashboardV1Contract,
  validateDashboardSchemaV1,
} from "@things-link/client-contracts/dashboard/v1";

const corpusRoot = fileURLToPath(new URL("../contracts/dashboard-v1-golden/", import.meta.url));
const manifest = JSON.parse(await readFile(resolve(corpusRoot, "manifest.json"), "utf8"));

function exactKeys(value, expected, path) {
  assert.deepEqual(Object.keys(value).sort(), [...expected].sort(), path);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function loadInput(input) {
  if (input.kind === "FILE") {
    const path = resolve(corpusRoot, input.path);
    assert.equal(isAbsolute(input.path), false, input.path);
    assert.equal(relative(resolve(corpusRoot, "inputs"), path).startsWith(".."), false, input.path);
    return readFile(path);
  }
  assert.equal(input.kind, "REPEAT_BYTE");
  assert.equal(Number.isInteger(input.byte) && input.byte >= 0 && input.byte <= 255, true);
  assert.equal(Number.isInteger(input.count) && input.count >= 0 && input.count <= 1_000_000, true);
  return new Uint8Array(input.count).fill(input.byte);
}

function comparableRequirements(requirements) {
  const values = JSON.parse(JSON.stringify(requirements));
  for (const requirement of values) {
    if (requirement.requirementType === "MODEL_PROPERTY") requirement.allowedDataTypes.sort();
  }
  return values;
}

test("黄金清单维持封闭格式、唯一案例及完整拒绝原因", async () => {
  exactKeys(manifest, ["formatVersion", "hashAlgorithm", "schemaVersion", "cases"], "manifest字段");
  assert.equal(manifest.formatVersion, "tc.dashboard-golden-corpus/v1");
  assert.equal(manifest.hashAlgorithm, "SHA-256");
  assert.equal(manifest.schemaVersion, "tc.dashboard/v1");

  const ids = new Set();
  const parseReasons = new Set();
  const validationReasons = new Set();
  const acceptedKinds = new Set();
  const acceptedIds = new Set();
  const requirementTypes = new Set();
  let accepted = 0;
  for (const corpusCase of manifest.cases) {
    exactKeys(corpusCase, ["id", "input", "expected"], `${corpusCase.id}.字段`);
    assert.equal(typeof corpusCase.id === "string" && corpusCase.id.length > 0, true);
    assert.equal(ids.has(corpusCase.id), false, corpusCase.id);
    ids.add(corpusCase.id);
    exactKeys(
      corpusCase.input,
      corpusCase.input.kind === "FILE" ? ["kind", "path", "sha256"] : ["kind", "byte", "count", "sha256"],
      `${corpusCase.id}.input字段`,
    );
    const bytes = await loadInput(corpusCase.input);
    assert.equal(sha256(bytes), corpusCase.input.sha256, `${corpusCase.id}.input.sha256`);

    if (corpusCase.expected.outcome === "ACCEPT") {
      accepted += 1;
      acceptedIds.add(corpusCase.id);
      exactKeys(
        corpusCase.expected,
        ["outcome", "scope", "normalizedUtf8", "normalizedBytes", "normalizedSha256", "unresolvedRequirements"],
        `${corpusCase.id}.expected字段`,
      );
      const acceptedRoot = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
      acceptedRoot.pages.forEach((page) => page.components.forEach((component) => acceptedKinds.add(component.kind)));
      corpusCase.expected.unresolvedRequirements.forEach(({ requirementType }) => requirementTypes.add(requirementType));
    } else {
      assert.equal(corpusCase.expected.outcome, "REJECT", `${corpusCase.id}.expected.outcome`);
      exactKeys(corpusCase.expected, ["outcome", "phase", "reason", "path"], `${corpusCase.id}.expected字段`);
      assert.equal(["PARSE", "VALIDATION"].includes(corpusCase.expected.phase), true, `${corpusCase.id}.expected.phase`);
      assert.equal(corpusCase.expected.path.startsWith("$"), true, `${corpusCase.id}.expected.path`);
      (corpusCase.expected.phase === "PARSE" ? parseReasons : validationReasons).add(corpusCase.expected.reason);
    }
  }
  assert.equal(accepted > 0, true);
  assert.deepEqual([...acceptedIds].sort(), [
    "accept_external_requirements",
    "accept_minimal_text",
    "accept_number_normalization",
  ]);
  assert.deepEqual([...parseReasons].sort(), [...dashboardV1Contract.rejectionReasons.parse].sort());
  assert.deepEqual([...validationReasons].sort(), [...dashboardV1Contract.rejectionReasons.validation].sort());
  assert.deepEqual([...acceptedKinds].sort(), [...new Set(dashboardV1Contract.components.map(({ kind }) => kind))].sort());
  assert.deepEqual([...requirementTypes].sort(), [
    "BUILTIN_RESOURCE",
    "DATA_ADAPTER",
    "DEFAULT_DEVICE",
    "HISTORICAL_PROPERTY",
    "HOST_COMPONENT",
    "MODEL_GAUGE_RANGE",
    "MODEL_PROPERTY",
    "MODEL_REFERENCE",
  ]);
});

for (const corpusCase of manifest.cases) {
  test(`TypeScript结果匹配共享黄金期望: ${corpusCase.id}`, async () => {
    const source = await loadInput(corpusCase.input);
    if (corpusCase.expected.outcome === "ACCEPT") {
      const result = validateDashboardSchemaV1(source);
      assert.equal(result.scope, DASHBOARD_SCHEMA_SCOPE);
      assert.equal(result.scope, corpusCase.expected.scope);
      assert.equal(result.normalizedJson, corpusCase.expected.normalizedUtf8);
      assert.deepEqual(JSON.parse(JSON.stringify(result.schema)), JSON.parse(corpusCase.expected.normalizedUtf8));
      const normalized = new TextEncoder().encode(result.normalizedJson);
      assert.equal(normalized.byteLength, corpusCase.expected.normalizedBytes);
      assert.equal(sha256(normalized), corpusCase.expected.normalizedSha256);
      assert.deepEqual(
        comparableRequirements(result.unresolvedRequirements),
        corpusCase.expected.unresolvedRequirements,
      );
      return;
    }

    assert.throws(
      () => validateDashboardSchemaV1(source),
      (error) => error instanceof DashboardContractViolation
        && error.reason === corpusCase.expected.reason
        && error.path === corpusCase.expected.path,
    );
  });
}
