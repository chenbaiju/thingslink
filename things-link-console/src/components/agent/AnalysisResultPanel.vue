<template>
  <section v-if="value" class="analysis-result console-feedback" data-testid="analysis-result">
    <ElDivider content-position="left">本次分析结果</ElDivider>
    <p>{{ categoryLabel }}</p>
    <p v-if="value.call">调用编号：{{ value.call.id }}</p>
    <template v-if="value.result">
      <ElAlert class="console-hint" type="info" show-icon :closable="false"
        >以下为模型生成内容，需人工核对；引用编号不证明结论正确。</ElAlert
      >
      <p class="analysis-text">{{ value.result.summary }}</p>
      <div v-for="group in groups" :key="group.kind">
        <ElDivider content-position="left">{{ group.label }}</ElDivider>
        <p v-if="group.items.length === 0">未返回此类条目。</p>
        <ul v-else>
          <li v-for="(finding, index) in group.items" :key="index">
            <p class="analysis-text">{{ finding.statement }}</p>
            <p>引用编号：{{ finding.evidenceIds.join('、') }}</p>
          </li>
        </ul>
      </div>
      <ElDivider content-position="left">限制</ElDivider>
      <ul v-if="value.result.limitations.length">
        <li
          v-for="(limitation, index) in value.result.limitations"
          :key="index"
          class="analysis-text"
          >{{ limitation }}</li
        >
      </ul>
      <ElAlert v-else class="console-hint" type="info" show-icon :closable="false"
        >模型未列出限制，不代表不存在限制。</ElAlert
      >
      <p
        >Token 用量：输入 {{ value.result.usage.promptTokens }}，输出
        {{ value.result.usage.completionTokens }}，合计
        {{ value.result.usage.totalTokens }}；输入缓存命中
        {{ value.result.usage.cacheHitTokens }}，未命中
        {{ value.result.usage.cacheMissTokens }}。用量不等于费用结算。</p
      >
      <ElAlert class="console-hint" type="info" show-icon :closable="false"
        >正文只在当前页面暂存，刷新或切换身份及设备后消失；不能从原调用状态恢复正文。</ElAlert
      >
    </template>
    <ElAlert v-else class="console-hint" type="warning" show-icon :closable="false"
      >本次没有可展示正文，用量与费用无法由此确认。请保留原意图，手动核对原调用，勿自动重新分析。</ElAlert
    >
  </section>
  <ElAlert
    v-else-if="run !== undefined"
    role="alert"
    class="console-hint"
    type="error"
    show-icon
    :closable="false"
    >分析结果未通过校验，正文不展示；请核对原调用，勿重复提交。</ElAlert
  >
</template>
<script setup lang="ts">
  import { computed } from 'vue'
  import { decodeAnalysisRun } from '@/features/agent/analysis-result'
  const props = defineProps<{ run: unknown }>()
  const value = computed(() => {
    if (props.run === undefined) return undefined
    try {
      return decodeAnalysisRun(props.run)
    } catch {
      return undefined
    }
  })
  const categoryLabel = computed(() => {
    switch (value.value?.category) {
      case 'SUCCEEDED':
        return '本次已返回正文；结构校验不代表诊断质量验收。'
      case 'REPLAY':
        return '原调用已有记录，本次只返回元数据。'
      case 'UNAVAILABLE':
        return '当前分析不可用。'
      case 'UNQUALIFIED':
        return '本次未取得可交付的分析结果。'
      default:
        return '传输结果未知，不能推断未执行或零费用。'
    }
  })
  const groups = computed(() =>
    [
      { kind: 'FACT', label: '模型陈述的事实' },
      { kind: 'HYPOTHESIS', label: '待核验的假设' },
      { kind: 'RECOMMENDATION', label: '需人工判断的建议' }
    ].map((group) => ({
      ...group,
      items: value.value?.result?.findings.filter((item) => item.kind === group.kind) ?? []
    }))
  )
</script>
<style scoped lang="scss">
  .analysis-result {
    display: grid;
    gap: 12px;
  }
  .analysis-text {
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
</style>
