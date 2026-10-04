const COMPONENT_VERSION = __HOST_VERSION__ === '1.0.0' ? '1.0.0' : '1.0.1';
import { describe, expect, it, vi } from 'vitest';
import { loadShareContext, loadSharePublished, type ShareContext, type ShareReadSession } from './share-published';
const shareId = '11111111-1111-4111-8111-111111111111';
const dashboardId = '22222222-2222-4222-8222-222222222222';
const versionId = '33333333-3333-4333-8333-333333333333';
function fixture() {
  const context: ShareContext = { shareId, dashboardId, dashboardVersionId: versionId, dashboardVersionNumber: '1',
    expiresAt: '2026-09-08T00:00:00Z', historyAnchorAt: '2026-09-07T00:00:00.123456789Z',
    hostCompatibility: (__HOST_VERSION__ === '1.0.0' ? { minInclusive: '1.0.0', maxExclusive: '1.0.1' } : { minInclusive: '1.1.0', maxExclusive: '1.1.2' }), variableScopes: [] };
  const schema = { schemaVersion: 'tc.dashboard/v1', presentation: { mode: 'RESPONSIVE_GRID' }, pages: [{ id: 'main', title: '分享', components: [
    { id: 'text', kind: 'TEXT', componentVersion: COMPONENT_VERSION, layout: { x: 0, y: 0, w: 24, h: 8 }, props: { content: '<script>原样文字</script>' }, bindings: {} },
  ] }] };
  const envelope = { dashboardId, dashboardVersionId: versionId, dashboardVersionNumber: '1', schemaVersion: 'tc.dashboard/v1',
    schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', schemaDigest: 'a'.repeat(64), requiredComponents: [{ kind: 'TEXT', componentVersion: COMPONENT_VERSION }], requiredResources: [], schema };
  const fetch = vi.fn<ShareReadSession['fetch']>(async path => Response.json(path.endsWith('/context') ? context : envelope));
  const session: ShareReadSession = { shareId, fetch, checkCurrent: () => ({ shareId }) };
  return { context, envelope, fetch, session, request: { context, session, resources: [], hostVersion: __HOST_VERSION__ } };
}
describe('independent share published context', () => {
  it('uses only the capability context/schema and preserves exact server anchor and digest', async () => {
    const f = fixture(); const context = await loadShareContext({ shareId, session: f.session });
    const result = await loadSharePublished({ ...f.request, context });
    expect(context.historyAnchorAt).toBe(f.context.historyAnchorAt); expect(Object.isFrozen(context)).toBe(true);
    expect(result).not.toHaveProperty('applicationVersionId'); expect(result).not.toHaveProperty('publicationRevision');
    expect(result.schemaDigest).toBe('a'.repeat(64)); expect(f.fetch.mock.calls.map(([path]) => path)).toEqual([`/api/v1/shares/${shareId}/context`, `/api/v1/shares/${shareId}/schema`]);
  });
  it('rejects extra App identity fields and duplicate context keys', async () => {
    const f = fixture(); f.fetch.mockImplementation(async () => Response.json({ ...f.context, identity: { kind: 'APP' } }));
    await expect(loadShareContext({ shareId, session: f.session })).rejects.toMatchObject({ reason: 'invalid-schema' });
    f.fetch.mockImplementation(async () => new Response(JSON.stringify(f.context).replace('"shareId":', `"shareId":"${shareId}","shareId":`)));
    await expect(loadShareContext({ shareId, session: f.session })).rejects.toMatchObject({ reason: 'invalid-schema' });
  });
  it('rejects mismatched exact version, wrong digest algorithm and unregistered components', async () => {
    const f = fixture(); f.envelope.dashboardVersionId = shareId;
    await expect(loadSharePublished(f.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
    const g = fixture(); g.envelope.schemaDigestAlgorithm = 'SHA-256';
    await expect(loadSharePublished(g.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
    const h = fixture(); h.envelope.requiredComponents = [];
    await expect(loadSharePublished(h.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
  });
  it('rejects unknown variable scopes and duplicate scope devices', async () => {
    const f = fixture(); f.context.variableScopes = [{ variableKey: 'unknown', deviceIds: [dashboardId] }];
    await expect(loadSharePublished(f.request)).rejects.toMatchObject({ reason: 'invalid-schema' });
    f.context.variableScopes[0]!.deviceIds.push(dashboardId);
    await expect(loadShareContext({ shareId, session: f.session })).rejects.toMatchObject({ reason: 'invalid-schema' });
  });
  it('bounds context raw bytes and fences before/after asynchronous reads', async () => {
    const f = fixture(); f.fetch.mockImplementation(async () => new Response(' '.repeat(16385)));
    await expect(loadShareContext({ shareId, session: f.session })).rejects.toMatchObject({ reason: 'too-large' });
    const g = fixture(); g.fetch.mockImplementation(async () => { g.session.checkCurrent = () => ({ shareId: dashboardId }); return Response.json(g.context); });
    await expect(loadShareContext({ shareId, session: g.session })).rejects.toMatchObject({ reason: 'identity-changed' });
  });
});
