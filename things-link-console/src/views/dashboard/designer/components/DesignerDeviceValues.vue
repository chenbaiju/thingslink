<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import { localTablePage } from '@things-link/client-contracts/dashboard/v1'
  import type { PreviewRow } from '@/features/dashboard/device-preview'
  const props = defineProps<{ table: NonNullable<PreviewRow['table']> }>()
  const requested = ref(0)
  watch(
    () => props.table,
    () => {
      requested.value = 0
    }
  )
  const page = computed(() =>
    localTablePage(props.table.rows, requested.value, props.table.rowLimit)
  )
</script>
<template>
  <section aria-label="多设备当前值快照" class="device-values">
    <p data-testid="preview-device-total"
      >完整设备数：{{ page.total }}；第{{ page.index + 1 }}/{{ page.count }}页</p
    >
    <div class="device-values-scroll"
      ><table>
        <thead
          ><tr
            ><th scope="col">设备</th
            ><th v-for="column in table.columns" :key="column.id" scope="col">{{
              column.label
            }}</th></tr
          ></thead
        >
        <tbody
          ><tr v-for="row in page.rows" :key="row.deviceId" :data-preview-device="row.deviceId">
            <th scope="row">{{ row.name }}</th>
            <td v-for="(cell, index) in row.cells" :key="table.columns[index]!.id"
              >{{ cell.text }}<small v-if="cell.detail">{{ cell.detail }}</small></td
            >
          </tr></tbody
        >
      </table></div
    >
    <div class="console-actions">
      <el-button :disabled="page.index === 0" @click="requested = page.index - 1"
        >上一页设备值</el-button
      >
      <el-button :disabled="page.index + 1 >= page.count" @click="requested = page.index + 1"
        >下一页设备值</el-button
      >
    </div>
  </section>
</template>
<style scoped>
  .device-values {
    min-width: 0;
    max-width: 100%;
  }
  .device-values-scroll {
    max-width: 100%;
    overflow-x: auto;
  }
  table {
    width: 100%;
    border-collapse: collapse;
  }
  th,
  td {
    min-width: 90px;
    padding: 6px;
    text-align: left;
    overflow-wrap: anywhere;
    vertical-align: top;
    border: 1px solid var(--el-border-color);
  }
  small {
    display: block;
  }
</style>
