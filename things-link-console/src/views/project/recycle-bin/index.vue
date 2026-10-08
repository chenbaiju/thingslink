<template>
  <div class="console-page project-recycle-page">
    <ConsoleWorkspaceHeader
      project-style
      title="项目回收站"
      description="查看仍有恢复资格的删除项目。恢复期限以服务端为准；恢复后旧凭据与分享不会重新生效。"
      :links="[{ label: '项目列表', path: '/project/list' }]"
    />
    <ProjectRecycleBin />
  </div>
</template>

<script setup lang="ts">
  import ProjectRecycleBin from '@/components/ProjectRecycleBin.vue'
  defineOptions({ name: 'ProjectRecycleBinPage' })
</script>

<style scoped lang="scss">
  .project-recycle-page {
    display: flex;
    flex-direction: column;
    height: var(--art-full-height);
    min-height: 520px;
    padding: 10px;

    :deep(.workspace-header) {
      flex-shrink: 0;
    }
    .project-recycle-bin {
      flex: 1;
      min-height: 0;
    }
  }
</style>
