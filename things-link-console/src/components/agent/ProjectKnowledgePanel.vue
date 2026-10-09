<template>
  <ElCard v-if="readable" shadow="never" class="knowledge-panel" data-testid="project-knowledge">
    <template #header><h4 class="console-heading">受控项目知识</h4></template>
    <ElAlert
      type="info"
      show-icon
      :closable="false"
      title="管理员逐次批准的项目共用资料；个人排查记录仍仅本人可见。这里只做本地字面检索，不调用模型、不发送外部服务。"
    />
    <ElAlert v-if="error" type="error" :closable="false" show-icon :title="error" />
    <ElAlert v-if="notice" type="info" show-icon :closable="false" :title="notice" />
    <div class="console-toolbar">
      <ElButton :disabled="busy" @click="refresh">刷新批准资料</ElButton>
      <ElButton v-if="manager" :disabled="!writable" @click="newSource">准备新来源</ElButton>
    </div>
    <ElAlert
      v-if="loaded && !rows.length"
      type="info"
      show-icon
      :closable="false"
      title="当前项目尚无批准资料。"
    />
    <ul v-if="rows.length" class="knowledge-panel__sources">
      <li v-for="row in rows" :key="row.id">
        <span>{{ row.sourceKey }}（版本 {{ row.versionNumber }}，{{ row.createdAt }}）</span>
        <ElButton :disabled="busy" @click="open(row)">查看当前正文</ElButton>
      </li>
    </ul>
    <template v-if="detail">
      <p>版本：{{ detail.source.id }}；摘要：{{ detail.source.contentSha256 }}</p>
      <pre class="knowledge-panel__text">{{ detail.content }}</pre>
      <ElButton v-if="manager" type="danger" :disabled="!writable" @click="remove"
        >删除此来源全部版本</ElButton
      >
    </template>
    <template v-if="manager">
      <ElAlert
        type="info"
        show-icon
        :closable="false"
        title="最多100个来源，每来源20个版本；正文需人工筛选脱敏。项目归档或状态未知时不能发布。替换前先查看当前正文，提交结果不明时先刷新核对。"
      />
      <ElDivider content-position="left">发布项目共享版本</ElDivider>
      <ElForm label-position="top" @submit.prevent>
        <ElFormItem label="知识来源标识">
          <ElInput
            v-model="sourceKey"
            :disabled="!writable || !!detail"
            maxlength="64"
            aria-label="知识来源标识"
            placeholder="来源标识，如 pump_guide"
          />
        </ElFormItem>
        <ElFormItem label="批准知识正文">
          <ElInput
            v-model="content"
            type="textarea"
            :rows="6"
            :disabled="!writable"
            aria-label="批准知识正文"
            placeholder="已筛选脱敏的纯文本正文"
          />
        </ElFormItem>
      </ElForm>
      <p class="knowledge-panel__metadata">规范正文：{{ contentBytes }} / 16384 UTF-8字节</p>
      <div class="knowledge-panel__approval">
        <ElCheckbox v-model="approved" :disabled="!writable"
          >我已筛选脱敏，并批准本项目全部当前成员读取本次正文</ElCheckbox
        >
        <ElButton type="primary" :disabled="!canPublish" @click="publish"
          >发布项目共享版本</ElButton
        >
      </div>
    </template>
    <ElDivider content-position="left">本地字面检索</ElDivider>
    <ElForm label-position="top" @submit.prevent>
      <ElFormItem label="字面关键词">
        <ElInput
          v-model="keywords"
          type="textarea"
          :rows="2"
          :disabled="busy"
          aria-label="知识字面关键词"
          placeholder="每行一个字面关键词，1至5个，各2至32字"
        />
      </ElFormItem>
    </ElForm>
    <div class="console-toolbar">
      <ElButton :disabled="!canSearch" @click="search">手动本地检索</ElButton>
    </div>
    <template v-if="result">
      <ElAlert
        type="info"
        show-icon
        :closable="false"
        :title="
          result.state === 'NO_SOURCES'
            ? '无批准资料，不能给出知识依据。'
            : result.state === 'NO_MATCH'
              ? '没有字面命中，不能推断设备正常。'
              : '以下为字面原文依据，可能矛盾或过时，不是诊断结论。'
        "
      />
      <article v-for="hit in result.hits" :key="hit.source.id">
        <p>{{ hit.source.sourceKey }} / 版本 {{ hit.source.versionNumber }}：{{ hit.source.id }}</p>
        <p
          >正文摘要：{{ hit.source.contentSha256 }}；码点区间 [{{ hit.startCodePoint }},
          {{ hit.endCodePoint }}){{ hit.truncated ? '（片段已截断）' : '' }}</p
        >
        <pre class="knowledge-panel__text">{{ hit.text }}</pre>
      </article>
    </template>
  </ElCard>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { fetchProjects } from '@/api/project'
  import * as api from '@/api/assistant-knowledge'
  const props = defineProps<{ projectId: string }>()
  const user = useUserStore()
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
  const manager = computed(
    () => readable.value && Boolean(user.info.roles?.some((r) => ['OWNER', 'ADMIN'].includes(r)))
  )
  const rows = ref<api.KnowledgeSource[]>([])
  const detail = ref<api.KnowledgeDetail>()
  const result = ref<api.KnowledgeSearch>()
  const sourceKey = ref(''),
    content = ref(''),
    keywords = ref(''),
    error = ref(''),
    notice = ref(''),
    projectStatus = ref('')
  const busy = ref(false),
    loaded = ref(false),
    uncertain = ref(false),
    approved = ref(false)
  const normalized = computed(() => content.value.replace(/\r\n?/g, '\n').normalize('NFC'))
  const contentBytes = computed(() => new TextEncoder().encode(normalized.value).length)
  const writable = computed(
    () =>
      manager.value &&
      loaded.value &&
      projectStatus.value === 'ACTIVE' &&
      !busy.value &&
      !uncertain.value
  )
  const keys = computed(() => keywords.value.split('\n').map((k) => k.trim().normalize('NFC')))
  const validKeywords = computed(
    () =>
      keys.value.length >= 1 &&
      keys.value.length <= 5 &&
      new Set(keys.value).size === keys.value.length &&
      keys.value.every(
        (k) => Array.from(k).length >= 2 && Array.from(k).length <= 32 && validCharacters(k, false)
      )
  )
  const canSearch = computed(() => readable.value && !busy.value && validKeywords.value)
  const canPublish = computed(
    () =>
      writable.value &&
      approved.value &&
      /^[a-z][a-z0-9_-]{0,63}$/.test(sourceKey.value) &&
      normalized.value.trim().length > 0 &&
      contentBytes.value <= 16384 &&
      validCharacters(normalized.value, true) &&
      (detail.value
        ? detail.value.source.sourceKey === sourceKey.value &&
          detail.value.source.versionNumber < 20
        : rows.value.length < 100 && !rows.value.some((r) => r.sourceKey === sourceKey.value))
  )
  let generation = 0,
    controller: AbortController | undefined
  function validCharacters(text: string, lines: boolean) {
    return Array.from(text).every((c) => {
      const code = c.codePointAt(0)!
      return (
        !(code >= 0xd800 && code <= 0xdfff) &&
        (!(code <= 31 || (code >= 127 && code <= 159)) || (lines && (code === 9 || code === 10)))
      )
    })
  }
  const validSource = (r: api.KnowledgeSource) =>
    r &&
    /^[a-z][a-z0-9_-]{0,63}$/.test(r.sourceKey) &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(r.id) &&
    /^[0-9a-f]{64}$/.test(r.contentSha256) &&
    Number.isInteger(r.versionNumber) &&
    r.versionNumber >= 1 &&
    r.versionNumber <= 20 &&
    typeof r.createdAt === 'string' &&
    Number.isFinite(Date.parse(r.createdAt))
  function draftClear() {
    sourceKey.value = content.value = ''
    approved.value = false
    detail.value = undefined
  }
  function clear() {
    ++generation
    controller?.abort()
    controller = undefined
    rows.value = []
    result.value = undefined
    draftClear()
    keywords.value = error.value = notice.value = projectStatus.value = ''
    busy.value = loaded.value = uncertain.value = false
  }
  function begin() {
    ++generation
    controller?.abort()
    controller = new AbortController()
    busy.value = true
    error.value = notice.value = ''
    result.value = undefined
    const run = generation,
      epoch = currentIdentityEpoch(),
      scope = () =>
        [
          props.projectId,
          user.info.userId,
          user.info.tenantId,
          user.info.currentProjectId,
          user.info.roles?.join(',')
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
  async function refresh() {
    if (!readable.value || busy.value) return
    draftClear()
    rows.value = []
    loaded.value = false
    projectStatus.value = ''
    const run = begin()
    try {
      const [sources, projects] = await Promise.all([
        api.listKnowledge(props.projectId, run.signal),
        fetchProjects()
      ])
      if (!run.current()) return
      if (
        !Array.isArray(sources) ||
        sources.length > 100 ||
        !sources.every(validSource) ||
        new Set(sources.map((r) => r.sourceKey)).size !== sources.length ||
        new Set(sources.map((r) => r.id)).size !== sources.length
      )
        throw new Error('INVALID_CATALOG')
      rows.value = sources
      loaded.value = true
      uncertain.value = false
      projectStatus.value = projects.find((p) => p.id === props.projectId)?.status ?? ''
    } catch {
      if (run.current()) error.value = '批准资料不可读，请确认权限后手动刷新。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  function newSource() {
    if (writable.value) {
      draftClear()
      result.value = undefined
    }
  }
  async function open(row: api.KnowledgeSource) {
    if (!readable.value || busy.value || !rows.value.some((r) => r.id === row.id)) return
    draftClear()
    const run = begin()
    try {
      const value = await api.readKnowledge(props.projectId, row.sourceKey, run.signal)
      if (!run.current()) return
      if (
        !validSource(value.source) ||
        value.source.id !== row.id ||
        value.source.sourceKey !== row.sourceKey ||
        value.source.contentSha256 !== row.contentSha256 ||
        typeof value.content !== 'string' ||
        !value.content.trim() ||
        new TextEncoder().encode(value.content).length > 16384 ||
        !validCharacters(value.content, true) ||
        value.content !== value.content.replace(/\r\n?/g, '\n').normalize('NFC')
      )
        throw new Error('INVALID_DETAIL')
      detail.value = value
      sourceKey.value = row.sourceKey
      content.value = value.content
    } catch {
      if (run.current()) error.value = '当前版本不可读或已更新，请先刷新目录。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  async function publish() {
    if (!canPublish.value) return
    const key = sourceKey.value,
      expected = detail.value?.source.id ?? null,
      body = normalized.value
    approved.value = false
    const run = begin()
    try {
      const value = await api.publishKnowledge(props.projectId, key, expected, body, run.signal)
      if (!run.current()) return
      if (
        !validSource(value) ||
        value.sourceKey !== key ||
        value.id === expected ||
        rows.value.some((r) => r.id === value.id) ||
        value.versionNumber !== (detail.value?.source.versionNumber ?? 0) + 1
      )
        throw new Error('INVALID_RECEIPT')
      rows.value = rows.value.filter((r) => r.sourceKey !== key).concat(value)
      draftClear()
      notice.value = '版本已发布并批准当前项目成员读取。'
    } catch {
      if (run.current()) {
        uncertain.value = true
        draftClear()
        error.value = '发布未取得可验证回执，请先刷新核对；不会自动重发。'
      }
    } finally {
      if (run.current()) busy.value = false
    }
  }
  async function remove() {
    if (!writable.value || !detail.value) return
    const source = detail.value.source,
      run = begin()
    try {
      await api.deleteKnowledge(props.projectId, source.sourceKey, source.id, run.signal)
      if (!run.current()) return
      rows.value = rows.value.filter((r) => r.sourceKey !== source.sourceKey)
      draftClear()
      notice.value = '此来源全部版本已删除，旧引用不可再读取。'
    } catch {
      if (run.current()) {
        uncertain.value = true
        draftClear()
        error.value = '删除未取得回执，请先刷新核对；不会自动重发。'
      }
    } finally {
      if (run.current()) busy.value = false
    }
  }
  async function search() {
    if (!canSearch.value) return
    const run = begin()
    try {
      const value = await api.searchKnowledge(props.projectId, keys.value, run.signal)
      if (!run.current()) return
      if (
        value.projectId !== props.projectId ||
        value.mode !== 'LOCAL_LITERAL' ||
        value.externalAllowed !== false ||
        typeof value.collectedAt !== 'string' ||
        !Number.isFinite(Date.parse(value.collectedAt)) ||
        !['NO_SOURCES', 'NO_MATCH', 'MATCHED'].includes(value.state) ||
        !Array.isArray(value.hits) ||
        value.hits.length > 3 ||
        (value.state === 'MATCHED') !== value.hits.length > 0 ||
        new Set(value.hits.map((h) => h.source.id)).size !== value.hits.length ||
        !value.hits.every(
          (h) =>
            validSource(h.source) &&
            typeof h.text === 'string' &&
            keys.value.some((k) => h.text.includes(k)) &&
            Array.from(h.text).length >= 2 &&
            Array.from(h.text).length <= 256 &&
            new TextEncoder().encode(h.text).length <= 1024 &&
            Number.isInteger(h.startCodePoint) &&
            h.startCodePoint >= 0 &&
            Number.isInteger(h.endCodePoint) &&
            h.endCodePoint - h.startCodePoint === Array.from(h.text).length &&
            typeof h.truncated === 'boolean'
        )
      )
        throw new Error('INVALID_SEARCH')
      result.value = value
    } catch {
      if (run.current()) error.value = '检索不可用或引用合同不符，请手动重试；不能视为无命中。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
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
    clear
  )
  watch([sourceKey, content], () => {
    approved.value = false
  })
  onBeforeUnmount(clear)
</script>
<style scoped lang="scss">
  .knowledge-panel {
    margin-bottom: 10px;
    overflow-wrap: anywhere;
  }
  .knowledge-panel__sources {
    padding: 0;
    list-style: none;
    li {
      display: flex;
      gap: 10px;
      align-items: center;
      justify-content: space-between;
      padding: 10px 0;
      border-bottom: 1px solid var(--el-border-color-lighter);
    }
    span {
      min-width: 0;
    }
    .el-button {
      flex-shrink: 0;
    }
  }
  .knowledge-panel__approval {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    :deep(.el-checkbox) {
      height: auto;
      white-space: normal;
    }
    :deep(.el-checkbox__label) {
      line-height: 20px;
      white-space: normal;
    }
  }
  .knowledge-panel__metadata {
    margin: 0 0 10px;
    font-size: 12px;
    color: var(--art-text-gray-500);
  }
  .knowledge-panel__text {
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
</style>
