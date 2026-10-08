<template>
  <section class="project-recycle-bin">
    <ElAlert v-if="notice" :title="notice" type="warning" :closable="false" />
    <ElTable v-loading="loading" :data="rows" row-key="id" height="100%">
      <ElTableColumn prop="name" label="项目名称" min-width="200" />
      <ElTableColumn label="删除时间" width="190">
        <template #default="{ row }">{{ formatTime(row.deletedAt) }}</template>
      </ElTableColumn>
      <ElTableColumn label="恢复截止（本地时间）" width="220">
        <template #default="{ row }">{{ formatTime(row.restoreDeadline) }}</template>
      </ElTableColumn>
      <ElTableColumn prop="timezone" label="项目时区" width="170" />
      <ElTableColumn label="状态" min-width="200">
        <template #default="{ row }">{{
          row.restorable ? '可恢复' : '已超过恢复期限，等待清理'
        }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <div class="project-recycle-bin__actions">
            <ElButton
              type="primary"
              :disabled="!row.restorable || loading || !!restoringId"
              :loading="restoringId === row.id"
              @click="restore(row)"
              >恢复项目</ElButton
            >
            <ElButton
              :disabled="!row.restorable || loading || !!restoringId"
              @click="exportProjectId = row.id || ''"
              >留存导出</ElButton
            >
          </div>
        </template>
      </ElTableColumn>
      <template #empty>
        <ElEmpty v-if="loaded" description="回收站暂无项目" />
        <span v-else>{{ loading ? '正在加载回收站' : '回收站读取失败，请重新刷新。' }}</span>
      </template>
    </ElTable>
    <ElDialog
      :model-value="!!exportProjectId"
      title="留存导出"
      width="min(760px, calc(100vw - 32px))"
      @close="exportProjectId = ''"
    >
      <ProjectRetentionExport
        v-if="exportProjectId"
        :key="exportProjectId"
        :project-id="exportProjectId"
      />
    </ElDialog>
  </section>
</template>
<script setup lang="ts">
  import { ElMessageBox } from 'element-plus'
  import ProjectRetentionExport from '@/components/ProjectRetentionExport.vue'
  import {
    fetchProjectRecycleBin,
    fetchRestoreProject,
    type ProjectRecycleBinResponse
  } from '@/api/project'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { formatTime } from '@/utils/time'
  const emit = defineEmits<{ changed: [] }>()
  const user = useUserStore()
  const rows = ref<ProjectRecycleBinResponse[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const restoringId = ref('')
  const notice = ref('')
  const exportProjectId = ref('')
  let generation = 0
  const refresh = async () => {
    const sequence = ++generation
    loading.value = true
    loaded.value = false
    rows.value = []
    try {
      const result = await fetchProjectRecycleBin()
      if (sequence === generation) {
        rows.value = result
        loaded.value = true
      }
    } catch {
      if (sequence === generation) notice.value = '回收站读取失败，请重新刷新。'
    } finally {
      if (sequence === generation) loading.value = false
    }
  }
  const restore = async (row: ProjectRecycleBinResponse) => {
    if (!row.id || !row.restorable || restoringId.value || loading.value) return
    restoringId.value = row.id
    notice.value = ''
    const epoch = currentIdentityEpoch()
    const sequence = generation
    let submitted = false
    try {
      await ElMessageBox.confirm(
        `确认恢复项目「${row.name}」？恢复不会重新启用旧设备凭据、旧分享或已撤销能力。`,
        '恢复项目',
        { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' }
      )
      if (epoch !== currentIdentityEpoch() || sequence !== generation) return
      submitted = true
      await fetchRestoreProject(row.id)
      if (epoch !== currentIdentityEpoch() || sequence !== generation) return
      notice.value = '项目已恢复，请从项目列表重新进入并核对配置。'
    } catch (error) {
      if (epoch !== currentIdentityEpoch() || sequence !== generation) return
      if (error !== 'cancel' && error !== 'close')
        notice.value = '恢复未确认成功，请刷新回收站和项目列表核对结果，勿直接重复提交。'
    } finally {
      if (epoch === currentIdentityEpoch() && sequence === generation) {
        restoringId.value = ''
        if (submitted) {
          emit('changed')
          await refresh()
        }
      }
    }
  }
  watch(
    () => [user.info.userId, user.info.tenantId, currentIdentityEpoch()],
    () => {
      generation++
      rows.value = []
      loaded.value = false
      restoringId.value = ''
      exportProjectId.value = ''
      notice.value = ''
      void refresh()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
  })
</script>
<style scoped lang="scss">
  .project-recycle-bin {
    display: flex;
    flex-direction: column;
    gap: 14px;

    &__actions {
      display: flex;
      flex-wrap: nowrap;
      gap: 12px;
      align-items: center;

      .el-button {
        flex-shrink: 0;
        margin-left: 0;
      }
    }
  }
</style>
