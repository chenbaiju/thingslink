import { describe, expect, it } from 'vitest'
import {
  checkArtifactSize,
  emptyOtaHistory,
  firmwareActions,
  firmwareStatusLabel,
  firmwareStatusTag,
  formatBytes,
  mergeOtaHistoryPage,
  OTA_MANIFEST_REQUIRED_FIELDS,
  OTA_MAX_ARTIFACT_BYTES,
  parseManifestInput,
  publicationEnvironmentBoundary,
  uploadStatusLabel
} from '@/features/ota/firmware-model'

/** 构造一份形状完整的 manifest，用于逐字段验证缺失拒绝。 */
const manifest = (): Record<string, unknown> => {
  const value: Record<string, unknown> = { contractVersion: 'tc-ota-manifest/v1' }
  for (const field of OTA_MANIFEST_REQUIRED_FIELDS) {
    if (field !== 'contractVersion') value[field] = field === 'artifactSize' ? 1024 : 'x'
  }
  return value
}

describe('OTA固件展示与判定', () => {
  it('未知状态原样透传，不折叠成已知状态', () => {
    expect(firmwareStatusLabel('RETRY_WAIT')).toBe('RETRY_WAIT')
    expect(firmwareStatusTag('RETRY_WAIT')).toBe('info')
    expect(firmwareStatusLabel('READY')).toBe('已就绪')
    expect(firmwareStatusTag('REVOKED')).toBe('danger')
    expect(firmwareStatusLabel(null)).toBe('—')
    expect(uploadStatusLabel('VERIFIED')).toBe('对象已复验')
    expect(uploadStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })

  it('按字节格式化并使用固定单位', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1024)).toBe('1.0 KiB')
    expect(formatBytes(OTA_MAX_ARTIFACT_BYTES)).toBe('64.0 MiB')
    expect(formatBytes(undefined)).toBe('—')
  })

  it('上传前只校验控制台能诚实判定的部分', () => {
    expect(checkArtifactSize(1).ok).toBe(true)
    expect(checkArtifactSize(OTA_MAX_ARTIFACT_BYTES).ok).toBe(true)
    expect(checkArtifactSize(0).ok).toBe(false)
    expect(checkArtifactSize(-1).ok).toBe(false)
    expect(checkArtifactSize(OTA_MAX_ARTIFACT_BYTES + 1).ok).toBe(false)
    expect(checkArtifactSize(OTA_MAX_ARTIFACT_BYTES + 1).reason).toContain('超过单次上界')
  })

  it('动作资格与服务端判定一致：只有草稿可上传与取消，只有已就绪可退役', () => {
    const draft = firmwareActions('DRAFT', false)
    expect(draft).toMatchObject({
      canUpload: true,
      canCancel: true,
      canPublish: false,
      canDeprecate: false
    })
    expect(draft.reason).toContain('对象上传与复验')

    const draftUploaded = firmwareActions('DRAFT', true)
    expect(draftUploaded.canPublish).toBe(true)
    expect(draftUploaded.reason).toBe('')

    const ready = firmwareActions('READY', true)
    expect(ready).toMatchObject({
      canUpload: false,
      canPublish: false,
      canCancel: false,
      canDeprecate: true,
      canRevoke: true
    })

    const deprecated = firmwareActions('DEPRECATED', true)
    expect(deprecated.canRevoke).toBe(true)
    expect(deprecated.canDeprecate).toBe(false)

    for (const terminal of ['CANCELLED', 'REVOKED']) {
      const actions = firmwareActions(terminal, true)
      expect(actions.canUpload).toBe(false)
      expect(actions.canPublish).toBe(false)
      expect(actions.reason).toContain('终态')
    }

    const verifying = firmwareActions('VERIFYING', true)
    expect(verifying.reason).toContain('signer')
  })

  it('把信任未配置与无signer都识别为环境边界，而不是业务拒绝', () => {
    expect(publicationEnvironmentBoundary(70010, 503)).toBe('TRUST_NOT_CONFIGURED')
    expect(publicationEnvironmentBoundary(70016, 503)).toBe('SIGNER_UNAVAILABLE')
    expect(publicationEnvironmentBoundary(undefined, 503)).toBe('SIGNER_UNAVAILABLE')
    expect(publicationEnvironmentBoundary(70017, 422)).toBeNull()
    expect(publicationEnvironmentBoundary(70018, 409)).toBeNull()
    expect(publicationEnvironmentBoundary(70020, 403)).toBeNull()
  })

  it('manifest只做形状校验：缺字段、错合同版本与非对象都被拒绝', () => {
    const complete = manifest()
    const ok = parseManifestInput(JSON.stringify(complete))
    expect(ok.ok).toBe(true)
    expect(ok.manifest).toEqual(complete)

    expect(parseManifestInput('   ').ok).toBe(false)
    expect(parseManifestInput('not json').reason).toContain('合法JSON')
    expect(parseManifestInput('[1]').reason).toContain('JSON对象')

    const wrongVersion = { ...complete, contractVersion: 'tc-ota-manifest/v2' }
    expect(parseManifestInput(JSON.stringify(wrongVersion)).reason).toContain('tc-ota-manifest/v1')

    for (const field of OTA_MANIFEST_REQUIRED_FIELDS) {
      const missing = { ...complete }
      delete missing[field]
      const result = parseManifestInput(JSON.stringify(missing))
      expect(result.ok, `缺少 ${field} 时必须拒绝`).toBe(false)
      expect(result.reason).toContain(field)
    }
  })

  it('历史分页首页替换、续页追加，末页清空游标', () => {
    expect(emptyOtaHistory<string>()).toEqual({ items: [], cursor: '', hasMore: false })

    const first = mergeOtaHistoryPage(
      emptyOtaHistory<string>(),
      {
        items: ['a', 'b'],
        nextCursor: 'cursor-1',
        hasMore: true
      },
      false
    )
    expect(first).toEqual({ items: ['a', 'b'], cursor: 'cursor-1', hasMore: true })

    const second = mergeOtaHistoryPage(
      first,
      { items: ['c'], nextCursor: null, hasMore: false },
      true
    )
    expect(second).toEqual({ items: ['a', 'b', 'c'], cursor: '', hasMore: false })

    // 服务端返回已到末页时即使给了游标也不能继续请求下一页。
    expect(
      mergeOtaHistoryPage(first, { items: [], nextCursor: 'stale', hasMore: false }, true)
    ).toEqual({
      items: ['a', 'b'],
      cursor: '',
      hasMore: false
    })
    // items缺省按空数组处理，失败响应不会被伪造成事实。
    expect(mergeOtaHistoryPage(first, { hasMore: false }, true).items).toEqual(['a', 'b'])
  })
})
