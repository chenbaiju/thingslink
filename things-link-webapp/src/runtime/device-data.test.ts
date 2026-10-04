import { describe, expect, it, vi } from 'vitest';
import { validateDashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1';
import { assertDeviceDashboardSupport, alarmSubscription, loadDirtyAlarms, currentSubscription, loadDeviceCurrent, loadDeviceCatalog, loadDevicePage, loadAlarmPage, planDevicePage, type DeviceReadContext } from './device-data';
const modelId = '11111111-1111-4111-8111-111111111111';
const deviceId = '22222222-2222-4222-8222-222222222222';
const appId = '33333333-3333-4333-8333-333333333333';
const dashboardId = '44444444-4444-4444-8444-444444444444';
const userId = '55555555-5555-4555-8555-555555555555';
const digest = 'a'.repeat(64);
function definition() {
  return { schemaVersion: 'tc.dashboard/v1', presentation: { mode: 'RESPONSIVE_GRID' },
    models: [{ key: 'pump', versionId: modelId, digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', digest, profile: 'TC_PROPERTY_COMPOSITE_V1' }],
    variables: [{ key: 'selected', type: 'DEVICE_SINGLE', title: '设备', modelKey: 'pump' }],
    pages: [{ id: 'main', title: '首页', components: [
      { id: 'selector', kind: 'DEVICE_SELECTOR', componentVersion: '1.0.0', layout: { x: 0, y: 0, w: 24, h: 8 }, props: {}, bindings: { directory: { source: 'DEVICE_DIRECTORY', variableKey: 'selected' } } },
      { id: 'card', kind: 'VALUE_CARD', componentVersion: '1.0.0', layout: { x: 0, y: 8, w: 24, h: 8 }, props: {}, bindings: { value: { source: 'CURRENT_VALUE', device: { variableKey: 'selected' }, propertyKey: 'temperature' } } },
    ] }] };
}
function fixture() {
  const raw = definition();
  const schema = validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify(raw))).schema;
  const fetch = vi.fn<DeviceReadContext['session']['fetch']>();
  const checkCurrent = vi.fn(() => ({ status: 'authenticated' as const, appUserId: userId, projectId: appId }));
  const context: DeviceReadContext = {
    session: { fetch, checkCurrent },
    current: { identity: { kind: 'APP', appUserId: userId, projectId: appId },
      application: { id: appId, appKey: `app_${'a'.repeat(32)}`, displayName: '应用' },
      publicationRevision: '9007199254740993', applicationVersionId: appId, applicationVersionNumber: '1', applicationFormatVersion: 'tc.application/v1',
      hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '1.0.1' }, entryDashboardId: dashboardId,
      dashboards: [{ dashboardId, dashboardVersionId: dashboardId, dashboardVersionNumber: '1', title: '看板', schemaVersion: 'tc.dashboard/v1', schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', schemaDigest: digest, pages: [{ id: 'main', title: '首页' }] }] },
    published: { applicationVersionId: appId, publicationRevision: '9007199254740993', dashboardId, dashboardVersionId: dashboardId, dashboardVersionNumber: '1', schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', schemaDigest: digest, schema },
  };
  const snapshots = { devices: [{ deviceId, status: 'AVAILABLE', name: '水泵', deviceStatus: 'ONLINE', lastOnlineAt: null, currentModelVersionId: modelId }],
    models: [{ versionId: modelId, digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', digest, profile: 'TC_PROPERTY_COMPOSITE_V1', properties: [
      { propertyKey: 'temperature', dataType: 'NUMBER', unit: '℃', minimumValue: null, maximumValue: null, enumOptions: null, onLabel: null, offLabel: null },
    ] }] };
  const current = { devices: [{ deviceId, status: 'AVAILABLE', values: [{ propertyKey: 'temperature', state: 'VALUE', value: 0, occurredAt: '2026-09-07T00:00:00Z', reportedModelVersionId: modelId }] }] };
  fetch.mockImplementation(async path => Response.json(path.includes('snapshots') ? snapshots : current));
  return { raw, schema, context, fetch, checkCurrent, snapshots, current, plan: planDevicePage(schema, 'main', { selected: deviceId }) };
}
describe('single-device published data plans', () => {
  it('requires an explicit choice and never invents a first catalog device', async () => {
    const f = fixture(); const plan = planDevicePage(f.schema, 'main', {});
    expect(plan.missingRequired).toEqual(['selected']);
    expect((await loadDevicePage({ ...f.context, plan })).devices).toEqual([]);
    expect(f.fetch).not.toHaveBeenCalled();
  });
  it('deduplicates component dependencies and sends all four exact runtime headers', async () => {
    const f = fixture(); const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    expect(f.plan.devices).toEqual([{ deviceId, expectedModelVersionId: modelId, propertyKeys: ['temperature'] }]);
    expect(f.fetch).toHaveBeenCalledTimes(2);
    for (const [, init] of f.fetch.mock.calls) {
      expect(init?.method).toBe('POST');
      expect(init?.headers).toMatchObject({ 'X-Application-Key': `app_${'a'.repeat(32)}`, 'X-Application-Version': appId,
        'X-Application-Revision': '9007199254740993', 'X-Dashboard-Version': dashboardId });
    }
    expect(facts.current[0]!.values[0]).toMatchObject({ state: 'VALUE', value: { kind: 'NUMBER', lexical: '0' } });
    expect(Object.isFrozen(facts.current[0]!.values)).toBe(true);
  });
  it('retains decimal precision beyond JS safe integers and accepts runtime exponents outside ConfigNumber', async () => {
    const f = fixture();
    f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
      : new Response(JSON.stringify(f.current).replace('"value":0', '"value":9007199254740993.123456789')));
    const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    expect(facts.current[0]!.values[0]).toMatchObject({ value: { kind: 'NUMBER', lexical: '9007199254740993.123456789' } });
    f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
      : new Response(JSON.stringify(f.current).replace('"value":0', '"value":1e-1000')));
    expect((await loadDevicePage({ ...f.context, plan: f.plan })).current[0]!.values[0]).toMatchObject({ value: { lexical: '1e-1000' } });
  });
  it('preserves false as SWITCH VALUE, never treating it as missing', async () => {
    const f = fixture(); f.snapshots.models[0]!.properties[0]!.dataType = 'SWITCH';
    f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
      : new Response(JSON.stringify(f.current).replace('"value":0', '"value":false')));
    expect((await loadDevicePage({ ...f.context, plan: f.plan })).current[0]!.values[0]).toMatchObject({ state: 'VALUE', value: false });
  });
  it.each(['NO_VALUE', 'SOURCE_VERSION_UNKNOWN', 'SOURCE_MODEL_MISMATCH', 'CONTRACT_MISMATCH'])(
    'keeps %s distinct without value or fabricated time', async state => {
      const f = fixture(); f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
        : Response.json({ devices: [{ deviceId, status: 'AVAILABLE', values: [{ propertyKey: 'temperature', state }] }] }));
      expect((await loadDevicePage({ ...f.context, plan: f.plan })).current[0]!.values[0]).toEqual({ propertyKey: 'temperature', state });
    });
  it('does not query values or expose model metadata for NOT_AVAILABLE devices', async () => {
    const f = fixture(); f.fetch.mockResolvedValue(Response.json({ devices: [{ deviceId, status: 'NOT_AVAILABLE' }], models: [] }));
    const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    expect(facts.devices).toEqual([{ deviceId, status: 'NOT_AVAILABLE' }]); expect(facts.current).toEqual([]);
    expect(f.fetch).toHaveBeenCalledTimes(1);
  });
  it('keeps MODEL_MISMATCH including null distinct, and rejects leaked unavailable facts', async () => {
    const f = fixture(); f.fetch.mockResolvedValue(Response.json({ devices: [{ deviceId, status: 'MODEL_MISMATCH', currentModelVersionId: null }], models: [] }));
    expect((await loadDevicePage({ ...f.context, plan: f.plan })).devices[0]).toMatchObject({ status: 'MODEL_MISMATCH', currentModelVersionId: null });
    f.fetch.mockResolvedValue(Response.json({ devices: [{ deviceId, status: 'NOT_AVAILABLE', name: '秘密' }], models: [] }));
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'response' });
  });
  it('requires exact successful-model property projection and matching immutable digest', async () => {
    const f = fixture(); f.snapshots.models[0]!.digest = 'b'.repeat(64);
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'response' });
    const g = fixture(); g.snapshots.models[0]!.properties.push({ ...g.snapshots.models[0]!.properties[0]!, propertyKey: 'hidden' });
    await expect(loadDevicePage({ ...g.context, plan: g.plan })).rejects.toMatchObject({ reason: 'response' });
  });
  it('rejects changed source identity, missing requested values and malformed timestamps', async () => {
    for (const variant of ['source', 'missing', 'time']) {
      const f = fixture();
      if (variant === 'source') f.current.devices[0]!.values[0]!.reportedModelVersionId = deviceId;
      if (variant === 'missing') f.current.devices[0]!.values = [];
      if (variant === 'time') f.current.devices[0]!.values[0]!.occurredAt = '2026-02-30T00:00:00Z';
      await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'response' });
    }
  });
  it('rejects duplicate JSON and a forged exact-number object', async () => {
    const f = fixture();
    f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
      : new Response(JSON.stringify(f.current).replace('"value":0', '"value":0,"value":1')));
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'response' });
    f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
      : new Response(JSON.stringify(f.current).replace('"value":0', '"value":{"type":"JSON_NUMBER","lexical":"0"}')));
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'response' });
  });
  it('does not derive revocation or alter selection from an empty catalog page', async () => {
    const f = fixture(); f.fetch.mockResolvedValue(Response.json({ items: [], nextCursor: null, hasMore: false }));
    expect(await loadDeviceCatalog({ ...f.context, variableKey: 'selected', limit: 3 })).toEqual({ items: [], nextCursor: null, hasMore: false });
    expect(f.plan.selections.selected).toBe(deviceId); expect(f.fetch).toHaveBeenCalledTimes(1);
    expect(f.fetch.mock.calls[0]![0]).toContain(`modelVersionId=${modelId}&limit=3`);
  });
  it('rejects catalog model mismatch and never follows nextCursor automatically', async () => {
    const f = fixture(); f.fetch.mockResolvedValue(Response.json({ items: [{ deviceId, name: '设备', deviceStatus: 'ONLINE', currentModelVersionId: modelId }], nextCursor: 'opaque', hasMore: true }));
    expect((await loadDeviceCatalog({ ...f.context, variableKey: 'selected' })).nextCursor).toBe('opaque'); expect(f.fetch).toHaveBeenCalledTimes(1);
    f.fetch.mockResolvedValue(Response.json({ items: [{ deviceId, name: '设备', deviceStatus: 'ONLINE', currentModelVersionId: appId }], nextCursor: null, hasMore: false }));
    await expect(loadDeviceCatalog({ ...f.context, variableKey: 'selected' })).rejects.toMatchObject({ reason: 'response' });
  });
  it('rejects fifteen distinct pageSize catalog requests even when all selectors share one variable', () => {
    const raw = definition();
    raw.variables = [{ key: 'selected', type: 'DEVICE_SINGLE', title: '设备', modelKey: 'pump' }];
    const selectors = Array.from({ length: 15 }, (_, index) => ({ id: `selector_${index}`, kind: 'DEVICE_SELECTOR', componentVersion: '1.0.0',
      layout: { x: 0, y: index * 8, w: 24, h: 8 }, props: { pageSize: index + 1 }, bindings: { directory: { source: 'DEVICE_DIRECTORY', variableKey: 'selected' } } }));
    const card = { id: 'card', kind: 'VALUE_CARD', componentVersion: '1.0.0', layout: { x: 0, y: 120, w: 24, h: 8 }, props: {},
      bindings: { value: { source: 'CURRENT_VALUE', device: { variableKey: 'selected' }, propertyKey: 'temperature' } } };
    const schema = validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify({ ...raw, pages: [{ id: 'main', title: '首页', components: [...selectors, card] }] }))).schema;
    expect(() => assertDeviceDashboardSupport(schema)).toThrow();
    const merged = validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify({ ...raw, pages: [{ id: 'main', title: '首页',
      components: [...selectors.map(selector => ({ ...selector, props: { pageSize: 20 } })), card] }] }))).schema;
    expect(() => assertDeviceDashboardSupport(merged)).not.toThrow();
  });
  it('stops before HTTP for a stale application revision or changed App identity', async () => {
    const f = fixture(); f.context.current.publicationRevision = '2';
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'identity' }); expect(f.fetch).not.toHaveBeenCalled();
    const g = fixture(); g.checkCurrent.mockReturnValue({ status: 'authenticated', appUserId: appId, projectId: appId });
    await expect(loadDevicePage({ ...g.context, plan: g.plan })).rejects.toMatchObject({ reason: 'identity' }); expect(g.fetch).not.toHaveBeenCalled();
  });
  it('checks whole-dashboard candidate support before a page can hide unsupported behavior', () => {
    const f = fixture(); expect(() => assertDeviceDashboardSupport(f.schema)).not.toThrow();
    const raw = definition(); raw.variables[0]!.type = 'DEVICE_MULTI';
    // 单选槽配MULTI连共享完整语义也不合法，生产不放宽此规则。
    expect(() => validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify(raw)))).toThrow();
  });
});

function multiDefinition(maxItems = 2, propertyKeys = ['temperature']) {
  return {
    schemaVersion: 'tc.dashboard/v1', presentation: { mode: 'RESPONSIVE_GRID' },
    models: [{ key: 'pump', versionId: modelId, digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', digest, profile: 'TC_PROPERTY_COMPOSITE_V1' }],
    variables: [{ key: 'selected', type: 'DEVICE_MULTI', title: '设备', modelKey: 'pump', maxItems }],
    pages: [{ id: 'main', title: '首页', components: [{ id: 'table', kind: 'TABLE', componentVersion: '1.0.0',
      layout: { x: 0, y: 0, w: 24, h: 8 }, props: { mode: 'DEVICE_VALUES', rowLimit: 1, columns: propertyKeys.map((key, index) => ({ id: `col_${index}`, label: key })) },
      bindings: { columns: propertyKeys.map((propertyKey, index) => ({ id: `col_${index}`, value: { source: 'CURRENT_VALUE', device: { variableKey: 'selected' }, propertyKey } })) },
    }] }],
  };
}
function validated(source: unknown) { return validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify(source))).schema; }
const secondDevice = '66666666-6666-4666-8666-666666666666';
describe('multi-device scalar table plans', () => {
  it('keeps all selected rows in the plan even when rowLimit is one local display row', () => {
    const schema = validated(multiDefinition());
    const plan = planDevicePage(schema, 'main', { selected: [deviceId, secondDevice] });
    expect(plan.selections.selected).toEqual([deviceId, secondDevice]);
    expect(plan.devices.map(device => device.deviceId)).toEqual([deviceId, secondDevice]);
    expect(plan.bindings.map(binding => [binding.columnId, binding.deviceId])).toEqual([['col_0', deviceId], ['col_0', secondDevice]]);
    expect(() => assertDeviceDashboardSupport(schema)).not.toThrow();
  });
  it('requires an array with unique canonical IDs within maxItems and does not silently trim', () => {
    const schema = validated(multiDefinition(1));
    for (const selection of [[deviceId, deviceId], [deviceId, secondDevice], ['bad'], null, deviceId]) {
      expect(() => planDevicePage(schema, 'main', { selected: selection })).toThrow();
    }
    expect(planDevicePage(schema, 'main', { selected: [] }).missingRequired).toEqual(['selected']);
  });
  it('uses default arrays only for omitted selection and allows explicit empty selection', () => {
    const source = multiDefinition();
    const schema = validated({ ...source, variables: [{ ...source.variables[0], defaultDeviceIds: [deviceId] }] });
    expect(planDevicePage(schema, 'main', {}).selections.selected).toEqual([deviceId]);
    expect(planDevicePage(schema, 'main', { selected: [] }).devices).toEqual([]);
  });
  it('deduplicates the same selected device across variables without merging variable identity', () => {
    const source = multiDefinition();
    const other = { ...source.pages[0]!.components[0]!, id: 'other_table', layout: { x: 0, y: 8, w: 24, h: 8 },
      bindings: { columns: [{ id: 'col_0', value: { source: 'CURRENT_VALUE', device: { variableKey: 'other' }, propertyKey: 'pressure' } }] } };
    const schema = validated({ ...source, variables: [...source.variables, { ...source.variables[0], key: 'other' }],
      pages: [{ ...source.pages[0], components: [...source.pages[0]!.components, other] }] });
    const plan = planDevicePage(schema, 'main', { selected: [deviceId], other: [deviceId] });
    expect(plan.devices).toEqual([{ deviceId, expectedModelVersionId: modelId, propertyKeys: ['temperature', 'pressure'] }]);
    expect(plan.bindings.map(binding => binding.variableKey)).toEqual(['selected', 'other']);
    const mismatch = validated({ ...source, models: [...source.models, { ...source.models[0], key: 'other_model', versionId: appId }],
      variables: [...source.variables, { ...source.variables[0], key: 'other', modelKey: 'other_model' }],
      pages: [{ ...source.pages[0], components: [...source.pages[0]!.components, other] }] });
    expect(() => planDevicePage(mismatch, 'main', { selected: [deviceId], other: [deviceId] })).toThrow();
  });
  it('budgets conservative maxItems rather than coincident empty/default selections', () => {
    const source = multiDefinition(20, Array.from({ length: 10 }, (_, index) => `key_${index}`));
    expect(() => assertDeviceDashboardSupport(validated(source))).not.toThrow();
    const extra = { ...source.pages[0]!.components[0]!, id: 'extra', layout: { x: 0, y: 8, w: 24, h: 8 },
      props: { mode: 'DEVICE_VALUES', rowLimit: 1, columns: [{ id: 'extra_col', label: '额外' }] },
      bindings: { columns: [{ id: 'extra_col', value: { source: 'CURRENT_VALUE', device: { variableKey: 'selected' }, propertyKey: 'extra_key' } }] } };
    const tooManyKeys = validated({ ...source, pages: [{ ...source.pages[0], components: [...source.pages[0]!.components, extra] }] });
    expect(() => assertDeviceDashboardSupport(tooManyKeys)).toThrow();
    const another = { ...extra, bindings: { columns: [{ id: 'extra_col', value: { source: 'CURRENT_VALUE', device: { variableKey: 'other' }, propertyKey: 'temperature' } }] } };
    const tooManyDevices = validated({ ...source, variables: [...source.variables, { ...source.variables[0], key: 'other', maxItems: 1 }],
      pages: [{ ...source.pages[0], components: [...source.pages[0]!.components, another] }] });
    expect(() => assertDeviceDashboardSupport(tooManyDevices)).toThrow();
  });
  it('loads a multi selection in one snapshots batch and never queries values for an unavailable row', async () => {
    const f = fixture(); const schema = validated(multiDefinition());
    const context = { ...f.context, published: { ...f.context.published, schema } };
    const plan = planDevicePage(schema, 'main', { selected: [deviceId, secondDevice] });
    const snapshots = { ...f.snapshots, devices: [...f.snapshots.devices, { deviceId: secondDevice, status: 'NOT_AVAILABLE' }] };
    f.fetch.mockImplementation(async path => Response.json(path.includes('snapshots') ? snapshots : f.current));
    const facts = await loadDevicePage({ ...context, plan });
    expect(facts.devices.map(device => device.status)).toEqual(['AVAILABLE', 'NOT_AVAILABLE']);
    expect(facts.current).toHaveLength(1);
    expect(JSON.parse(f.fetch.mock.calls[0]![1]!.body as string).devices).toHaveLength(2);
    expect(JSON.parse(f.fetch.mock.calls[1]![1]!.body as string).devices).toHaveLength(1);
    expect(f.fetch).toHaveBeenCalledTimes(2);
  });
  it('rejects a TABLE composite property before requesting current values', async () => {
    const f = fixture(); const schema = validated(multiDefinition());
    f.snapshots.models[0]!.properties[0]!.dataType = 'OBJECT';
    await expect(loadDevicePage({ ...f.context, published: { ...f.context.published, schema }, plan: planDevicePage(schema, 'main', { selected: [deviceId] }) })).rejects.toMatchObject({ reason: 'plan' });
    expect(f.fetch).toHaveBeenCalledTimes(1);
  });
  it('accepts multi-variable catalog filtering as a single page without selecting returned members', async () => {
    const f = fixture(); const schema = validated(multiDefinition());
    f.fetch.mockResolvedValue(Response.json({ items: [], hasMore: false, nextCursor: null }));
    await loadDeviceCatalog({ ...f.context, published: { ...f.context.published, schema }, variableKey: 'selected' });
    expect(planDevicePage(schema, 'main', {}).selections.selected).toEqual([]);
    expect(f.fetch).toHaveBeenCalledTimes(1);
  });
});

describe('single-property composite component data', () => {
  function compositeFixture(kind: 'JSON_VIEW' | 'TABLE' = 'JSON_VIEW', dataType: 'OBJECT' | 'LIST' = 'OBJECT') {
    const f = fixture();
    const schema = validated({ ...f.raw, pages: [{ id: 'main', title: '首页', components: [{
      id: 'composite', kind, componentVersion: '1.0.0', layout: { x: 0, y: 0, w: 24, h: 8 },
      props: kind === 'TABLE' ? { mode: 'LIST_VALUE', rowLimit: 1 } : {},
      bindings: { value: { source: 'CURRENT_VALUE', device: { variableKey: 'selected' }, propertyKey: 'temperature' } },
    }] }] });
    f.snapshots.models[0]!.properties[0]!.dataType = dataType;
    return { ...f, schema, context: { ...f.context, published: { ...f.context.published, schema } }, plan: planDevicePage(schema, 'main', { selected: deviceId }) };
  }
  it('reads an entire LIST_VALUE as one property regardless of local rowLimit', async () => {
    const f = compositeFixture('TABLE', 'LIST');
    f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
      : new Response(JSON.stringify(f.current).replace('"value":0', '"value":[0,9007199254740993,1e-1000]')));
    expect(() => assertDeviceDashboardSupport(f.schema)).not.toThrow();
    const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    const fact = facts.current[0]!.values[0]!;
    expect(fact.state === 'VALUE' && Array.isArray(fact.value) && fact.value.length).toBe(3);
    expect(f.plan.devices[0]!.propertyKeys).toEqual(['temperature']);
    expect(f.fetch).toHaveBeenCalledTimes(2);
  });
  it('keeps valid OBJECT kind/lexical fields intact and nested scalar values exact', async () => {
    const f = compositeFixture();
    f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
      : new Response(JSON.stringify(f.current).replace('"value":0', '"value":{"kind":"NUMBER","lexical":"not-a-number","n":9007199254740993}')));
    const fact = (await loadDevicePage({ ...f.context, plan: f.plan })).current[0]!.values[0]!;
    expect(fact).toMatchObject({ state: 'VALUE', value: { kind: 'NUMBER', lexical: 'not-a-number', n: { kind: 'NUMBER', lexical: '9007199254740993' } } });
  });
  it('rejects type-incompatible LIST_VALUE binding before current query', async () => {
    const f = compositeFixture('TABLE', 'OBJECT');
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'plan' });
    expect(f.fetch).toHaveBeenCalledTimes(1);
  });
  it('rejects a wrong composite root and depth-invalid VALUE without converting it to NO_VALUE', async () => {
    const f = compositeFixture();
    for (const source of ['[]', '{"x":null}', `{"x":${'['.repeat(7)}true${']'.repeat(7)}}`]) {
      f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
        : new Response(JSON.stringify(f.current).replace('"value":0', `"value":${source}`)));
      await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'response' });
    }
  });
  it('keeps existing empty states bodyless even for composite properties', async () => {
    const f = compositeFixture();
    f.fetch.mockImplementation(async path => path.includes('snapshots') ? Response.json(f.snapshots)
      : Response.json({ devices: [{ deviceId, status: 'AVAILABLE', values: [{ propertyKey: 'temperature', state: 'CONTRACT_MISMATCH' }] }] }));
    expect((await loadDevicePage({ ...f.context, plan: f.plan })).current[0]!.values[0]).toEqual({ propertyKey: 'temperature', state: 'CONTRACT_MISMATCH' });
  });
});

function interactiveFixture() {
  const f = fixture();
  const source = { ...f.raw, variables: [...f.raw.variables,
    { key: 'range', type: 'TIME_RANGE', title: '时段' }, { key: 'mode', type: 'TEXT_ENUM', title: '模式', options: [{ value: 'a', label: '显示标签' }] }],
    pages: [{ id: 'main', title: '首页', components: [...f.raw.pages[0]!.components,
      { id: 'chart', kind: 'LINE_CHART', componentVersion: '1.0.0', layout: { x: 0, y: 16, w: 24, h: 8 }, props: { series: [{ id: 'temp', label: '历史温度' }] },
        bindings: { series: [{ id: 'temp', value: { source: 'HISTORY_SERIES', device: { variableKey: 'selected' }, propertyKey: 'temperature', timeRangeVariableKey: 'range', granularity: 'RAW', aggregation: 'AVG' } }] } },
      { id: 'alarms', kind: 'ALARM_LIST', componentVersion: '1.0.0', layout: { x: 0, y: 24, w: 24, h: 8 }, props: {},
        bindings: { alarms: { source: 'ALARM_LIST', devices: { variableKey: 'selected' }, conditionStates: ['ACTIVE'], ackStates: ['UNACKNOWLEDGED'], severities: ['MAJOR'] } } },
      { id: 'label', kind: 'TEXT', componentVersion: '1.0.0', layout: { x: 0, y: 32, w: 24, h: 8 }, props: {}, bindings: { text: { source: 'ENUM_TEXT', variableKey: 'mode' } } },
    ] }] };
  const schema = validated(source); const to = '2026-09-07T02:00:00.000Z';
  const plan = planDevicePage(schema, 'main', { selected: deviceId, mode: null }, to);
  const context = { ...f.context, published: { ...f.context.published, schema } };
  f.fetch.mockImplementation(async path => Response.json(path.includes('snapshots') ? f.snapshots : path.includes('history/versioned')
    ? { requestedGranularity: 'RAW', actualGranularity: 'RAW', aggregation: 'AVG', points: [] }
    : path.includes('/alarms/') ? { items: [], nextCursor: null, hasMore: false } : f.current));
  return { ...f, source, schema, plan, context, to };
}
describe('complete interactive page plan and reads', () => {
  it('uses one captured UTC window and does not block selected devices for an empty required enum', async () => {
    const f = interactiveFixture(); expect(() => assertDeviceDashboardSupport(f.schema)).not.toThrow();
    expect(f.plan.missingRequired).toContain('mode'); expect(f.plan.histories[0]).toMatchObject({ to: f.to, from: '2026-09-07T01:00:00.000Z' });
    const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    expect(facts.current).toHaveLength(1); expect(facts.history[0]!.status).toBe('READY'); expect(facts.alarms[0]!.status).toBe('READY');
    expect(f.fetch).toHaveBeenCalledTimes(4);
    expect(f.fetch.mock.calls.find(([path]) => path.includes('/history/'))![0]).toContain('to=2026-09-07T02%3A00%3A00.000Z');
  });
  it('validates enum/preset selections and never uses an enum value as a request path', () => {
    const f = interactiveFixture();
    const plan = planDevicePage(f.schema, 'main', { selected: deviceId, mode: 'a', range: 'LAST_24_HOURS' }, f.to);
    expect(plan.textBindings).toEqual([{ componentId: 'label', variableKey: 'mode', label: '显示标签' }]);
    expect(plan.histories[0]!.from).toBe('2026-09-06T02:00:00.000Z');
    expect(() => planDevicePage(f.schema, 'main', { mode: 'evil/path' }, f.to)).toThrow();
    expect(() => planDevicePage(f.schema, 'main', { range: 'CUSTOM' }, f.to)).toThrow();
  });
  it('keeps 10001/30058 localized while 60009/60010 reject the whole page', async () => {
    for (const code of [10001, 30058, 60009, 60010]) {
      const f = interactiveFixture();
      f.fetch.mockImplementation(async path => path.includes('/history/') ? Response.json({ code }, { status: 400 })
        : Response.json(path.includes('snapshots') ? f.snapshots : path.includes('/alarms/') ? { items: [], nextCursor: null, hasMore: false } : f.current));
      if (code >= 60000) await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'identity' });
      else { const result = await loadDevicePage({ ...f.context, plan: f.plan }); expect(result.history[0]!.status).toBe(code === 10001 ? 'CONFIGURATION_ERROR' : 'NON_NUMERIC'); expect(result.current).toHaveLength(1); }
    }
  });
  it('counts history metadata keys without accidentally requesting history-only current values', async () => {
    const f = interactiveFixture();
    const schema = validated({ ...f.source, pages: [{ ...f.source.pages[0], components: f.source.pages[0]!.components.filter(component => component.id !== 'card') }] });
    const plan = planDevicePage(schema, 'main', { selected: deviceId }, f.to);
    await loadDevicePage({ ...f.context, published: { ...f.context.published, schema }, plan });
    expect(JSON.parse(f.fetch.mock.calls[0]![1]!.body as string).devices[0].propertyKeys).toEqual(['temperature']);
    expect(f.fetch.mock.calls.some(([path]) => path.includes('/current-values/'))).toBe(false);
  });
});

describe('interactive shared observations and isolated pagination', () => {
  it('deduplicates equal history/initial alarm queries and updates only the explicitly paged component', async () => {
    const f = interactiveFixture(); const chart = f.source.pages[0]!.components.find(component => component.id === 'chart')!;
    const alarm = f.source.pages[0]!.components.find(component => component.id === 'alarms')!;
    const schema = validated({ ...f.source, pages: [{ ...f.source.pages[0], components: [...f.source.pages[0]!.components,
      { ...chart, id: 'chart_two', layout: { x: 0, y: 40, w: 24, h: 8 } }, { ...alarm, id: 'alarms_two', layout: { x: 0, y: 48, w: 24, h: 8 } }] }] });
    const context = { ...f.context, published: { ...f.context.published, schema } };
    const plan = planDevicePage(schema, 'main', { selected: deviceId }, f.to);
    expect(plan.histories).toHaveLength(1); expect(plan.alarmQueries).toHaveLength(1);
    const facts = await loadDevicePage({ ...context, plan }); expect(facts.history).toHaveLength(1); expect(facts.alarms).toHaveLength(2);
    expect(f.fetch).toHaveBeenCalledTimes(4);
    const page = await loadAlarmPage(context, 'alarms_two', 'opaque', plan);
    expect(page.componentId).toBe('alarms_two'); expect(f.fetch).toHaveBeenCalledTimes(5);
    expect(facts.alarms[0]!.componentId).toBe('alarms');
    expect(JSON.parse(f.fetch.mock.calls[4]![1]!.body as string).cursor).toBe('opaque');
  });
  it('rejects eleven distinct history queries while shared identical series consume only one', () => {
    const f = interactiveFixture();
    const charts = Array.from({ length: 3 }, (_, group) => {
      const count = group === 2 ? 3 : 4;
      return { id: `chart_${group}`, kind: 'LINE_CHART', componentVersion: '1.0.0', layout: { x: 0, y: group * 8, w: 24, h: 8 },
        props: { series: Array.from({ length: count }, (_, index) => ({ id: `s_${index}`, label: '历史' })) },
        bindings: { series: Array.from({ length: count }, (_, index) => ({ id: `s_${index}`, value: { source: 'HISTORY_SERIES', device: { variableKey: 'selected' },
          propertyKey: `key_${group}_${index}`, timeRangeVariableKey: 'range', granularity: 'RAW', aggregation: 'AVG' } })) } };
    });
    const schema = validated({ ...f.source, pages: [{ id: 'main', title: '首页', components: charts }] });
    expect(() => assertDeviceDashboardSupport(schema)).toThrow();
    expect(() => planDevicePage(schema, 'main', { selected: deviceId }, f.to)).toThrow();
    const shared = validated({ ...f.source, pages: [{ id: 'main', title: '首页', components: charts.map(chart => ({ ...chart, props: { series: chart.props.series.slice(0, 1) },
      bindings: { series: chart.bindings.series.slice(0, 1).map(series => ({ ...series, value: { ...series.value, propertyKey: 'temperature' } })) } })) }] });
    expect(() => assertDeviceDashboardSupport(shared)).not.toThrow();
    expect(planDevicePage(shared, 'main', { selected: deviceId }, f.to).histories).toHaveLength(1);
  });
});

describe('combined page expansion boundaries', () => {
  const chart = (id: string, keys: string[], y: number) => ({ id, kind: 'LINE_CHART', componentVersion: '1.0.0', layout: { x: 0, y, w: 24, h: 8 },
    props: { series: keys.map((key, index) => ({ id: `series_${index}`, label: key })) },
    bindings: { series: keys.map((propertyKey, index) => ({ id: `series_${index}`, value: { source: 'HISTORY_SERIES', device: { variableKey: 'selected' }, propertyKey,
      timeRangeVariableKey: 'range', granularity: 'RAW', aggregation: 'AVG' } })) } });
  it('accepts ten complete history queries but rejects cold API expansion above twenty', () => {
    const f = interactiveFixture(); const charts = [chart('one', ['h0', 'h1', 'h2', 'h3'], 0), chart('two', ['h4', 'h5', 'h6', 'h7'], 8), chart('three', ['h8', 'h9'], 16)];
    const schema = validated({ ...f.source, pages: [{ id: 'main', title: '首页', components: charts }] });
    expect(() => assertDeviceDashboardSupport(schema)).not.toThrow();
    expect(planDevicePage(schema, 'main', { selected: deviceId }, f.to).histories).toHaveLength(10);
    const alarm = f.source.pages[0]!.components.find(component => component.id === 'alarms')!;
    const expanded = validated({ ...f.source, pages: [{ id: 'main', title: '首页', components: [...charts,
      ...Array.from({ length: 6 }, (_, index) => ({ ...alarm, id: `alarm_${index}`, props: { pageSize: index + 1 }, layout: { x: 0, y: 24 + 8 * index, w: 24, h: 8 } }))] }] });
    expect(() => assertDeviceDashboardSupport(expanded)).toThrow(); // 4冷+meta1+history10+alarm6=21。
  });
  it('includes history-only keys in the per-device fifty-key maximum', () => {
    const f = interactiveFixture();
    const card = f.source.pages[0]!.components.find(component => component.id === 'card')!;
    const cards = Array.from({ length: 45 }, (_, index) => ({ ...card, id: `card_${index}`, layout: { x: 0, y: index * 8, w: 24, h: 8 },
      bindings: { value: { source: 'CURRENT_VALUE', device: { variableKey: 'selected' }, propertyKey: `c_${index}` } } }));
    const schema = validated({ ...f.source, pages: [{ id: 'main', title: '首页', components: [...cards,
      chart('one', ['h0', 'h1', 'h2', 'h3'], 360), chart('two', ['h4', 'h5'], 368)] }] });
    expect(() => assertDeviceDashboardSupport(schema)).toThrow();
    expect(() => planDevicePage(schema, 'main', { selected: deviceId }, f.to)).toThrow();
  });
  it('counts current and history together at two hundred device-key pairs', () => {
    const f = interactiveFixture(); const multi = multiDefinition(19, Array.from({ length: 10 }, (_, index) => `c_${index}`));
    const table = { ...multi.pages[0]!.components[0]!, id: 'multi', layout: { x: 0, y: 0, w: 24, h: 8 },
      bindings: { columns: multi.pages[0]!.components[0]!.bindings.columns.map(column => ({ ...column, value: { ...column.value, device: { variableKey: 'many' } } })) } };
    const base = { ...f.source, variables: [...f.source.variables, { ...multi.variables[0], key: 'many' }],
      pages: [{ id: 'main', title: '首页', components: [table, chart('one', ['h0', 'h1', 'h2', 'h3'], 8), chart('two', ['h4', 'h5', 'h6', 'h7'], 16), chart('three', ['h8', 'h9'], 24)] }] };
    expect(() => assertDeviceDashboardSupport(validated(base))).not.toThrow(); // 19*10+10=200。
    const card = { ...f.source.pages[0]!.components.find(component => component.id === 'card')!, layout: { x: 0, y: 32, w: 24, h: 8 } };
    expect(() => assertDeviceDashboardSupport(validated({ ...base, pages: [{ ...base.pages[0], components: [...base.pages[0]!.components, card] }] }))).toThrow();
  });
});


describe('current-only realtime refresh and subscription barrier', () => {
  it('waits after metadata validation and before the first current REST without inventing an ACK', async () => {
    const f = fixture();
    let release!: () => void;
    let entered!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const waiting = new Promise<void>(resolve => { entered = resolve; });
    const beforeCurrent = vi.fn(async (queries: readonly { deviceId: string; expectedModelVersionId: string; propertyKeys: readonly string[] }[]) => {
      expect(queries).toEqual([{ deviceId, expectedModelVersionId: modelId, propertyKeys: ['temperature'] }]);
      entered(); await gate;
    });
    const loading = loadDevicePage({ ...f.context, plan: f.plan, beforeCurrent });
    await waiting;
    expect(f.fetch).toHaveBeenCalledTimes(1);
    expect(f.fetch.mock.calls[0]![0]).toContain('/snapshots/');
    release(); await loading;
    expect(beforeCurrent).toHaveBeenCalledTimes(1);
    expect(f.fetch.mock.calls[1]![0]).toContain('/current-values/');
  });

  it('excludes history-only keys and devices from the realtime subscription', () => {
    const f = interactiveFixture();
    expect(currentSubscription(f.plan)).toEqual([{ deviceId, propertyKeys: ['temperature'] }]);
    const schema = validated({ ...f.source, pages: [{ ...f.source.pages[0], components: f.source.pages[0]!.components.filter(component => component.id !== 'card') }] });
    expect(currentSubscription(planDevicePage(schema, 'main', { selected: deviceId }, f.to))).toEqual([]);
  });

  it('dirty sends one authoritative current request and preserves history/alarm receipts by reference', async () => {
    const f = interactiveFixture();
    const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    f.fetch.mockClear(); f.current.devices[0]!.values[0]!.value = 12;
    const updated = await loadDeviceCurrent({ ...f.context, plan: f.plan, facts, devices: [{ deviceId, propertyKeys: ['temperature'] }] });
    expect(f.fetch).toHaveBeenCalledTimes(1);
    expect(f.fetch.mock.calls[0]![0]).toContain('/current-values/');
    expect(new Headers(f.fetch.mock.calls[0]![1]!.headers).get('X-Application-Revision')).toBe('9007199254740993');
    expect(updated.current[0]!.values[0]).toMatchObject({ state: 'VALUE', value: { lexical: '12' } });
    expect(facts.current[0]!.values[0]).toMatchObject({ value: { lexical: '0' } });
    expect(updated.history).toBe(facts.history); expect(updated.alarms).toBe(facts.alarms);
  });

  it.each(['NOT_AVAILABLE', 'MODEL_MISMATCH'] as const)('dirty %s requires full recovery instead of merging stale history with a negative current device', async status => {
    const f = interactiveFixture(); const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    f.fetch.mockClear(); f.fetch.mockResolvedValue(Response.json({ devices: [{ deviceId, status, values: [] }] }));
    await expect(loadDeviceCurrent({ ...f.context, plan: f.plan, facts })).rejects.toMatchObject({ reason: 'identity' });
    expect(f.fetch).toHaveBeenCalledTimes(1);
    expect(facts.current[0]!.status).toBe('AVAILABLE');
  });

  it('rejects out-of-plan dirty keys and stale selection facts before HTTP, while an empty dirty set does nothing', async () => {
    const f = fixture(); const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    f.fetch.mockClear();
    await expect(loadDeviceCurrent({ ...f.context, plan: f.plan, facts, devices: [{ deviceId, propertyKeys: ['unknown'] }] })).rejects.toMatchObject({ reason: 'plan' });
    await expect(loadDeviceCurrent({ ...f.context, plan: f.plan, facts: { ...facts, selections: { selected: null } } })).rejects.toMatchObject({ reason: 'identity' });
    expect(await loadDeviceCurrent({ ...f.context, plan: f.plan, facts, devices: [] })).toBe(facts);
    expect(f.fetch).not.toHaveBeenCalled();
  });
});

describe('independent share device data adapter', () => {
  function shareFixture() {
    const f = interactiveFixture();
    const shareId = '66666666-6666-4666-8666-666666666666';
    const context: Extract<DeviceReadContext, { kind: 'share' }> = {
      kind: 'share', context: { shareId, dashboardId, dashboardVersionId: dashboardId, dashboardVersionNumber: '1',
        expiresAt: '2026-09-08T00:00:00Z', historyAnchorAt: f.to,
        hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '1.0.1' }, variableScopes: [{ variableKey: 'selected', deviceIds: [deviceId] }] },
      published: { dashboardId, dashboardVersionId: dashboardId, dashboardVersionNumber: '1', schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', schemaDigest: digest, schema: f.schema },
      session: { shareId, fetch: f.fetch, checkCurrent: () => ({ shareId }) },
    };
    f.fetch.mockImplementation(async path => Response.json(path.includes('snapshots') ? f.snapshots : path.includes('/history?')
      ? { from: f.plan.histories[0]!.from, to: f.to, requestedGranularity: 'RAW', actualGranularity: 'RAW', aggregation: 'AVG', points: [] }
      : path.includes('/alarms/') ? { items: [], nextCursor: null, hasMore: false } : f.current));
    return { ...f, context, shareId };
  }
  it('uses independent share routes and server anchor without App runtime headers', async () => {
    const f = shareFixture(); const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    expect(facts.current).toHaveLength(1); expect(facts.history[0]?.status).toBe('READY'); expect(facts.alarms[0]?.status).toBe('READY');
    for (const [path, init] of f.fetch.mock.calls) {
      expect(path).toMatch(new RegExp(`^/api/v1/shares/${f.shareId}/`));
      expect(Object.keys(init?.headers ?? {}).some(key => key.toLowerCase().startsWith('x-'))).toBe(false);
    }
    const history = new URL(f.fetch.mock.calls.find(([path]) => path.includes('/history?'))![0], 'https://example.test');
    expect(history.searchParams.get('windowPreset')).toBe('LAST_1_HOUR');
    expect(history.searchParams.get('anchorAt')).toBe(f.to); expect(history.searchParams.has('from')).toBe(false);
    f.fetch.mockClear();
    const updated = await loadDeviceCurrent({ ...f.context, plan: f.plan, facts });
    expect(f.fetch).toHaveBeenCalledTimes(1); expect(updated.history).toBe(facts.history);
    expect(f.fetch.mock.calls[0]![0]).toContain('/devices/current-values/query');
  });
  it('fails before HTTP for a same-model device outside the independently sealed variable', async () => {
    const f = shareFixture(); const plan = planDevicePage(f.schema, 'main', { selected: appId }, f.to);
    await expect(loadDevicePage({ ...f.context, plan })).rejects.toMatchObject({ reason: 'plan' });
    expect(f.fetch).not.toHaveBeenCalled();
  });
  it('rejects client-generated anchors and missing capability generation before HTTP', async () => {
    const f = shareFixture(); const plan = planDevicePage(f.schema, 'main', f.plan.selections, '2026-09-07T03:00:00Z');
    await expect(loadDevicePage({ ...f.context, plan })).rejects.toMatchObject({ reason: 'plan' });
    f.context.session.checkCurrent = () => ({ shareId: appId });
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'identity' });
    expect(f.fetch).not.toHaveBeenCalled();
  });
  it('recognizes revoked capability as full identity loss instead of a local empty observation', async () => {
    const f = shareFixture(); f.fetch.mockImplementation(async () => Response.json({ code: 60053 }, { status: 404 }));
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'identity' });
  });
  it('filters catalog by variableKey and rejects an out-of-scope returned row', async () => {
    const f = shareFixture(); f.fetch.mockImplementation(async () => Response.json({ items: [{ deviceId, name: '设备', deviceStatus: 'ONLINE', currentModelVersionId: modelId }], nextCursor: null, hasMore: false }));
    await loadDeviceCatalog({ ...f.context, variableKey: 'selected' });
    expect(f.fetch.mock.calls[0]![0]).toContain('variableKey=selected'); expect(f.fetch.mock.calls[0]![0]).not.toContain('modelVersionId=');
    f.fetch.mockImplementation(async () => Response.json({ items: [{ deviceId: appId, name: '越界', deviceStatus: 'ONLINE', currentModelVersionId: modelId }], nextCursor: null, hasMore: false }));
    await expect(loadDeviceCatalog({ ...f.context, variableKey: 'selected' })).rejects.toMatchObject({ reason: 'response' });
  });
  it('rejects returned history windows that differ even below a millisecond', async () => {
    const f = shareFixture(); f.fetch.mockImplementation(async path => Response.json(path.includes('snapshots') ? f.snapshots : path.includes('/history?')
      ? { from: f.plan.histories[0]!.from, to: '2026-09-07T02:00:00.000000001Z', requestedGranularity: 'RAW', actualGranularity: 'RAW', aggregation: 'AVG', points: [] } : f.current));
    await expect(loadDevicePage({ ...f.context, plan: f.plan })).rejects.toMatchObject({ reason: 'response' });
  });
});

describe('current dashboard alarm dirty read', () => {
  it('maps long query identities to short keys and replaces shared group pages with one REST read', async () => {
    const f = interactiveFixture();
    const alarm = f.source.pages[0]!.components.find(component => component.id === 'alarms')!;
    const schema = validated({ ...f.source, pages: [{ ...f.source.pages[0], components: [...f.source.pages[0]!.components,
      { ...alarm, id: 'alarms_two', layout: { x: 0, y: 40, w: 24, h: 8 } }] }] });
    const context = { ...f.context, published: { ...f.context.published, schema } };
    const plan = planDevicePage(schema, 'main', { selected: deviceId }, f.to);
    const facts = await loadDevicePage({ ...context, plan });
    const groups = alarmSubscription(plan); expect(groups).toHaveLength(1);
    expect(groups[0]!.queryKey).toBe('a0'); expect(groups[0]!.queryId.length).toBeGreaterThan(64);
    const old = { ...facts, alarms: facts.alarms.map(result => ({ ...result, nextCursor: 'old-page', hasMore: true })) };
    f.fetch.mockClear();
    const updated = await loadDirtyAlarms({ ...context, plan, facts: old, queryIds: [groups[0]!.queryId] });
    expect(f.fetch).toHaveBeenCalledTimes(1); expect(f.fetch.mock.calls[0]![0]).toContain('/alarms/query');
    expect(JSON.parse(f.fetch.mock.calls[0]![1]!.body as string)).not.toHaveProperty('cursor');
    expect(updated.alarms.map(result => result.nextCursor)).toEqual([null, null]);
    expect(updated.history).toBe(facts.history); expect(updated.current).toBe(facts.current);
    f.fetch.mockClear();
    await expect(loadDirtyAlarms({ ...context, plan, facts, queryIds: ['foreign-query'] })).rejects.toMatchObject({ reason: 'plan' });
    expect(f.fetch).not.toHaveBeenCalled();
  });
  it('waits after metadata before the first REST on a pure alarm page', async () => {
    const f = interactiveFixture();
    const schema = validated({ ...f.source, pages: [{ ...f.source.pages[0], components: f.source.pages[0]!.components.filter(component => component.id === 'alarms') }] });
    const plan = planDevicePage(schema, 'main', { selected: deviceId }, f.to);
    const context = { ...f.context, published: { ...f.context.published, schema } };
    f.fetch.mockImplementation(async path => Response.json(path.includes('snapshots')
      ? { devices: f.snapshots.devices, models: [{ ...f.snapshots.models[0], properties: [] }] }
      : { items: [], nextCursor: null, hasMore: false }));
    let release!: () => void; const gate = new Promise<void>(resolve => { release = resolve; });
    let entered!: () => void; const ready = new Promise<void>(resolve => { entered = resolve; });
    const loading = loadDevicePage({ ...context, plan, beforeCurrent: async (queries, available) => {
      expect(queries).toEqual([]); expect(available).toHaveLength(1); entered(); await gate;
    } });
    await ready; expect(f.fetch).toHaveBeenCalledTimes(1); release();
    const facts = await loading; expect(f.fetch).toHaveBeenCalledTimes(2); expect(facts.alarms[0]!.status).toBe('READY');
  });
  it('propagates authority loss without producing stale merged alarm facts', async () => {
    const f = interactiveFixture(); const facts = await loadDevicePage({ ...f.context, plan: f.plan });
    f.fetch.mockResolvedValue(Response.json({ code: 60010 }, { status: 403 }));
    await expect(loadDirtyAlarms({ ...f.context, plan: f.plan, facts, queryIds: [f.plan.alarmQueries[0]!.queryId] }))
      .rejects.toMatchObject({ reason: 'identity' });
  });
});
