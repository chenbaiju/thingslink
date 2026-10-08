<template>
  <section class="workbench-resources" aria-labelledby="resources-heading">
    <h2 id="resources-heading">我的资源</h2>
    <ul>
      <li v-for="row in rows" :key="row.path">
        <span class="workbench-resources__icon"><ArtSvgIcon :icon="row.icon" /></span>
        <div>
          <span>{{ row.label }}</span>
          <span class="workbench-resources__count" :aria-label="`${row.label}数量`">{{
            row.failed
              ? '暂不可用'
              : row.count === undefined
                ? '—'
                : row.count.toLocaleString('zh-CN')
          }}</span>
        </div>
      </li>
    </ul>
    <p v-if="!rows.length" class="workbench-resources__empty">当前角色暂无可查看的资源统计。</p>
  </section>
</template>

<script setup lang="ts">
  import { onBeforeUnmount, ref, watch } from 'vue'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { countWorkbenchResource, workbenchResources } from '@/utils/workbench-resources'

  const props = defineProps<{ projectId: string; scopeKey: string; allowedPaths: string[] }>()
  type ResourceRow = (typeof workbenchResources)[number] & { count?: number; failed: boolean }
  const rows = ref<ResourceRow[]>([])
  let generation = 0
  async function load() {
    const request = ++generation
    const projectId = props.projectId
    const epoch = currentIdentityEpoch()
    const current = () => request === generation && epoch === currentIdentityEpoch()
    rows.value = workbenchResources
      .filter((resource) => props.allowedPaths.includes(resource.path))
      .map((resource) => ({ ...resource, failed: false }))
    const pending = [...rows.value]
    // 最多三个目录同时读取，避免首页一次并发请求全部模块。
    await Promise.all(
      Array.from({ length: Math.min(3, pending.length) }, async () => {
        while (current() && pending.length) {
          const row = pending.shift()!
          try {
            const count = await countWorkbenchResource(row, projectId, current)
            if (current()) row.count = count
          } catch {
            if (current()) row.failed = true
          }
        }
      })
    )
  }
  watch(() => [props.projectId, props.scopeKey, props.allowedPaths, currentIdentityEpoch()], load, {
    immediate: true
  })
  onBeforeUnmount(() => generation++)
</script>

<style scoped lang="scss">
  .workbench-resources {
    padding: 24px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-light);
    border-radius: 10px;

    h2 {
      margin: 0 0 20px;
      font-size: 17px;
      font-weight: 600;
    }

    ul {
      display: flex;
      flex-direction: column;
      gap: 18px;
      padding: 0;
      margin: 0;
      list-style: none;
    }

    li {
      display: flex;
      gap: 14px;
      align-items: center;
      font-size: 14px;
    }
  }

  .workbench-resources__icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    font-size: 20px;
    color: var(--el-text-color-secondary);
    background: var(--el-fill-color-light);
    border-radius: 4px;
  }

  .workbench-resources__count {
    display: block;
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .workbench-resources__empty {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
</style>
