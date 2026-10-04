const COMPONENT_VERSION = __HOST_VERSION__ === '1.0.0' ? '1.0.0' : '1.0.1';
import { describe, expect, it, vi } from 'vitest';
import { loadPublishedDashboard, type CurrentApplication, type PublishedDashboardRequest } from './published';
const appVersion = '11111111-1111-4111-8111-111111111111';
const dashboardId = '22222222-2222-4222-8222-222222222222';
const dashboardVersionId = '33333333-3333-4333-8333-333333333333';
const userId = '44444444-4444-4444-8444-444444444444';
const projectId = '55555555-5555-4555-8555-555555555555';
const digest = 'a'.repeat(64);
function fixture() {
  const current: CurrentApplication = {
    identity: { kind: 'APP', appUserId: userId, projectId },
    application: { id: appVersion, appKey: `app_${'a'.repeat(32)}`, displayName: '应用' },
    applicationFormatVersion: 'tc.application/v1', applicationVersionId: appVersion, applicationVersionNumber: '1',
    publicationRevision: '9007199254740993', entryDashboardId: dashboardId,
    hostCompatibility: (__HOST_VERSION__ === '1.0.0' ? { minInclusive: '1.0.0', maxExclusive: '1.0.1' } : { minInclusive: '1.1.0', maxExclusive: '1.1.2' }),
    dashboards: [{ dashboardId, dashboardVersionId, dashboardVersionNumber: '2', title: '看板', schemaVersion: 'tc.dashboard/v1',
      schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', schemaDigest: digest, pages: [{ id: 'main', title: '首页' }] }],
  };
  const schema = { schemaVersion: 'tc.dashboard/v1', presentation: { mode: 'RESPONSIVE_GRID' }, pages: [{
    id: 'main', title: '首页', components: [{ id: 'heading', kind: 'TEXT', componentVersion: COMPONENT_VERSION,
      layout: { x: 0, y: 0, w: 24, h: 8 }, props: { content: '静态内容' }, bindings: {} }],
  }] };
  const envelope = {
    applicationVersionId: appVersion, publicationRevision: current.publicationRevision, dashboardId, dashboardVersionId,
    dashboardVersionNumber: '2', schemaVersion: 'tc.dashboard/v1', schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
    schemaDigest: digest, requiredComponents: [{ kind: 'TEXT', componentVersion: COMPONENT_VERSION }], requiredResources: [], schema,
  };
  const fetch = vi.fn<PublishedDashboardRequest['session']['fetch']>(async () => Response.json(envelope));
  const checkCurrent = vi.fn(() => ({ status: 'authenticated' as const, appUserId: userId, projectId }));
  const request: PublishedDashboardRequest = { current, dashboardId, session: { fetch, checkCurrent }, resources: [], hostVersion: __HOST_VERSION__ };
  return { current, schema, envelope, fetch, checkCurrent, request };
}
describe('precise published dashboard read', () => {
  it('reads only the explicit immutable dashboard and preserves the server digest', async () => {
    const f = fixture(); const result = await loadPublishedDashboard(f.request);
    expect(f.fetch).toHaveBeenCalledTimes(1);
    expect(f.fetch.mock.calls[0]?.[0]).toContain(`/${dashboardVersionId}/schema?expectedPublicationRevision=9007199254740993`);
    expect(result.schemaDigest).toBe(digest);
    expect(result.schema.presentation).toMatchObject({ mode: 'RESPONSIVE_GRID', columns: 24 });
    expect(Object.isFrozen(result.schema)).toBe(true);
  });
  it.each(['publicationRevision', 'applicationVersionId', 'dashboardVersionId', 'dashboardVersionNumber', 'schemaDigest'] as const)(
    'rejects mismatched %s instead of falling back', async (field) => {
      const f = fixture(); f.envelope[field] = 'mismatch';
      await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
    });
  it('checks exact ordered pages and exact component descriptors', async () => {
    const f = fixture(); f.current.dashboards[0]!.pages[0]!.title = '别的页面';
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
    const g = fixture(); g.envelope.requiredComponents.push({ kind: 'IMAGE', componentVersion: COMPONENT_VERSION });
    await expect(loadPublishedDashboard(g.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
  });
  it('rejects duplicate JSON before any lossy parse can hide it', async () => {
    const f = fixture(); const raw = JSON.stringify(f.envelope).replace('"content":"静态内容"', '"content":"静态内容","content":"隐藏"');
    f.fetch.mockImplementation(async () => new Response(raw));
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
  });
  it('rejects raw oversized schema even when normalized content would be tiny', async () => {
    const f = fixture(); const raw = JSON.stringify(f.envelope).replace('"presentation":', `${' '.repeat(512000)}"presentation":`);
    f.fetch.mockImplementation(async () => new Response(raw));
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
    f.fetch.mockImplementation(async () => new Response(' '.repeat(768 * 1024 + 1)));
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'too-large' });
  });
  it('rejects an unsupported component anywhere, never rendering only the surrounding frame', async () => {
    const f = fixture(); f.schema.pages[0]!.components[0]!.kind = 'COMMAND_BUTTON';
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
  });
  it('accepts variable-bound TEXT as a pure declared label without adding a data endpoint', async () => {
    const f = fixture();
    const raw = JSON.stringify(f.envelope);
    const value = JSON.parse(raw);
    value.schema.variables = [{ key: 'mode', type: 'TEXT_ENUM', title: '模式', options: [{ value: 'a', label: '甲' }] }];
    value.schema.pages[0].components[0].props = {};
    value.schema.pages[0].components[0].bindings = { text: { source: 'ENUM_TEXT', variableKey: 'mode' } };
    f.fetch.mockImplementation(async () => Response.json(value));
    expect((await loadPublishedDashboard(f.request)).schema.pages[0]!.components[0]!.kind).toBe('TEXT');
    expect(f.fetch).toHaveBeenCalledTimes(1);
  });
  it('accepts exact composite and alarm capabilities across pages with exact descriptors', async () => {
    const f = fixture(); const value = JSON.parse(JSON.stringify(f.envelope));
    value.schema.models = [{ key: 'model', versionId: userId, digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', digest, profile: 'TC_PROPERTY_COMPOSITE_V1' }];
    value.schema.variables = [{ key: 'sensor', type: 'DEVICE_SINGLE', title: '设备', modelKey: 'model' }];
    const binding = { source: 'CURRENT_VALUE', device: { variableKey: 'sensor' }, propertyKey: 'samples' };
    value.schema.pages[0].components = [
      { id: 'list', kind: 'TABLE', componentVersion: COMPONENT_VERSION, layout: { x: 0, y: 0, w: 12, h: 8 }, props: { mode: 'LIST_VALUE' }, bindings: { value: binding } },
      { id: 'json', kind: 'JSON_VIEW', componentVersion: COMPONENT_VERSION, layout: { x: 12, y: 0, w: 12, h: 8 }, props: {}, bindings: { value: binding } },
    ];
    value.requiredComponents = ['JSON_VIEW', 'TABLE'].map(kind => ({ kind, componentVersion: COMPONENT_VERSION }));
    f.fetch.mockImplementation(async () => Response.json(value));
    expect((await loadPublishedDashboard(f.request)).schema.pages[0]!.components).toHaveLength(2);
    // 非当前页的能力仍完整检查，声明与实现必须覆盖整版。
    const page = { id: 'history', title: '历史', components: [{ id: 'alarms', kind: 'ALARM_LIST', componentVersion: COMPONENT_VERSION,
      layout: { x: 0, y: 0, w: 24, h: 8 }, props: {}, bindings: { alarms: { source: 'ALARM_LIST', devices: { variableKey: 'sensor' }, conditionStates: ['ACTIVE'], ackStates: ['UNACKNOWLEDGED'], severities: ['INFO'] } } }] };
    value.schema.pages.push(page); f.current.dashboards[0]!.pages.push({ id: 'history', title: '历史' });
    value.requiredComponents.unshift({ kind: 'ALARM_LIST', componentVersion: COMPONENT_VERSION });
    expect((await loadPublishedDashboard(f.request)).schema.pages).toHaveLength(2);
    value.requiredComponents.shift();
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
  });
  it('rejects incompatible host and an absent selected dashboard before HTTP', async () => {
    const f = fixture(); f.current.hostCompatibility.minInclusive = '9.0.0';
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'incompatible' });
    expect(f.fetch).not.toHaveBeenCalled();
    const g = fixture(); await expect(loadPublishedDashboard({ ...g.request, dashboardId: userId })).rejects.toMatchObject({ reason: 'invalid-current' });
    expect(g.fetch).not.toHaveBeenCalled();
  });
  it('fences identity after the entire streamed body arrives', async () => {
    const f = fixture();
    f.checkCurrent.mockReturnValueOnce({ status: 'authenticated', appUserId: userId, projectId })
      .mockReturnValue({ status: 'authenticated', appUserId: appVersion, projectId });
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'identity-changed' });
  });
  it('accepts IMAGE only from an exact digest PNG registry entry', async () => {
    const f = fixture(); const value = JSON.parse(JSON.stringify(f.envelope));
    value.schema.pages[0].components[0] = { id: 'image', kind: 'IMAGE', componentVersion: COMPONENT_VERSION, layout: { x: 0, y: 0, w: 24, h: 8 },
      props: { resourceId: 'device_mark', resourceDigest: digest, alt: '设备' }, bindings: {} };
    value.requiredComponents = [{ kind: 'IMAGE', componentVersion: COMPONENT_VERSION }];
    value.requiredResources = [{ resourceId: 'device_mark', digest }];
    f.fetch.mockImplementation(async () => Response.json(value));
    const resource = { resourceId: 'device_mark', digest, digestAlgorithm: 'SHA-256' as const, assetPath: `assets/${digest}.png`, mediaType: 'image/png' as const, byteLength: 191 };
    expect((await loadPublishedDashboard({ ...f.request, resources: [resource] })).schema.pages[0]!.components[0]!.kind).toBe('IMAGE');
    await expect(loadPublishedDashboard({ ...f.request, resources: [{ ...resource, assetPath: 'https://evil.test/image.png' }] })).rejects.toMatchObject({ reason: 'incompatible' });
    await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'incompatible' });
  });
});

it('does not let a broad application range bypass an incompatible exact component', async () => {
  const f = fixture();
  f.current.hostCompatibility = { minInclusive: '1.0.0', maxExclusive: '2.0.0' };
  const value = JSON.parse(JSON.stringify(f.envelope));
  const incompatible = __HOST_VERSION__ === '1.0.0' ? '1.0.1' : '1.0.0';
  value.schema.pages[0].components[0].componentVersion = incompatible;
  value.requiredComponents[0].componentVersion = incompatible;
  f.fetch.mockImplementation(async () => Response.json(value));
  await expect(loadPublishedDashboard(f.request)).rejects.toMatchObject({ reason: 'incompatible' });
});
