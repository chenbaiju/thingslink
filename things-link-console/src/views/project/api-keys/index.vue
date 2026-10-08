<template>
  <div class="console-page api-keys-page console-page--single-panel">
    <ConsoleWorkspaceHeader
      title="API Key"
      description="为系统集成签发、轮换和撤销密钥。明文仅在签发时展示，请及时保存。"
      :links="[
        { label: 'Webhook', path: '/project/webhooks', permission: 'integration:manage' },
        { label: '用量与项目设置', path: '/project/settings', permission: 'quota:read' }
      ]"
    />
    <div v-if="allowed" class="console-toolbar console-page-actions">
      <ElButton type="primary" :icon="Plus" :disabled="busy || !!pending" @click="openForm()">
        签发 Key
      </ElButton>
    </div>
    <ElCard class="console-page__main-panel">
      <ElAlert v-if="!allowed" title="请选择有集成管理权限的项目" :closable="false" />
      <template v-else>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" />
        <ElTable :data="items" row-key="id" v-loading="busy">
          <ElTableColumn show-overflow-tooltip prop="name" label="名称" />
          <ElTableColumn show-overflow-tooltip prop="id" label="Key ID" min-width="280" />
          <ElTableColumn show-overflow-tooltip prop="status" label="状态"
            ><template #default="{ row }"><ConsoleStatusTag :status="row.status" /></template
          ></ElTableColumn>
          <ElTableColumn label="权限"
            ><template #default="{ row }">{{ row.scopes?.join(', ') }}</template></ElTableColumn
          >
          <ElTableColumn label="来源 IP"
            ><template #default="{ row }">{{ row.ipCidrs?.join(', ') }}</template></ElTableColumn
          >
          <ElTableColumn show-overflow-tooltip prop="expiresAt" label="到期时间" min-width="180" />
          <ElTableColumn class-name="console-table-actions-cell" label="操作" width="104"
            ><template #default="{ row }">
              <ConsoleTableAction
                type="primary"
                :disabled="busy || !!pending || row.status !== 'ACTIVE'"
                @click="openForm(row)"
                label="轮换"
                icon="ri:key-2-line"
              />
              <ConsoleTableAction
                type="danger"
                :disabled="busy || !!pending || row.status === 'REVOKED'"
                @click="revoke(row)"
                label="撤销"
                icon="ri:close-circle-line"
              /> </template
          ></ElTableColumn>
        </ElTable>
        <ElButton v-if="next" :disabled="busy" @click="load(true)">加载更多</ElButton>
        <section class="recovery">
          <h3 class="console-heading">查询原操作</h3>
          <p class="console-description"
            >响应丢失时先查询原操作，避免重复发钥。查询只返回元数据；秘密丢失后请明确轮换或撤销。</p
          >
          <p class="console-description" v-if="pending"
            >待确认操作：<code data-testid="pending-operation">{{ pending }}</code></p
          >
          <div class="console-toolbar">
            <ElInput
              v-model="recoveryId"
              aria-label="原操作 ID"
              placeholder="输入原操作 ID"
              :disabled="busy"
            />
            <ElButton :disabled="busy || !(pending || recoveryId)" @click="recover"
              >查询操作结果</ElButton
            >
            <ElButton v-if="pending" :disabled="busy" @click="discard">结束本地尝试</ElButton>
          </div>
          <ElAlert
            v-if="recovered"
            data-testid="recovered-key"
            :closable="false"
            :title="`已确认：${recovered.name}（${recovered.status}），Key ID：${recovered.id}。查询不恢复秘密。`"
          />
        </section>
      </template>
    </ElCard>
    <ElDialog
      class="console-dialog"
      v-model="formVisible"
      :title="target ? '轮换 API Key' : '签发 API Key'"
      :close-on-click-modal="false"
    >
      <p class="console-description" v-if="target"
        >成功后旧 Key 立即撤销，旧 Key 的命令结果不会转移给新 Key。</p
      >
      <ElAlert
        v-if="pending && !busy"
        title="操作结果尚未确认。请关闭填写窗口，在页面查询原操作结果。"
        type="warning"
        :closable="false"
      />
      <ElAlert v-if="error" :title="error" type="error" :closable="false" />
      <ElForm label-position="top" @submit.prevent="save">
        <ElFormItem label="名称"
          ><ElInput v-model="name" aria-label="Key 名称" maxlength="80" :disabled="busy"
        /></ElFormItem>
        <ElFormItem label="权限"
          ><ElCheckboxGroup v-model="scopes" :disabled="busy">
            <ElCheckbox value="device:read">device:read</ElCheckbox
            ><ElCheckbox value="device:control">device:control</ElCheckbox
            ><ElCheckbox value="alarm:read">alarm:read</ElCheckbox>
          </ElCheckboxGroup></ElFormItem
        >
        <ElFormItem label="来源 IP/CIDR，每行一个"
          ><ElInput
            v-model="cidrs"
            type="textarea"
            aria-label="来源 IP/CIDR"
            :disabled="busy"
            placeholder="例如 203.0.113.10/32；不能使用主机名"
        /></ElFormItem>
        <ElFormItem label="到期时间（本地时区，最长366天）"
          ><ElInput v-model="expires" type="datetime-local" aria-label="到期时间" :disabled="busy"
        /></ElFormItem>
        <div class="console-actions">
          <ElButton type="primary" :disabled="busy || !!pending" @click="save">确认签发</ElButton>
          <ElButton :disabled="busy" @click="formVisible = false">关闭填写窗口</ElButton>
        </div>
      </ElForm>
    </ElDialog>
    <ElDialog
      class="console-dialog"
      v-model="secretVisible"
      title="仅本次展示完整 Key"
      :close-on-click-modal="false"
      @close="clearSecret"
    >
      <p class="console-description"
        >请妥善保存到服务端秘密管理配置，关闭后无法再次查看。不要发送给其他人或写入代码仓库。</p
      >
      <ElInput :model-value="secret" readonly aria-label="完整 API Key" />
      <ElButton @click="clearSecret">已保存，关闭</ElButton>
    </ElDialog>
  </div>
</template>
<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'
  import { Plus } from '@element-plus/icons-vue'

  import ConsoleStatusTag from '@/components/ConsoleStatusTag.vue'
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import * as api from '@/api/api-key'

  defineOptions({ name: 'ProjectApiKeys' })
  const user = useUserStore()
  const project = computed(() => user.info.currentProjectId ?? '')
  const allowed = computed(
    () => user.isLogin && !!project.value && !!user.info.buttons?.includes('integration:manage')
  )
  const items = ref<api.ApiKey[]>([]),
    next = ref<string>(),
    recovered = ref<api.ApiKey>()
  const busy = ref(false),
    error = ref(''),
    formVisible = ref(false),
    secretVisible = ref(false)
  const secret = ref(''),
    pending = ref(''),
    recoveryId = ref(''),
    target = ref<string>()
  const name = ref(''),
    scopes = ref<string[]>(['device:read']),
    cidrs = ref(''),
    expires = ref('')
  let generation = 0
  function clearSecret() {
    secret.value = ''
    secretVisible.value = false
  }
  function clear() {
    generation++
    clearSecret()
    formVisible.value = false
    busy.value = false
    error.value = ''
    items.value = []
    next.value = undefined
    recovered.value = undefined
    pending.value = ''
    recoveryId.value = ''
    target.value = undefined
    name.value = ''
    scopes.value = ['device:read']
    cidrs.value = ''
    expires.value = ''
  }
  async function run(work: (current: () => boolean, pid: string) => Promise<void>) {
    if (!allowed.value || busy.value) return
    const epoch = generation,
      pid = project.value
    const current = () => epoch === generation && pid === project.value && allowed.value
    busy.value = true
    error.value = ''
    try {
      await work(current, pid)
    } catch (reason) {
      if (current() && reason !== 'cancel' && reason !== 'close') {
        if (reason instanceof HttpError && [80001, 401, 403, 10003, 50001].includes(reason.code))
          clear()
        else
          error.value = pending.value
            ? '操作结果尚未确认，请查询原操作；不要重复签发。'
            : reason instanceof Error
              ? reason.message
              : '操作失败'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  async function refresh(current: () => boolean, pid: string, more = false) {
    const page = await api.listKeys(pid, more ? next.value : undefined)
    if (current()) {
      items.value = more ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
      next.value = page.nextCursor ?? undefined
    }
  }
  function load(more = false) {
    return run((current, pid) => refresh(current, pid, more))
  }
  function openForm(row?: api.ApiKey) {
    if (!allowed.value || busy.value || pending.value) return
    clearSecret()
    recovered.value = undefined
    target.value = row?.id
    name.value = row?.name ?? ''
    scopes.value = [...(row?.scopes ?? ['device:read'])]
    cidrs.value = row?.ipCidrs?.join('\n') ?? ''
    const at = new Date(Date.now() + 7 * 86400000)
    expires.value = new Date(at.getTime() - at.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16)
    formVisible.value = true
  }
  function save() {
    return run(async (current, pid) => {
      if (pending.value) return
      const at = new Date(expires.value)
      if (
        !name.value.trim() ||
        !scopes.value.length ||
        !cidrs.value.trim() ||
        !Number.isFinite(at.getTime())
      )
        throw new Error('请填写名称、权限、来源IP和有效到期时间')
      const operationId = crypto.randomUUID()
      const data: api.CreateKey = {
        operationId,
        name: name.value.trim(),
        scopes: [...scopes.value],
        ipCidrs: cidrs.value.split(/\s+/).filter(Boolean),
        expiresAt: at.toISOString()
      }
      pending.value = operationId
      recoveryId.value = operationId
      const result = await api.createKey(pid, data, target.value)
      if (!current()) return
      pending.value = ''
      formVisible.value = false
      if (result.secret) {
        secret.value = result.secret
        secretVisible.value = true
      } else recovered.value = result.key
      await refresh(current, pid)
    })
  }
  function recover() {
    return run(async (current, pid) => {
      const id = pending.value || recoveryId.value.trim()
      if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(id))
        throw new Error('请输入有效的操作ID')
      const value = await api.recoverKey(pid, id)
      if (!current()) return
      clearSecret()
      recovered.value = value
      pending.value = ''
      formVisible.value = false
      await refresh(current, pid)
    })
  }
  function revoke(row: api.ApiKey) {
    return run(async (current, pid) => {
      if (pending.value || !row.id) return
      await ElMessageBox.confirm('撤销后所有使用此 Key 的新请求将失败，是否继续？', '撤销 API Key')
      if (!current()) return
      const operationId = crypto.randomUUID()
      pending.value = operationId
      recoveryId.value = operationId
      const value = await api.revokeKey(pid, row.id, operationId)
      if (!current()) return
      clearSecret()
      recovered.value = value
      pending.value = ''
      await refresh(current, pid)
    })
  }
  function discard() {
    return run(async (current) => {
      await ElMessageBox.confirm(
        '结束本地尝试不会取消已受理的操作。请保留操作ID，查询列表并处理可能已签发的 Key。',
        '结束本地尝试'
      )
      if (current()) {
        pending.value = ''
        formVisible.value = false
      }
    })
  }
  watch(
    () => [project.value, allowed.value, user.info.userId, user.isLogin],
    () => {
      clear()
      if (allowed.value) void load()
    },
    { immediate: true }
  )
  onBeforeUnmount(clear)
</script>
<style scoped>
  .recovery {
    margin-top: 10px;
  }
  .recovery .el-input {
    max-width: 400px;
    margin-right: 12px;
  }
</style>
