<template>
  <section class="personal-records" data-testid="personal-records">
    <h4>个人事实记录</h4>
    <p class="console-description"
      >仅本人且当前仍有项目权限可读；历史事实不代表设备当前状态，不是模型诊断。记录30天后不可读，文本与对象值不保存。</p
    >
    <div class="console-toolbar">
      <ElButton :disabled="!canSave" :loading="busy" @click="save">重新取证并保存</ElButton>
      <ElButton :disabled="!readable || busy" @click="refresh">刷新本人记录</ElButton>
    </div>
    <ElAlert v-if="error" type="error" :closable="false" :title="error" />
    <p v-if="notice">{{ notice }}</p>
    <p v-if="loaded && !rows.length">当前设备没有本人有效记录。</p>
    <ul v-if="rows.length">
      <li v-for="row in rows" :key="row.id">
        <span>{{ row.createdAt }}（可见至 {{ row.expiresAt }}）</span>
        <ElButton :disabled="busy || !readable" @click="open(row.id)">查看</ElButton>
        <ElButton :disabled="busy || !readable" @click="generate(row.id)">生成事实报告</ElButton>
        <ElButton :disabled="busy || !readable" @click="generate(row.id, true)">
          重新确权并下载报告
        </ElButton>
        <ElButton :disabled="busy || !readable" @click="remove(row.id)">删除本人记录</ElButton>
      </li>
    </ul>
    <template v-if="detail">
      <p
        >历史采集区间：{{ detail.snapshot.collectionStartedAt }} 至
        {{ detail.snapshot.collectionFinishedAt }}</p
      >
      <p>采集时模型：{{ detail.snapshot.modelVersionId }}</p>
      <p
        >采集时设备状态：{{ detail.snapshot.device.status }}；告警摘要：{{
          detail.snapshot.alarmSummary.state
        }}</p
      >
      <ul>
        <li v-for="property in detail.snapshot.properties" :key="property.key">
          {{ property.key }}：{{
            property.valueOmitted
              ? '值已省略（文本或对象）'
              : property.value === null
                ? '缺少可用值'
                : JSON.stringify(property.value)
          }}
          （来源：{{ property.availability }}；上报：{{ property.occurredAt ?? '未提供' }}）
        </li>
      </ul>
    </template>
    <section v-if="report" aria-label="个人历史事实报告" data-testid="personal-fact-report">
      <h4>个人历史事实报告</h4>
      <p>仅汇编本条历史记录，未做模型诊断；下载会再次核验当前权限与来源有效期。</p>
      <p>来源记录：{{ report.sourceRecord.id }}；报告摘要：{{ report.contentSha256 }}</p>
      <pre>{{ report.markdown }}</pre>
    </section>
  </section>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import {
    savePersonalRecord,
    listPersonalRecords,
    readPersonalRecord,
    deletePersonalRecord,
    generatePersonalFactReport,
    type PersonalRecord,
    type PersonalRecordDetail,
    type PersonalFactReport
  } from '@/api/assistant-records'
  const props = defineProps<{
    projectId: string
    deviceId: string
    modelVersionId: string
    propertyKeys: string[]
    parentBusy?: boolean
  }>()
  const user = useUserStore()
  const rows = ref<PersonalRecord[]>([])
  const detail = ref<PersonalRecordDetail>()
  const report = ref<PersonalFactReport>()
  const busy = ref(false)
  const loaded = ref(false)
  const error = ref('')
  const notice = ref('')
  const readable = computed(() =>
    Boolean(
      user.isLogin &&
        user.info.userId &&
        user.info.tenantId &&
        props.deviceId &&
        props.projectId &&
        user.info.currentProjectId === props.projectId &&
        user.info.roles?.some((role) => ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'].includes(role))
    )
  )
  const canSave = computed(
    () =>
      readable.value &&
      !busy.value &&
      !props.parentBusy &&
      !!props.modelVersionId &&
      props.propertyKeys.length >= 1 &&
      props.propertyKeys.length <= 10 &&
      new Set(props.propertyKeys).size === props.propertyKeys.length &&
      props.propertyKeys.every((key) => /^[A-Za-z0-9_-]{1,64}$/.test(key))
  )
  let generation = 0
  let controller: AbortController | undefined
  function clear() {
    ++generation
    controller?.abort()
    controller = undefined
    rows.value = []
    detail.value = undefined
    report.value = undefined
    busy.value = loaded.value = false
    error.value = notice.value = ''
  }
  function begin() {
    ++generation
    controller?.abort()
    controller = new AbortController()
    busy.value = true
    error.value = notice.value = ''
    detail.value = undefined
    report.value = undefined
    const run = generation,
      identity = currentIdentityEpoch(),
      signal = controller.signal
    const scope = () =>
      [
        props.projectId,
        props.deviceId,
        props.modelVersionId,
        props.propertyKeys.join(','),
        user.info.userId,
        user.info.tenantId,
        user.info.currentProjectId,
        user.info.roles?.join(',')
      ].join('|')
    const startedScope = scope()
    return {
      signal,
      current: () =>
        run === generation &&
        identity === currentIdentityEpoch() &&
        readable.value &&
        startedScope === scope()
    }
  }
  const valid = (row: PersonalRecord) =>
    typeof row?.id === 'string' &&
    row.deviceId === props.deviceId &&
    typeof row.modelVersionId === 'string' &&
    typeof row.contentSha256 === 'string' &&
    /^[0-9a-f]{64}$/.test(row.contentSha256) &&
    Number.isFinite(Date.parse(row.createdAt)) &&
    Number.isFinite(Date.parse(row.expiresAt))
  async function refresh() {
    if (!readable.value || busy.value) return
    const run = begin()
    rows.value = []
    loaded.value = false
    try {
      const result = await listPersonalRecords(props.projectId, run.signal)
      if (!run.current()) return
      if (!Array.isArray(result) || result.length > 100) throw new Error('INVALID_RECORD_LIST')
      const selected = result.filter((row) => row.deviceId === props.deviceId)
      if (!selected.every(valid) || new Set(selected.map((row) => row.id)).size !== selected.length)
        throw new Error('INVALID_RECORD_LIST')
      rows.value = selected
      loaded.value = true
    } catch {
      if (run.current()) error.value = '本人记录不可用，请核对当前权限后手动刷新。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  async function save() {
    if (!canSave.value) return
    const run = begin()
    try {
      const row = await savePersonalRecord(
        props.projectId,
        props.deviceId,
        props.modelVersionId,
        [...props.propertyKeys],
        run.signal
      )
      if (!run.current()) return
      if (!valid(row) || row.modelVersionId !== props.modelVersionId)
        throw new Error('INVALID_SAVED_RECORD')
      rows.value = [row, ...rows.value.filter((item) => item.id !== row.id)].slice(0, 100)
      loaded.value = true
      notice.value = '已保存本人历史事实。'
    } catch {
      if (run.current()) error.value = '保存未取得有效回执；请手动刷新列表核对，不会自动重发。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  async function open(id: string) {
    if (!readable.value || busy.value || !rows.value.some((row) => row.id === id)) return
    const run = begin()
    try {
      const result = await readPersonalRecord(props.projectId, id, run.signal)
      if (!run.current()) return
      if (
        !valid(result.record) ||
        result.record.id !== id ||
        result.snapshot.deviceId !== props.deviceId ||
        result.snapshot.modelVersionId !== result.record.modelVersionId ||
        result.snapshot.schemaVersion !== 1
      )
        throw new Error('INVALID_RECORD_DETAIL')
      detail.value = result
    } catch {
      if (run.current()) error.value = '历史事实不可读，可能已到期、删除或权限变化，请手动刷新。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  async function remove(id: string) {
    if (!readable.value || busy.value || !rows.value.some((row) => row.id === id)) return
    const run = begin()
    try {
      await deletePersonalRecord(props.projectId, id, run.signal)
      if (!run.current()) return
      rows.value = rows.value.filter((row) => row.id !== id)
      notice.value = '本人记录已删除。'
    } catch {
      if (run.current()) error.value = '删除未取得回执，请手动刷新核对。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  async function generate(id: string, download = false) {
    const row = rows.value.find((entry) => entry.id === id)
    if (!readable.value || busy.value || !row) return
    const run = begin()
    try {
      const result = await generatePersonalFactReport(props.projectId, id, run.signal)
      if (!run.current()) return
      const source = result?.sourceRecord
      if (
        result.schemaVersion !== 1 ||
        result.mode !== 'FACTS_ONLY' ||
        !valid(source) ||
        source.id !== row.id ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(source.id) ||
        source.modelVersionId !== row.modelVersionId ||
        source.contentSha256 !== row.contentSha256 ||
        source.createdAt !== row.createdAt ||
        source.expiresAt !== row.expiresAt ||
        Date.parse(source.expiresAt) <= Date.now() ||
        typeof result.markdown !== 'string' ||
        !result.markdown ||
        typeof result.contentSha256 !== 'string' ||
        !/^[0-9a-f]{64}$/.test(result.contentSha256)
      )
        throw new Error('INVALID_FACT_REPORT')
      const bytes = new TextEncoder().encode(result.markdown)
      if (bytes.byteLength > 131072) throw new Error('FACT_REPORT_TOO_LARGE')
      const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
      if (!run.current()) return
      if (Date.parse(source.expiresAt) <= Date.now()) throw new Error('FACT_REPORT_EXPIRED')
      const hash = Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')
      if (hash !== result.contentSha256) throw new Error('FACT_REPORT_HASH_MISMATCH')
      report.value = result
      if (download) {
        const url = URL.createObjectURL(new Blob([bytes], { type: 'text/markdown;charset=utf-8' }))
        try {
          const link = document.createElement('a')
          link.href = url
          link.download = `personal-fact-report-${source.id}.md`
          link.click()
        } finally {
          setTimeout(() => URL.revokeObjectURL(url), 0)
        }
        notice.value = '报告已通过来源及摘要校验，已发起浏览器下载。'
      } else notice.value = '已生成本人历史事实报告，未调用模型。'
    } catch {
      if (run.current()) {
        report.value = undefined
        error.value = '报告未取得有效回执或校验失败，未显示或下载；请核对权限后手动重试。'
      }
    } finally {
      if (run.current()) busy.value = false
    }
  }
  watch(
    () => [
      props.projectId,
      props.deviceId,
      props.modelVersionId,
      props.propertyKeys.join(','),
      user.isLogin,
      user.info.userId,
      user.info.tenantId,
      user.info.currentProjectId,
      user.info.roles?.join(','),
      currentIdentityEpoch()
    ],
    clear
  )
  onBeforeUnmount(clear)
</script>

<style scoped>
  [data-testid='personal-fact-report'] pre {
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
</style>
