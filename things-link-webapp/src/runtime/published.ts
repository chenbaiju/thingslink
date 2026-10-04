import {
  isHostVersionCompatible,
  parseDashboardSchemaEnvelope,
  validateDashboardSchemaV1,
  type DashboardSchemaV1,
} from '@things-link/client-contracts/dashboard/v1';
import { assertDeviceDashboardSupport } from './device-data';
import type { SessionCoordinator } from '../auth/session';
import type { components } from '../types/api/schema';

export type CurrentApplication = components['schemas']['WebAppApplicationCurrentResponse'];
/** 本地制品注册表；真实PNG字节/长度/SHA-256由构建检查证明，不接受业务JSON给出的URL。 */
export interface PublishedBuiltinResource {
  readonly resourceId: string;
  readonly digestAlgorithm: 'SHA-256';
  readonly digest: string;
  readonly assetPath: string;
  readonly mediaType: 'image/png';
  readonly byteLength: number;
}
export interface PublishedDashboard {
  readonly applicationVersionId: string;
  readonly publicationRevision: string;
  readonly dashboardId: string;
  readonly dashboardVersionId: string;
  readonly dashboardVersionNumber: string;
  readonly schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256';
  readonly schemaDigest: string;
  readonly schema: DashboardSchemaV1;
}
export interface PublishedDashboardRequest {
  readonly current: CurrentApplication;
  readonly dashboardId: string;
  readonly session: Pick<SessionCoordinator, 'fetch' | 'checkCurrent'>;
  readonly resources: readonly PublishedBuiltinResource[];
  readonly hostVersion: string;
  readonly signal?: AbortSignal;
}
/** 固定错误类别不能泄露API错误正文、身份或令牌。 */
export class PublishedDashboardError extends Error {
  constructor(readonly reason: 'invalid-current' | 'unavailable' | 'invalid-schema' | 'incompatible' | 'identity-changed' | 'too-large') {
    super(`Published dashboard ${reason}`);
  }
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const LOCAL_KEY = /^[a-z][a-z0-9_]{0,63}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const HOST_VERSION = __HOST_VERSION__;
const MAX_ENVELOPE = 768 * 1024;
function requireThat(value: unknown, reason: PublishedDashboardError['reason']): asserts value {
  if (!value) throw new PublishedDashboardError(reason);
}
function positiveLong(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n;
}
function exactKeys(value: unknown, keys: string[]): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).sort().join(',') === [...keys].sort().join(',');
}
/** 有界读取原始字节；Schema子树必须保留源文本，不能经JSON.parse/stringify重构后校验。 */
export async function rawBody(response: Response, signal?: AbortSignal, maximum = MAX_ENVELOPE): Promise<Uint8Array> {
  const reader = response.body?.getReader();
  requireThat(reader, 'invalid-schema');
  const bytes = new Uint8Array(maximum);
  let size = 0;
  try {
    while (true) {
      signal?.throwIfAborted();
      const next = await reader.read();
      if (next.done) break;
      if (next.value.length > maximum - size) {
        await reader.cancel();
        throw new PublishedDashboardError('too-large');
      }
      bytes.set(next.value, size); size += next.value.length;
    }
  } finally { reader.releaseLock(); }
  return bytes.subarray(0, size);
}

/** 元数据§5：每次只读显式选中的精确版本；所有身份、摘要、页序和宿主需求通过才返回整版。 */
export async function loadPublishedDashboard(request: PublishedDashboardRequest): Promise<PublishedDashboard> {
  const { current, session, signal } = request;
  const before = session.checkCurrent();
  requireThat(before.status === 'authenticated' && current.identity?.kind === 'APP'
    && current.identity.appUserId === before.appUserId && current.identity.projectId === before.projectId, 'identity-changed');
  requireThat(current.applicationFormatVersion === 'tc.application/v1'
    && UUID.test(current.applicationVersionId) && positiveLong(current.applicationVersionNumber)
    && positiveLong(current.publicationRevision) && UUID.test(current.application?.id)
    && /^app_[0-9a-f]{32}$/.test(current.application.appKey)
    && Array.isArray(current.dashboards) && current.dashboards.length >= 1 && current.dashboards.length <= 5, 'invalid-current');
  requireThat(request.hostVersion === HOST_VERSION && current.hostCompatibility
    && isHostVersionCompatible(request.hostVersion, current.hostCompatibility), 'incompatible');
  requireThat(new Set(current.dashboards.map((ref) => ref.dashboardId)).size === current.dashboards.length
    && new Set(current.dashboards.map((ref) => ref.dashboardVersionId)).size === current.dashboards.length, 'invalid-current');
  for (const ref of current.dashboards) {
    requireThat(UUID.test(ref.dashboardId) && UUID.test(ref.dashboardVersionId) && positiveLong(ref.dashboardVersionNumber)
      && ref.schemaVersion === 'tc.dashboard/v1' && ref.schemaDigestAlgorithm === 'PG_JSONB_TEXT_V1_SHA256'
      && SHA256.test(ref.schemaDigest) && Array.isArray(ref.pages) && ref.pages.length >= 1 && ref.pages.length <= 5
      && new Set(ref.pages.map((page) => page.id)).size === ref.pages.length
      && ref.pages.every((page) => typeof page.id === 'string' && LOCAL_KEY.test(page.id) && typeof page.title === 'string'), 'invalid-current');
  }
  requireThat(current.entryDashboardId === null || current.dashboards.some((ref) => ref.dashboardId === current.entryDashboardId), 'invalid-current');
  const selected = current.dashboards.find((ref) => ref.dashboardId === request.dashboardId);
  requireThat(selected, 'invalid-current');
  // 异步期间UI可能替换current对象；保存本次明确入口的标量及页面副本，不跟随新指针。
  const reference = { ...selected, pages: selected.pages.map((page) => ({ ...page })) };
  const appVersion = current.applicationVersionId;
  const revision = current.publicationRevision;
  const appKey = current.application.appKey;
  const resourceRegistry = request.resources.map((resource) => ({ ...resource }));
  signal?.throwIfAborted();
  const response = await session.fetch(`/api/v1/app/applications/${appKey}/versions/${appVersion}/dashboards/${reference.dashboardVersionId}/schema?expectedPublicationRevision=${revision}`, {
    method: 'GET', headers: { Accept: 'application/json' }, signal,
  });
  requireThat(response.status === 200, 'unavailable');
  const raw = await rawBody(response, signal);
  signal?.throwIfAborted();
  const after = session.checkCurrent();
  requireThat(after.status === 'authenticated' && after.appUserId === before.appUserId && after.projectId === before.projectId, 'identity-changed');
  try {
    const { envelope, schemaSource } = parseDashboardSchemaEnvelope(raw);
    requireThat(exactKeys(envelope, ['applicationVersionId', 'publicationRevision', 'dashboardId', 'dashboardVersionId',
      'dashboardVersionNumber', 'schemaVersion', 'schemaDigestAlgorithm', 'schemaDigest', 'requiredComponents', 'requiredResources', 'schema']), 'invalid-schema');
    requireThat(envelope.applicationVersionId === appVersion && envelope.publicationRevision === revision
      && envelope.dashboardId === reference.dashboardId && envelope.dashboardVersionId === reference.dashboardVersionId
      && envelope.dashboardVersionNumber === reference.dashboardVersionNumber && envelope.schemaVersion === reference.schemaVersion
      && envelope.schemaDigestAlgorithm === reference.schemaDigestAlgorithm && envelope.schemaDigest === reference.schemaDigest, 'invalid-schema');
    const schema = validatePublishedSchema(schemaSource, envelope, resourceRegistry);
    requireThat(schema.pages.length === reference.pages.length && schema.pages.every((page, index) =>
      page.id === reference.pages[index]!.id && page.title === reference.pages[index]!.title), 'invalid-schema');
    session.checkCurrent();
    return Object.freeze({ applicationVersionId: appVersion, publicationRevision: revision, dashboardId: reference.dashboardId,
      dashboardVersionId: reference.dashboardVersionId, dashboardVersionNumber: reference.dashboardVersionNumber,
      schemaDigestAlgorithm: reference.schemaDigestAlgorithm, schemaDigest: reference.schemaDigest, schema });
  } catch (error) {
    if (error instanceof PublishedDashboardError) throw error;
    throw new PublishedDashboardError('invalid-schema');
  }
}

/** 两种真实身份共用Schema/制品解释；不接收应用身份字段。 */
export function validatePublishedSchema(schemaSource: Uint8Array, envelope: Record<string, unknown>,
  resourceRegistry: readonly PublishedBuiltinResource[], identityKind: 'app' | 'share' = 'app'): DashboardSchemaV1 {
  const validated = validateDashboardSchemaV1(schemaSource);
  const schema = validated.schema;
  try { assertDeviceDashboardSupport(schema, identityKind); } catch { throw new PublishedDashboardError('incompatible'); }
  const components = new Map<string, { kind: string; componentVersion: string }>();
  const resources = new Map<string, { resourceId: string; digest: string }>();
  requireThat(resourceRegistry.length <= 64 && new Set(resourceRegistry.map((entry) => entry.resourceId)).size === resourceRegistry.length, 'incompatible');
  for (const requirement of validated.unresolvedRequirements) {
    if (requirement.requirementType === 'HOST_COMPONENT') {
      requireThat((['TEXT', 'IMAGE', 'DEVICE_SELECTOR', 'VALUE_CARD', 'STATUS', 'GAUGE', 'TABLE', 'JSON_VIEW', 'LINE_CHART', 'ALARM_LIST'].includes(requirement.kind)) && requirement.componentVersion === (HOST_VERSION === '1.0.0' ? '1.0.0' : '1.0.1')
        && isHostVersionCompatible(HOST_VERSION, requirement.hostRange), 'incompatible');
      components.set(`${requirement.kind}/${requirement.componentVersion}`, { kind: requirement.kind, componentVersion: requirement.componentVersion });
    } else if (requirement.requirementType === 'BUILTIN_RESOURCE') {
      const resource = resourceRegistry.find((entry) => entry.resourceId === requirement.resourceId);
      requireThat(resource && resource.digestAlgorithm === 'SHA-256' && SHA256.test(resource.digest)
        && resource.digest === requirement.digest && resource.assetPath === `assets/${resource.digest}.png`
        && resource.mediaType === 'image/png' && Number.isInteger(resource.byteLength) && resource.byteLength >= 1 && resource.byteLength <= 1048576, 'incompatible');
      resources.set(requirement.resourceId, { resourceId: requirement.resourceId, digest: requirement.digest });
    } else if (requirement.requirementType === 'DATA_ADAPTER') {
      requireThat(['CURRENT_VALUE', 'DEVICE_STATUS', 'BOUNDED_DEVICE_DIRECTORY_PAGE', 'BOUNDED_DEVICE_VALUES', 'LIST_SNAPSHOT', 'COMPOSITE_SNAPSHOT', 'HISTORY_SERIES', 'BOUNDED_ALARM_PAGE'].includes(requirement.capability), 'incompatible');
    } else if (!['MODEL_REFERENCE', 'DEFAULT_DEVICE', 'MODEL_PROPERTY', 'MODEL_GAUGE_RANGE', 'HISTORICAL_PROPERTY'].includes(requirement.requirementType)) {
      throw new PublishedDashboardError('incompatible');
    }
    // 模型/默认设备需求在选中页的快照端口复验；未选择是明确状态，不伪造授权或元数据。
  }
  const expectedComponents = [...components.entries()].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([, value]) => value);
  const expectedResources = [...resources.entries()].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([, value]) => value);
  requireThat(Array.isArray(envelope.requiredComponents) && envelope.requiredComponents.length === expectedComponents.length
    && envelope.requiredComponents.every((entry, index) => exactKeys(entry, ['kind', 'componentVersion'])
    && entry.kind === expectedComponents[index]!.kind && entry.componentVersion === expectedComponents[index]!.componentVersion), 'invalid-schema');
  requireThat(Array.isArray(envelope.requiredResources) && envelope.requiredResources.length === expectedResources.length
    && envelope.requiredResources.every((entry, index) => exactKeys(entry, ['resourceId', 'digest'])
    && entry.resourceId === expectedResources[index]!.resourceId && entry.digest === expectedResources[index]!.digest), 'invalid-schema');
  return schema;
}
