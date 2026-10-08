<template>
  <div class="console-page project-create">
    <ConsoleWorkspaceHeader
      project-style
      title="添加项目"
      description="为您的物联网业务创建项目，选择设备接入与数据存储所在的区域。"
    />
    <ElForm
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <ElFormItem prop="name" label="项目名称">
        <p class="console-description">为项目取一个便于识别的名称。</p>
        <ElInput
          v-model.trim="form.name"
          class="project-create__name"
          maxlength="128"
          show-word-limit
          placeholder="请输入项目名称"
        />
      </ElFormItem>
      <ElFormItem label="选择地域">
        <p class="console-description">选择项目所在地域。项目创建后，区域不可变更。</p>
        <ElAlert v-if="regionError" type="error" :closable="false" :title="regionError" />
        <div v-loading="regionLoading" class="project-create__options">
          <ElButton
            v-for="group in regionGroups"
            :key="group.areaCode"
            :type="areaCode === group.areaCode ? 'primary' : 'default'"
            :disabled="
              !group.regions.some((region) => region.enabled && region.projectCreationEnabled)
            "
            :aria-pressed="areaCode === group.areaCode"
            @click="chooseArea(group.areaCode)"
          >
            {{ group.areaName }}
          </ElButton>
        </div>
      </ElFormItem>
      <ElFormItem prop="region" label="选择可用区">
        <div class="project-create__options">
          <ElButton
            v-for="region in availableRegions"
            :key="region.code"
            :type="form.region === region.code ? 'primary' : 'default'"
            :disabled="!region.enabled || !region.projectCreationEnabled"
            :aria-pressed="form.region === region.code"
            @click="form.region = region.code"
          >
            {{ region.name }}
          </ElButton>
        </div>
      </ElFormItem>
      <section class="project-create__plans" aria-label="项目套餐参考">
        <h3>项目套餐</h3>
        <p class="console-description">新项目沿用您所属租户的订阅套餐，以下为套餐目录参考。</p>
        <ElAlert v-if="planError" type="warning" :closable="false" title="套餐目录暂不可用" />
        <div v-loading="planLoading" class="project-create__plan-grid">
          <article v-for="tier in tiers" :key="tier.code" class="project-create__plan">
            <div class="project-create__plan-title">
              <h3>{{ tier.name }}</h3>
              <ElTag :type="tier.saleStatusTag">{{ tier.saleStatusLabel }}</ElTag>
            </div>
            <p class="project-create__price"
              >{{ tier.displayPrice }}<small v-if="tier.priceIsReference"> 参考价</small></p
            >
            <p class="console-description">{{ tier.billingPeriodLabel }}</p>
            <ul>
              <li v-for="quota in previewQuotas(tier)" :key="quota.code">
                <span>{{ quota.label }}</span
                ><strong>{{ quota.value.toLocaleString() }}{{ quotaUnit(quota.unit) }}</strong>
              </li>
            </ul>
            <ElButton @click="router.push('/plan-catalog')">了解套餐详情</ElButton>
          </article>
        </div>
      </section>
      <ElFormItem prop="description" label="项目描述">
        <p class="console-description">可选，用于说明项目用途或补充项目资料。</p>
        <ElInput
          v-model="form.description"
          class="project-create__description"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="请输入项目描述"
        />
      </ElFormItem>
      <ElButton
        type="primary"
        native-type="submit"
        :loading="submitting"
        :disabled="regionLoading || !!regionError || !selectedRegionCreatable"
        >创建项目</ElButton
      >
    </ElForm>
  </div>
</template>

<script setup lang="ts">
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage } from 'element-plus'
  import { useRouter } from 'vue-router'
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import { fetchCreateProject } from '@/api/project'
  import { fetchPlanCatalog } from '@/api/plan'
  import { buildPlanTierModel, type PlanTierModel } from '@/features/plan/catalog-model'
  import { useProjectRegionCatalog } from '@/hooks/project/useProjectRegionCatalog'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'ProjectCreate' })
  const router = useRouter()
  const {
    defaultProjectRegion,
    loading: regionLoading,
    loadProjectRegions,
    regionGroups
  } = useProjectRegionCatalog()
  const formRef = ref<FormInstance>()
  const form = reactive({ name: '', region: '', description: '' })
  const areaCode = ref('')
  const regionError = ref('')
  const submitting = ref(false)
  const planLoading = ref(false)
  const planError = ref(false)
  const tiers = ref<PlanTierModel[]>([])
  const availableRegions = computed(
    () => regionGroups.value.find((group) => group.areaCode === areaCode.value)?.regions ?? []
  )
  const selectedRegionCreatable = computed(() =>
    availableRegions.value.some(
      (region) => region.code === form.region && region.enabled && region.projectCreationEnabled
    )
  )
  const rules: FormRules = {
    name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
    description: [{ max: 1000, message: '项目描述不能超过 1000 个字符', trigger: 'blur' }],
    region: [{ required: true, message: '请选择可用区', trigger: 'change' }]
  }
  const chooseArea = (code: string) => {
    areaCode.value = code
    form.region =
      availableRegions.value.find((region) => region.enabled && region.projectCreationEnabled)
        ?.code ?? ''
  }
  const quotaUnit = (unit: string) =>
    ({ COUNT: '', DAY: ' 天', MONTH: ' 个月', MESSAGE: ' 条' })[unit] ?? ' ' + unit
  const previewQuotas = (tier: PlanTierModel) =>
    tier.quotas.filter((quota) =>
      [
        'DEVICES_MAX',
        'UPLINK_MESSAGE_DAILY',
        'HISTORY_WINDOW',
        'END_USERS_MAX',
        'DASHBOARDS_MAX'
      ].includes(quota.code)
    )
  const submit = async () => {
    // 校验前同步锁定，防止连续点击创建多个项目。
    if (!formRef.value || submitting.value || !selectedRegionCreatable.value) return
    submitting.value = true
    try {
      await formRef.value.validate()
      await fetchCreateProject({
        name: form.name,
        region: form.region,
        description: form.description.trim() || undefined
      })
      ElMessage.success('项目创建成功')
      await router.push('/project/list')
    } catch (error) {
      if (error instanceof Error && !(error instanceof HttpError))
        console.error('创建项目失败:', error)
    } finally {
      submitting.value = false
    }
  }
  onMounted(async () => {
    try {
      await loadProjectRegions()
      const group = regionGroups.value.find((item) =>
        item.regions.some(
          (region) =>
            region.code === defaultProjectRegion.value &&
            region.enabled &&
            region.projectCreationEnabled
        )
      )
      if (group) {
        areaCode.value = group.areaCode
        form.region = defaultProjectRegion.value
      } else regionError.value = '暂无可创建项目的区域'
    } catch {
      regionError.value = '区域目录加载失败，请刷新页面重试'
    }
  })
  onMounted(async () => {
    planLoading.value = true
    try {
      tiers.value = (await fetchPlanCatalog()).map(buildPlanTierModel)
    } catch {
      planError.value = true
    } finally {
      planLoading.value = false
    }
  })
</script>

<style scoped lang="scss">
  .project-create {
    padding: 10px;
    :deep(.el-form-item) {
      margin-bottom: 28px;
    }
    :deep(.el-form-item__content) {
      display: block;
    }
    :deep(.el-form-item__label) {
      font-weight: 500;
    }
    .console-description {
      margin: 0 0 10px;
    }
    &__name,
    &__description {
      max-width: 460px;
    }
    &__options {
      display: flex;
      flex-wrap: wrap;
      gap: 0;
    }
    &__options :deep(.el-button) {
      min-width: 88px;
      margin: 0;
      border-radius: 0;
    }
    &__options :deep(.el-button:first-child) {
      border-radius: 4px 0 0 4px;
    }
    &__options :deep(.el-button:last-child) {
      border-radius: 0 4px 4px 0;
    }
    &__plans {
      margin-bottom: 28px;
    }
    &__plans > h3 {
      margin: 0 0 8px;
      font-size: 14px;
      font-weight: 500;
    }
    &__plan-grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 16px;
      max-width: 1200px;
    }
    &__plan {
      display: flex;
      flex-direction: column;
      padding: 18px;
      background: var(--default-box-color);
      border: 1px solid var(--console-line);
      border-radius: 6px;
    }
    &__plan-title {
      display: flex;
      gap: 8px;
      align-items: center;
      justify-content: space-between;
      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 500;
      }
    }
    &__price {
      margin: 16px 0 8px;
      font-size: 24px;
      color: var(--el-color-primary);
      small {
        font-size: 12px;
        color: var(--el-text-color-secondary);
      }
    }
    &__plan ul {
      flex: 1;
      padding: 0;
      margin: 8px 0 20px;
      list-style: none;
    }
    &__plan li {
      display: flex;
      gap: 10px;
      justify-content: space-between;
      margin-bottom: 12px;
      font-size: 12px;
      strong {
        font-weight: 400;
      }
    }
  }
  @media (width <= 1100px) {
    .project-create__plan-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
  @media (width <= 640px) {
    .project-create__plan-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
