<template>
  <div class="console-page">
    <ConsoleWorkspaceHeader
      title="自部署申请 · 责任审核"
      description="核对申请摘要与材料并记录审核事实；审核资格独立于运营身份与项目角色。"
      :links="[
        { label: '待审申请', path: '/self-hosted-enrollment', permission: 'commercial:adjust' }
      ]"
    />
    <ElCard v-if="allowed" shadow="never">
      <ElAlert
        type="warning"
        :closable="false"
        title="一名授权责任人核验是签发前置；第二人复核可选。审核记录仍不代表客户归属已确认，也不会自动签发。"
      />
      <ElAlert v-if="error" class="spacing" type="error" :closable="false" :title="error" />
      <div class="spacing">
        <ElInput
          v-model="requestId"
          data-testid="review-request-id"
          placeholder="输入待审申请 ID"
        />
        <ElButton :disabled="busy || !validRequestId" @click="loadDetail">读取申请摘要</ElButton>
      </div>
      <section v-if="detail" class="spacing" data-testid="review-detail">
        <h4>不可变申请摘要</h4>
        <p>申请：{{ detail.requestId }}</p>
        <p>部署：{{ detail.deploymentId }}</p>
        <p>声明租户（未核验）：{{ detail.claimedTenantId }}</p>
        <p>部署公钥 SHA-256：{{ detail.publicKeySha256 }}</p>
        <p>申请 SHA-256：{{ detail.requestSha256 }}</p>
        <p>已批准修订：{{ detail.revisionId }}</p>
        <p>修订 SHA-256：{{ detail.revisionSha256 }}</p>
        <p>现有审核事实：{{ detail.attestationCount }}（至少 1 份；最多 2 份）</p>
        <h4>独立核对后的记录</h4>
        <p>请对照外部组织材料和原申请核验上述标识及摘要，再填写材料索引、摘要和档位。</p>
        <ElInput
          v-model.trim="organizationReference"
          data-testid="review-organization"
          placeholder="客户组织材料参考标识"
        />
        <ElInput
          v-model.trim="evidenceSha256"
          data-testid="review-evidence"
          class="spacing"
          placeholder="外部证据 SHA-256（64 位小写十六进制）"
        />
        <ElSelect
          v-model="tier"
          data-testid="review-tier"
          class="spacing"
          placeholder="选择核验档位"
        >
          <ElOption label="免费版 FREE" value="FREE" />
          <ElOption label="标准版 STANDARD" value="STANDARD" />
          <ElOption label="企业版 ENTERPRISE" value="ENTERPRISE" />
          <ElOption label="专业版 PROFESSIONAL" value="PROFESSIONAL" />
        </ElSelect>
        <div class="spacing">
          <ElCheckbox v-model="confirmed" data-testid="review-confirm">
            我已独立核对部署、租户、两项申请摘要、外部组织证据和已批准修订
          </ElCheckbox>
        </div>
        <ElButton type="primary" :disabled="busy || !canSubmit" @click="attest">
          记录独立审核事实
        </ElButton>
        <p v-if="progress" data-testid="review-progress">
          已记录 {{ progress.attestationCount }} 份审核事实；
          {{
            progress.readyForIssuanceReview
              ? '审核记录已具备，仍须核对客户归属与签发资格；'
              : '审核记录尚未就绪；'
          }}
          不构成授权签发或现场激活。
        </p>
      </section>
    </ElCard>
    <ElResult
      v-else
      icon="warning"
      title="需要独立审核资格"
      sub-title="运营资格和项目角色均不授予审核权。"
    />
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import {
    fetchReviewDetail,
    submitReview,
    type ReviewDetail,
    type ReviewProgress
  } from '@/api/self-hosted-review'

  const user = useUserStore()
  const allowed = computed(() => user.info.buttons?.includes('self_hosted:review') ?? false)
  const requestId = ref('')
  const detail = ref<ReviewDetail | null>(null)
  const organizationReference = ref('')
  const evidenceSha256 = ref('')
  const tier = ref('')
  const confirmed = ref(false)
  const progress = ref<ReviewProgress | null>(null)
  const error = ref('')
  const busy = ref(false)
  const validRequestId = computed(() =>
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId.value.trim())
  )
  const canSubmit = computed(
    () =>
      detail.value !== null &&
      /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/.test(organizationReference.value) &&
      /^[0-9a-f]{64}$/.test(evidenceSha256.value) &&
      ['FREE', 'STANDARD', 'ENTERPRISE', 'PROFESSIONAL'].includes(tier.value) &&
      confirmed.value
  )
  let generation = 0

  watch(
    () => [user.info.userId, allowed.value],
    () => {
      generation++
      requestId.value = ''
      detail.value = null
      organizationReference.value = ''
      evidenceSha256.value = ''
      tier.value = ''
      confirmed.value = false
      progress.value = null
      error.value = ''
      busy.value = false
    }
  )
  watch(requestId, (value) => {
    if (detail.value && value.trim() !== detail.value.requestId) {
      generation++
      detail.value = null
      progress.value = null
      confirmed.value = false
      busy.value = false
    }
  })
  onBeforeUnmount(() => generation++)

  async function loadDetail() {
    if (!allowed.value || busy.value || !validRequestId.value) return
    const selected = requestId.value.trim()
    const current = ++generation
    busy.value = true
    error.value = ''
    detail.value = null
    progress.value = null
    confirmed.value = false
    try {
      const result = await fetchReviewDetail(selected)
      if (current !== generation || !allowed.value) return
      detail.value = result
    } catch (failure) {
      if (current === generation)
        error.value = failure instanceof Error ? failure.message : '审核摘要读取失败。'
    } finally {
      if (current === generation) busy.value = false
    }
  }

  async function attest() {
    const reviewed = detail.value
    if (!allowed.value || busy.value || !canSubmit.value || !reviewed) return
    const current = generation
    busy.value = true
    error.value = ''
    try {
      const result = await submitReview(reviewed.requestId, {
        deploymentId: reviewed.deploymentId,
        tenantId: reviewed.claimedTenantId,
        publicKeySha256: reviewed.publicKeySha256,
        requestSha256: reviewed.requestSha256,
        organizationReference: organizationReference.value,
        evidenceSha256: evidenceSha256.value,
        tier: tier.value,
        revisionId: reviewed.revisionId,
        revisionSha256: reviewed.revisionSha256
      })
      if (current !== generation || !allowed.value) return
      progress.value = result
      detail.value = { ...reviewed, attestationCount: result.attestationCount }
      confirmed.value = false
    } catch (failure) {
      if (current === generation) {
        error.value =
          failure instanceof Error
            ? `${failure.message}；请重新读取摘要及审核进度后人工核对。`
            : '审核结果未知；请重新读取摘要及进度后人工核对。'
        detail.value = null
        progress.value = null
        confirmed.value = false
      }
    } finally {
      if (current === generation) busy.value = false
    }
  }
</script>

<style scoped>
  .spacing {
    margin-top: 16px;
  }
</style>
