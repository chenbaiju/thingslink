<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import { localTablePage, type RuntimeValue } from '@things-link/client-contracts/dashboard/v1'
  import DesignerJsonTree from './DesignerJsonTree.vue'
  const props = defineProps<{ value: RuntimeValue; rowLimit: number }>()
  const requested = ref(0)
  watch(
    () => [props.value, props.rowLimit],
    () => {
      requested.value = 0
    }
  )
  const page = computed(() =>
    localTablePage(Array.isArray(props.value) ? props.value : [], requested.value, props.rowLimit)
  )
</script>
<template>
  <section aria-label="完整列表快照" class="list-snapshot">
    <p data-testid="preview-list-total"
      >完整元素数：{{ page.total }}；第{{ page.index + 1 }}/{{ page.count }}页</p
    >
    <table
      ><thead
        ><tr><th scope="col">序号</th><th scope="col">值</th></tr></thead
      >
      <tbody
        ><tr v-for="(value, offset) in page.rows" :key="offset">
          <td>{{ page.index * rowLimit + offset + 1 }}</td>
          <td><DesignerJsonTree :value="value" :initial-expand-depth="1" /></td> </tr
      ></tbody>
    </table>
    <div class="console-actions">
      <el-button :disabled="page.index === 0" @click="requested = page.index - 1"
        >上一页列表</el-button
      >
      <el-button :disabled="page.index + 1 >= page.count" @click="requested = page.index + 1"
        >下一页列表</el-button
      >
    </div>
  </section>
</template>
<style scoped>
  .list-snapshot {
    min-width: 0;
    max-width: 100%;
  }
  table {
    width: 100%;
    table-layout: fixed;
    border-collapse: collapse;
  }
  th:first-child,
  td:first-child {
    width: 64px;
  }
  th,
  td {
    padding: 6px;
    text-align: left;
    overflow-wrap: anywhere;
    vertical-align: top;
    border: 1px solid var(--el-border-color);
  }
</style>
