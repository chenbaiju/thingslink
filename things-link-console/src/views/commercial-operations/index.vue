<template>
  <div class="console-page console-page--single-panel">
    <ElCard v-if="allowed" class="commercial-operations console-page__main-panel" shadow="never">
      <ElAlert
        type="info"
        :closable="false"
        title="仅审批当前租户的临时增量；数量不代表已验证的基础设施容量，最长有效期为24个UTC日历月。"
      />
      <ElAlert
        v-if="state.error"
        class="spacing"
        type="warning"
        :closable="false"
        :title="state.error"
      />
      <ElForm class="spacing" label-position="top" @submit.prevent>
        <ElFormItem label="目标租户 ID"
          ><ElInput
            v-model="tenantId"
            :disabled="state.busy || !!state.pending"
            placeholder="输入租户的完整 UUID"
            @input="state.preview = null"
        /></ElFormItem>
        <ElButton
          :disabled="state.busy || !!state.pending || storageBroken"
          @click="model.preview(tenantId.trim())"
          >读取审批摘要</ElButton
        >
      </ElForm>
      <section v-if="state.pending" class="spacing" data-testid="pending-adjustment">
        <h4>待确认申请</h4>
        <p class="console-description">租户：{{ state.pending.tenantId }}</p
        ><p class="console-description">申请编号：{{ state.pending.request.idempotencyKey }}</p>
        <p class="console-description"
          >{{ dimensionLabel(state.pending.request.dimensionCode) }} +
          {{ state.pending.request.amount }}</p
        >
        <p class="console-description"
          >{{ state.pending.request.startsAt }} 至 {{ state.pending.request.endsAt }}</p
        >
        <p class="console-description">原因：{{ state.pending.request.reason }}</p>
        <div class="console-actions">
          <ElButton :disabled="state.busy" @click="model.recover()">查询原申请</ElButton>
          <ElButton :disabled="state.busy" @click="model.retryOriginal()"
            >查询后按原内容重试</ElButton
          >
        </div>
      </section>
      <ElForm
        v-if="state.preview && !state.pending"
        class="spacing"
        label-position="top"
        @submit.prevent
      >
        <h4>{{ state.preview.tenantName }} · {{ state.preview.tenantId }}</h4>
        <ElFormItem label="调整维度"
          ><ElSelect v-model="form.dimensionCode" :disabled="state.busy"
            ><ElOption
              v-for="d in state.preview.dimensions"
              :key="d.code"
              :label="`${dimensionLabel(d.code)}（当前 ${d.effectiveAmount} ${d.unit}）`"
              :value="d.code ?? ''" /></ElSelect
        ></ElFormItem>
        <ElFormItem label="增加数量"
          ><ElInput
            v-model="form.amount"
            :disabled="state.busy"
            inputmode="numeric"
            placeholder="完整十进制整数，例如 9007199254740993"
        /></ElFormItem>
        <ElFormItem label="生效时间（UTC）"
          ><ElInput
            v-model="form.startsAt"
            :disabled="state.busy"
            placeholder="2026-09-21T00:00:00Z"
        /></ElFormItem>
        <ElFormItem label="到期时间（UTC）"
          ><ElInput v-model="form.endsAt" :disabled="state.busy" placeholder="2026-10-21T00:00:00Z"
        /></ElFormItem>
        <ElFormItem label="审批原因／工单"
          ><ElInput v-model="form.reason" type="textarea" maxlength="512" :disabled="state.busy"
        /></ElFormItem>
        <ElButton type="primary" :disabled="state.busy || storageBroken" @click="confirmSubmit"
          >核对并提交审批</ElButton
        >
      </ElForm>
      <section v-if="!state.pending" class="spacing">
        <h4>查询既有申请</h4>
        <div class="console-toolbar">
          <ElButton :disabled="state.busy" @click="readPending">读取本机待确认记录</ElButton>
          <ElInput
            v-model="lookupKey"
            placeholder="原申请编号（含已到期或已撤销）"
            :disabled="state.busy"
          />
          <ElButton
            class="spacing"
            :disabled="state.busy"
            @click="model.lookup(tenantId.trim(), lookupKey.trim())"
            >查询申请状态</ElButton
          >
        </div>
      </section>
      <section v-if="state.result" class="spacing" data-testid="adjustment-result">
        <h4>申请事实 · {{ statusLabel(state.result.status) }}</h4>
        <p class="console-description">租户：{{ state.result.tenantId }}</p
        ><p class="console-description">申请编号：{{ state.result.idempotencyKey }}</p>
        <p class="console-description"
          >{{ dimensionLabel(state.result.dimensionCode) }} + {{ state.result.amount }}
          {{ state.result.unit }}</p
        >
        <p class="console-description">{{ state.result.startsAt }} 至 {{ state.result.endsAt }}</p>
        <p class="console-description">审批人：{{ state.result.operatorId }}</p
        ><p class="console-description">原因：{{ state.result.reason }}</p>
        <template v-if="['ACTIVE', 'PENDING'].includes(state.result.status ?? '')">
          <div class="console-toolbar">
            <ElInput
              v-model="revokeReason"
              placeholder="撤销原因"
              :disabled="state.busy"
              maxlength="512"
            />
            <ElButton class="spacing" type="danger" :disabled="state.busy" @click="confirmRevoke"
              >撤销此调整</ElButton
            >
          </div>
        </template>
      </section>
    </ElCard>
    <ElResult
      v-else
      icon="warning"
      title="需要平台商业运营权限"
      sub-title="项目角色不授予此权限，请联系平台授权管理员。"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import {
    fetchCommercialPreview,
    createCommercialAdjustment,
    recoverCommercialAdjustment,
    revokeCommercialAdjustment
  } from '@/api/commercial'
  import { commercialState, createCommercialModel } from '@/features/commercial/adjustment-model'
  import { commercialPendingStorage } from '@/features/commercial/pending-storage'
  import { dimensionLabel as quotaDimensionLabel } from '@/features/plan/catalog-model'

  const user = useUserStore()
  const allowed = computed(() => user.info.buttons?.includes('commercial:adjust') ?? false)
  const tenantId = ref(''),
    lookupKey = ref(''),
    revokeReason = ref('')
  const storageBroken = ref(false)
  const state = reactive(commercialState())
  const form = reactive({
    dimensionCode: 'DEVICES_MAX',
    amount: '',
    startsAt: new Date().toISOString(),
    endsAt: '',
    reason: ''
  })
  const pendingStorage = () => commercialPendingStorage(localStorage, String(user.info.userId))
  const model = createCommercialModel(
    {
      preview: fetchCommercialPreview,
      create: createCommercialAdjustment,
      recover: recoverCommercialAdjustment,
      revoke: revokeCommercialAdjustment,
      save: (value) => {
        if (value) pendingStorage().save(value)
        else if (state.pending) pendingStorage().remove(state.pending.request.idempotencyKey)
      },
      key: () => crypto.randomUUID()
    },
    state
  )
  watch(
    () => [user.info.userId, allowed.value],
    () => {
      model.invalidate()
      tenantId.value = ''
      lookupKey.value = ''
      revokeReason.value = ''
      storageBroken.value = false
      if (!allowed.value) return
      try {
        const saved = pendingStorage().first()
        if (saved) {
          tenantId.value = model.restore(saved).tenantId
        }
      } catch {
        storageBroken.value = true
        state.error = '本地申请记录无法读取，请先核对原申请；已阻止新的授予。'
      }
    },
    { immediate: true }
  )
  function readPending() {
    if (state.busy || state.pending || !allowed.value) return
    try {
      const saved = pendingStorage().first()
      if (saved) {
        tenantId.value = model.restore(saved).tenantId
      }
    } catch {
      storageBroken.value = true
      state.error = '本地申请记录无法读取，请先核对原申请。'
    }
  }
  watch(
    () => state.pending,
    (pending, previous) => {
      if (!pending && previous) readPending()
    }
  )
  onBeforeUnmount(() => model.invalidate())
  const dimensionLabel = (code?: string) => quotaDimensionLabel(code ?? '')
  const statusLabel = (status?: string) =>
    ({ ACTIVE: '已受理', PENDING: '等待订阅恢复', EXPIRED: '已到期', CANCELLED: '已撤销' })[
      status ?? ''
    ] ??
    status ??
    '未知状态'
  async function confirmSubmit() {
    const preview = state.preview
    if (!preview || state.busy || state.pending) return
    const frozen = { ...form }
    try {
      await ElMessageBox.confirm(
        `目标：${preview.tenantName}（${preview.tenantId}）\n${dimensionLabel(frozen.dimensionCode)} 增加 ${frozen.amount}\n${frozen.startsAt} 至 ${frozen.endsAt}\n原因：${frozen.reason}`,
        '确认本次有限期调整',
        { confirmButtonText: '确认审批', cancelButtonText: '返回修改' }
      )
      if (state.preview !== preview || !allowed.value) return
      await model.submit(frozen)
      if (state.result?.idempotencyKey) lookupKey.value = state.result.idempotencyKey
    } catch {
      /* 取消确认不发出请求。 */
    }
  }
  async function confirmRevoke() {
    const original = state.result,
      reason = revokeReason.value
    if (!original || state.busy) return
    try {
      await ElMessageBox.confirm(
        `撤销申请 ${original.idempotencyKey}，原因：${reason}`,
        '确认撤销',
        { confirmButtonText: '撤销调整', cancelButtonText: '返回' }
      )
      if (state.result === original && allowed.value) await model.revoke(reason)
    } catch {
      /* 取消确认不改变事实。 */
    }
  }
</script>

<style scoped>
  .spacing {
    margin-top: 10px;
  }
  p {
    overflow-wrap: anywhere;
  }
  .el-select {
    width: 100%;
  }
</style>
