import { isHostVersionCompatible, parseDashboardRuntimeResponse, parseDashboardSchemaEnvelope } from '@things-link/client-contracts/dashboard/v1';
import type { ShareSession } from '../share/session';
import type { components } from '../types/api/schema';
import { PublishedDashboardError, rawBody, validatePublishedSchema, type PublishedBuiltinResource, type PublishedDashboard } from './published';
import { utcTimestamp } from './observation-data';

export type ShareContext = components['schemas']['DashboardShareContextResponse'];
export type ShareReadSession = Pick<ShareSession, 'shareId' | 'fetch' | 'checkCurrent'>;
/** 分享只有真实看板版本，不能补造应用版本、publicationRevision或APP主体。 */
export type SharePublishedDashboard = Omit<PublishedDashboard, 'applicationVersionId' | 'publicationRevision'>;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function check(value: unknown, reason: PublishedDashboardError['reason'] = 'invalid-schema'): asserts value {
  if (!value) throw new PublishedDashboardError(reason);
}
function object(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  check(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === [...keys].sort().join(','));
}
function freeze<T>(value: T): T {
  if (value && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value); }
  return value;
}
function fence(session: ShareReadSession, shareId: string, signal?: AbortSignal): void {
  signal?.throwIfAborted();
  check(UUID.test(shareId) && session.shareId === shareId && session.checkCurrent().shareId === shareId, 'identity-changed');
}
/** 来源只可能是真实分享context，不用浏览器时钟伪造服务端历史锚点。 */
export async function loadShareContext(request: { shareId: string; session: ShareReadSession; signal?: AbortSignal }): Promise<ShareContext> {
  const { shareId, session, signal } = request;
  fence(session, shareId, signal);
  const response = await session.fetch(`/api/v1/shares/${shareId}/context`, { headers: { Accept: 'application/json' }, signal });
  check(response.status === 200, response.status === 404 ? 'identity-changed' : 'unavailable');
  try {
    const value = parseDashboardRuntimeResponse(await rawBody(response, signal, 16 * 1024));
    fence(session, shareId, signal);
    object(value, ['shareId', 'dashboardId', 'dashboardVersionId', 'dashboardVersionNumber', 'expiresAt', 'historyAnchorAt', 'hostCompatibility', 'variableScopes']);
    check(value.shareId === shareId && typeof value.dashboardId === 'string' && UUID.test(value.dashboardId)
      && typeof value.dashboardVersionId === 'string' && UUID.test(value.dashboardVersionId)
      && typeof value.dashboardVersionNumber === 'string' && /^[1-9][0-9]{0,18}$/.test(value.dashboardVersionNumber)
      && BigInt(value.dashboardVersionNumber) <= 9223372036854775807n
      && utcTimestamp(value.expiresAt) && utcTimestamp(value.historyAnchorAt)
      && Date.parse(value.expiresAt) > Date.parse(value.historyAnchorAt));
    object(value.hostCompatibility, ['minInclusive', 'maxExclusive']);
    check(typeof value.hostCompatibility.minInclusive === 'string' && typeof value.hostCompatibility.maxExclusive === 'string'
      && isHostVersionCompatible(__HOST_VERSION__, value.hostCompatibility as { minInclusive: string; maxExclusive: string }), 'incompatible');
    check(Array.isArray(value.variableScopes) && value.variableScopes.length <= 64);
    const variables = new Set<string>(); const devices = new Set<string>();
    for (const scope of value.variableScopes) {
      object(scope, ['variableKey', 'deviceIds']);
      check(typeof scope.variableKey === 'string' && /^[a-z][a-z0-9_]{0,63}$/.test(scope.variableKey) && !variables.has(scope.variableKey));
      variables.add(scope.variableKey);
      check(Array.isArray(scope.deviceIds) && scope.deviceIds.length >= 1 && scope.deviceIds.length <= 20
        && scope.deviceIds.every(id => typeof id === 'string' && UUID.test(id)) && new Set(scope.deviceIds).size === scope.deviceIds.length);
      for (const id of scope.deviceIds) devices.add(id as string);
    }
    check(devices.size <= 20);
    return freeze(value as unknown as ShareContext);
  } catch (error) {
    if (error instanceof PublishedDashboardError) throw error;
    throw new PublishedDashboardError('invalid-schema');
  }
}
/** 服务端已复算持久jsonb摘要；客户端消费摘要及精确版本，不以JSON.stringify仿算PG摘要。 */
export async function loadSharePublished(request: { context: ShareContext; session: ShareReadSession;
  resources: readonly PublishedBuiltinResource[]; hostVersion: string; signal?: AbortSignal }): Promise<SharePublishedDashboard> {
  const { context, session, signal } = request;
  fence(session, context.shareId, signal);
  check(request.hostVersion === __HOST_VERSION__ && isHostVersionCompatible(request.hostVersion, context.hostCompatibility), 'incompatible');
  const response = await session.fetch(`/api/v1/shares/${context.shareId}/schema`, { headers: { Accept: 'application/json' }, signal });
  check(response.status === 200, response.status === 404 ? 'identity-changed' : 'unavailable');
  try {
    const { envelope, schemaSource } = parseDashboardSchemaEnvelope(await rawBody(response, signal));
    fence(session, context.shareId, signal);
    object(envelope, ['dashboardId', 'dashboardVersionId', 'dashboardVersionNumber', 'schemaVersion', 'schemaDigestAlgorithm',
      'schemaDigest', 'requiredComponents', 'requiredResources', 'schema']);
    check(envelope.dashboardId === context.dashboardId && envelope.dashboardVersionId === context.dashboardVersionId
      && envelope.dashboardVersionNumber === context.dashboardVersionNumber && envelope.schemaVersion === 'tc.dashboard/v1'
      && envelope.schemaDigestAlgorithm === 'PG_JSONB_TEXT_V1_SHA256' && typeof envelope.schemaDigest === 'string' && /^[a-f0-9]{64}$/.test(envelope.schemaDigest));
    const schema = validatePublishedSchema(schemaSource, envelope, request.resources, 'share');
    const variables = schema.variables.filter(variable => variable.type === 'DEVICE_SINGLE' || variable.type === 'DEVICE_MULTI');
    check(variables.length === context.variableScopes.length);
    const models = new Map<string, string>();
    for (const variable of variables) {
      const scope = context.variableScopes.find(scope => scope.variableKey === variable.key); check(scope);
      const model = schema.models.find(model => model.key === variable.modelKey); check(model);
      const defaults = variable.type === 'DEVICE_SINGLE' ? (variable.defaultDeviceId ? [variable.defaultDeviceId] : []) : variable.defaultDeviceIds;
      check(defaults.every(id => scope.deviceIds.includes(id)));
      for (const id of scope.deviceIds) { check(!models.has(id) || models.get(id) === model.versionId); models.set(id, model.versionId); }
    }
    fence(session, context.shareId, signal);
    return freeze({ dashboardId: context.dashboardId, dashboardVersionId: context.dashboardVersionId,
      dashboardVersionNumber: context.dashboardVersionNumber, schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256', schemaDigest: envelope.schemaDigest, schema });
  } catch (error) {
    if (error instanceof PublishedDashboardError) throw error;
    throw new PublishedDashboardError('invalid-schema');
  }
}
