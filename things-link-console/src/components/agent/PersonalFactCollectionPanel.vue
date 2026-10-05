<template>
  <ElCard v-if="readable" shadow="never" data-testid="personal-fact-collection">
    <template #header><h4 class="console-heading">个人历史设备集合报告</h4></template>
    <p class="console-description"
      >仅汇编本人所选历史记录，未做实时巡检或模型诊断；最多五设备，每设备选一条。记录仍仅本人且当前有权可读。</p
    >
    <div class="console-toolbar">
      <ElButton :disabled="busy" @click="refresh">刷新本人项目记录</ElButton>
      <ElButton :disabled="!canGenerate" @click="generate()">生成集合事实报告</ElButton>
      <ElButton :disabled="!canGenerate" @click="generate(true)">重新确权并下载集合报告</ElButton>
    </div>
    <ElAlert v-if="error" type="error" :closable="false" :title="error" />
    <p v-if="notice">{{ notice }}</p>
    <p v-if="loaded && !rows.length"
      >当前项目没有本人有效记录，请在设备详情的诊断证据页签手动保存。空记录不代表设备正常。</p
    >
    <p v-if="rows.length"
      >已选 {{ selected.length }} 个设备；标识仅用于区分历史来源，不补查设备当前名称或状态。</p
    >
    <ul v-if="rows.length">
      <li v-for="row in rows" :key="row.id">
        <label>
          <input
            type="checkbox"
            :checked="selected.includes(row.id)"
            :disabled="selectionDisabled(row)"
            :aria-label="`选择历史记录 ${row.id}`"
            @change="toggle(row.id)"
          />
          设备 {{ row.deviceId }}；记录 {{ row.id }}；采集时模型 {{ row.modelVersionId }}；保存
          {{ row.createdAt }}；可见至 {{ row.expiresAt }}
        </label>
      </li>
    </ul>
    <section
      v-if="report"
      data-testid="personal-collection-report"
      aria-label="个人历史集合事实报告"
    >
      <p
        >仅所选来源：{{ report.coverage.devices }} 个设备，{{
          report.coverage.selectedProperties
        }}
        个属性；可用 {{ report.coverage.availableValues }}，缺项
        {{ report.coverage.unavailableValues }}（其中省略
        {{ report.coverage.omittedValues }}）。缺项不代表正常。</p
      >
      <p
        >历史采集包络：{{ report.earliestCollectionAt }} 至
        {{ report.latestCollectionAt }}；不保证连续时序或同时刻快照。最早来源期限：{{
          report.expiresAt
        }}。</p
      >
      <p>报告摘要：{{ report.contentSha256 }}；下载会再次向服务端核验全部来源与当前权限。</p>
      <pre>{{ report.markdown }}</pre>
    </section>
  </ElCard>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import {
    listPersonalRecords,
    generatePersonalFactCollection,
    type PersonalRecord,
    type PersonalFactCollection
  } from '@/api/assistant-records'
  const props = defineProps<{ projectId: string }>()
  const user = useUserStore()
  const rows = ref<PersonalRecord[]>([]),
    selected = ref<string[]>([])
  const report = ref<PersonalFactCollection>()
  const busy = ref(false),
    loaded = ref(false),
    error = ref(''),
    notice = ref('')
  const readable = computed(() =>
    Boolean(
      user.isLogin &&
        user.info.userId &&
        user.info.tenantId &&
        props.projectId &&
        user.info.currentProjectId === props.projectId &&
        user.info.roles?.some((r) => ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'].includes(r))
    )
  )
  const uuid = (value: string) =>
    typeof value === 'string' && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
  const time = (value: string) => typeof value === 'string' && Number.isFinite(Date.parse(value))
  const validSource = (row: PersonalRecord) =>
    Boolean(
      row &&
        uuid(row.id) &&
        uuid(row.deviceId) &&
        uuid(row.modelVersionId) &&
        typeof row.contentSha256 === 'string' &&
        /^[0-9a-f]{64}$/.test(row.contentSha256) &&
        time(row.createdAt) &&
        time(row.expiresAt) &&
        Date.parse(row.createdAt) < Date.parse(row.expiresAt) &&
        Date.parse(row.expiresAt) > Date.now()
    )
  const sources = computed(() =>
    selected.value
      .map((id) => rows.value.find((row) => row.id === id))
      .filter((row): row is PersonalRecord => Boolean(row))
  )
  const canGenerate = computed(
    () =>
      readable.value &&
      loaded.value &&
      !busy.value &&
      selected.value.length >= 1 &&
      selected.value.length <= 5 &&
      new Set(selected.value).size === selected.value.length &&
      sources.value.length === selected.value.length &&
      sources.value.every(validSource) &&
      new Set(sources.value.map((row) => row.deviceId)).size === sources.value.length
  )
  let generation = 0,
    controller: AbortController | undefined
  function resetRun() {
    ++generation
    controller?.abort()
    controller = undefined
    report.value = undefined
    busy.value = false
    error.value = notice.value = ''
  }
  function clear() {
    resetRun()
    rows.value = []
    selected.value = []
    loaded.value = false
  }
  function begin() {
    resetRun()
    controller = new AbortController()
    busy.value = true
    const run = generation,
      epoch = currentIdentityEpoch(),
      scope = () =>
        [
          props.projectId,
          user.info.userId,
          user.info.tenantId,
          user.info.currentProjectId,
          user.info.roles?.join(','),
          selected.value.join(',')
        ].join('|'),
      started = scope()
    return {
      signal: controller.signal,
      current: () =>
        run === generation &&
        epoch === currentIdentityEpoch() &&
        readable.value &&
        scope() === started
    }
  }
  function selectionDisabled(row: PersonalRecord) {
    return (
      busy.value ||
      !readable.value ||
      !validSource(row) ||
      (!selected.value.includes(row.id) &&
        (selected.value.length >= 5 ||
          sources.value.some((source) => source.deviceId === row.deviceId)))
    )
  }
  function toggle(id: string) {
    const row = rows.value.find((row) => row.id === id)
    if (!row || selectionDisabled(row)) return
    selected.value = selected.value.includes(id)
      ? selected.value.filter((value) => value !== id)
      : [...selected.value, id]
  }
  async function refresh() {
    if (!readable.value || busy.value) return
    selected.value = []
    rows.value = []
    loaded.value = false
    const run = begin()
    try {
      const value = await listPersonalRecords(props.projectId, run.signal)
      if (!run.current()) return
      if (
        !Array.isArray(value) ||
        value.length > 100 ||
        !value.every(validSource) ||
        new Set(value.map((row) => row.id)).size !== value.length
      )
        throw new Error('INVALID_PERSONAL_CATALOG')
      rows.value = value
      loaded.value = true
    } catch {
      if (run.current()) error.value = '本人记录不可读或已到期，请核对当前权限后手动刷新。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  async function generate(download = false) {
    if (!canGenerate.value) return
    const expected = sources.value.map((row) => ({ ...row })).sort((a, b) => (a.id < b.id ? -1 : 1))
    // 时间并非响应式输入，点击时再次核验，不能使用缓存的按钮判断。
    if (!expected.every(validSource)) {
      resetRun()
      error.value = '所选来源已到期，请手动刷新。'
      return
    }
    const run = begin()
    try {
      const value = await generatePersonalFactCollection(
        props.projectId,
        expected.map((row) => row.id),
        run.signal
      )
      if (!run.current()) return
      const keys: (keyof PersonalRecord)[] = [
        'id',
        'deviceId',
        'modelVersionId',
        'createdAt',
        'expiresAt',
        'contentSha256'
      ]
      const coverage = value?.coverage
      if (
        !value ||
        value.schemaVersion !== 1 ||
        value.mode !== 'FACTS_ONLY' ||
        value.scope !== 'SELECTED_PERSONAL_RECORDS' ||
        !Array.isArray(value.sourceRecords) ||
        value.sourceRecords.length !== expected.length ||
        !value.sourceRecords.every(
          (source, i) =>
            validSource(source) && keys.every((key) => source[key] === expected[i][key])
        ) ||
        !coverage ||
        coverage.devices !== expected.length ||
        ![
          coverage.devices,
          coverage.selectedProperties,
          coverage.availableValues,
          coverage.unavailableValues,
          coverage.omittedValues
        ].every(Number.isInteger) ||
        coverage.selectedProperties < expected.length ||
        coverage.selectedProperties > expected.length * 10 ||
        coverage.availableValues < 0 ||
        coverage.unavailableValues < 0 ||
        coverage.omittedValues < 0 ||
        coverage.availableValues + coverage.unavailableValues !== coverage.selectedProperties ||
        coverage.omittedValues > coverage.unavailableValues ||
        !time(value.earliestCollectionAt) ||
        !time(value.latestCollectionAt) ||
        Date.parse(value.earliestCollectionAt) > Date.parse(value.latestCollectionAt) ||
        !time(value.expiresAt) ||
        Date.parse(value.expiresAt) !==
          Math.min(...expected.map((row) => Date.parse(row.expiresAt))) ||
        Date.parse(value.expiresAt) <= Date.now() ||
        typeof value.markdown !== 'string' ||
        !value.markdown ||
        typeof value.contentSha256 !== 'string' ||
        !/^[0-9a-f]{64}$/.test(value.contentSha256)
      )
        throw new Error('INVALID_COLLECTION_REPORT')
      const bytes = new TextEncoder().encode(value.markdown)
      if (bytes.byteLength > 1048576) throw new Error('COLLECTION_REPORT_TOO_LARGE')
      const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
      if (!run.current()) return
      if (Date.parse(value.expiresAt) <= Date.now() || !expected.every(validSource))
        throw new Error('COLLECTION_SOURCE_EXPIRED')
      const hash = Array.from(digest, (byte) => byte.toString(16).padStart(2, '0')).join('')
      if (hash !== value.contentSha256) throw new Error('COLLECTION_HASH_MISMATCH')
      report.value = value
      if (download) {
        const url = URL.createObjectURL(new Blob([bytes], { type: 'text/markdown;charset=utf-8' }))
        try {
          const link = document.createElement('a')
          link.href = url
          link.download = `personal-fact-collection-${expected.map((row) => row.id).join('_')}.md`
          link.click()
        } finally {
          setTimeout(() => URL.revokeObjectURL(url), 0)
        }
        notice.value = '集合报告已通过来源及摘要校验，已发起浏览器下载。'
      } else notice.value = '已生成所选个人历史集合事实报告，未调用模型。'
    } catch {
      if (run.current()) {
        report.value = undefined
        error.value = '集合报告未取得有效回执或校验失败，未显示或下载；请核对来源与权限后手动重试。'
      }
    } finally {
      if (run.current()) busy.value = false
    }
  }
  watch(() => selected.value.join(','), resetRun, { flush: 'sync' })
  watch(
    () => [
      props.projectId,
      user.isLogin,
      user.info.userId,
      user.info.tenantId,
      user.info.currentProjectId,
      user.info.roles?.join(','),
      currentIdentityEpoch()
    ],
    clear,
    { flush: 'sync' }
  )
  onBeforeUnmount(clear)
</script>
<style scoped>
  pre {
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
</style>
