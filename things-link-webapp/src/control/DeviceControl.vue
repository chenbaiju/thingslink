<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef } from 'vue';
import type { SessionCoordinator } from '../auth/session';
import { createDeviceControl, type ControlSnapshot, type ControlBudget } from './device-control';
const props = defineProps<{ session: SessionCoordinator; budget: ControlBudget }>();
const emit = defineEmits<{ back: [] }>();
const snapshot = shallowRef<ControlSnapshot>();
const controller = createDeviceControl({ session: props.session, budget: props.budget, fetch: (input, init) => window.fetch(input, init),
  now: () => performance.now(), randomId: () => crypto.randomUUID(),
  available: () => document.visibilityState === 'visible' && navigator.onLine,
  setTimer: (callback, delay) => window.setTimeout(callback, delay), clearTimer: timer => window.clearTimeout(timer as number),
  changed: value => { snapshot.value = value; } });
snapshot.value = controller.snapshot();
const selected = computed(() => snapshot.value?.commands.find(command => command.commandKey === snapshot.value?.commandKey));
const locked = computed(() => snapshot.value?.busy || snapshot.value?.state === 'UNKNOWN');
const labels = { ACCEPTED: '已受理', DISPATCHED: '已下发', ACKNOWLEDGED: '设备已确认收到', SUCCEEDED: '执行成功', FAILED: '执行失败', TIMED_OUT: '执行超时' };
function edit(event: Event) {
  const input = event.target as HTMLTextAreaElement; controller.edit(input.value); input.value = controller.snapshot().input;
}
function dispose() { controller.dispose(); }
defineExpose({ dispose });
onBeforeUnmount(dispose);
</script>

<template>
  <section class="runtime-surface device-control" data-testid="device-control" aria-label="独立设备控制">
    <h2>设备控制</h2>
    <p>这是独立控制入口，看板和分享页面仍为只读。命令目录仅供预览，提交时会按当前定义重新校验。</p>
    <p class="notice">离开、隐藏、离线或退出会清除本地输入与意图，但不会撤销已经受理的设备操作。</p>
    <button class="secondary-button" data-testid="device-control-back" @click="emit('back')">返回看板并清除本地意图</button>
    <template v-if="snapshot && snapshot.state !== 'CLOSED'">
      <div class="action-row">
        <button class="secondary-button" data-testid="control-device-first" :disabled="locked" @click="controller.devices()">读取设备首页</button>
        <button class="secondary-button" data-testid="control-device-next" :disabled="locked || !snapshot.nextCursor" @click="controller.devices(snapshot.nextCursor!)">下一页设备</button>
      </div>
      <label>已绑定设备
        <select data-testid="control-device" :value="snapshot.deviceId ?? ''" :disabled="locked" @change="controller.selectDevice(($event.target as HTMLSelectElement).value)">
          <option value="" disabled>请先读取设备，再明确选择</option>
          <option v-for="device in snapshot.devices" :key="device.id" :value="device.id">{{ device.name }}</option>
          <option v-if="snapshot.deviceId && !snapshot.devices.some(device => device.id === snapshot!.deviceId)" :value="snapshot.deviceId">已选择设备（不在当前页）</option>
        </select>
      </label>
      <label>可用命令
        <select data-testid="control-command" :value="snapshot.commandKey ?? ''" :disabled="locked" @change="controller.selectCommand(($event.target as HTMLSelectElement).value)">
          <option value="" disabled>请选择真实命令</option>
          <option v-for="command in snapshot.commands" :key="command.commandKey" :value="command.commandKey">{{ command.name }}</option>
        </select>
      </label>
      <div v-if="selected">
        <p>{{ selected.description ?? '暂无命令说明' }} · 设备执行期限 {{ selected.timeoutSeconds }} 秒</p>
        <details><summary>查看输入定义</summary><pre data-testid="control-input-schema">{{ selected.inputSchema ?? '未提供输入定义' }}</pre></details>
        <details><summary>查看输出定义</summary><pre>{{ selected.outputSchema ?? '未提供输出定义' }}</pre></details>
        <label>输入 JSON 对象（请求总计至多 64KiB）
          <textarea data-testid="control-input" :value="snapshot.input" :disabled="locked" autocomplete="off" spellcheck="false" rows="6" @input="edit"></textarea>
        </label>
        <button class="primary-button" data-testid="control-submit" :disabled="locked" @click="controller.submit()">提交命令</button>
      </div>
      <p data-testid="control-status" :data-status="snapshot.state === 'UNKNOWN' ? 'UNKNOWN' : snapshot.result?.status ?? snapshot.state" role="status">{{ snapshot.busy ? '正在等待响应…' : snapshot.state === 'UNKNOWN' ? '结果未知' : snapshot.result ? labels[snapshot.result.status] : snapshot.state === 'REJECTED' ? '请求未通过' : '待提交' }}</p>
      <p v-if="snapshot.message" data-testid="control-message" role="alert">{{ snapshot.message }}</p>
      <div v-if="snapshot.state === 'UNKNOWN'" class="action-row">
        <button data-testid="control-retry" :disabled="snapshot.busy" @click="controller.retry()">重试原请求（同一幂等键）</button>
        <button data-testid="control-abandon" :disabled="snapshot.busy" @click="controller.abandon()">放弃本地意图（不是撤销）</button>
      </div>
      <template v-if="snapshot.result">
        <p>受理或确认收到均不代表执行成功。只有“执行成功”表示成功。</p>
        <button data-testid="control-refresh" :disabled="snapshot.busy" @click="controller.refresh()">刷新命令状态</button>
        <pre data-testid="control-result">{{ snapshot.result.text }}</pre>
      </template>
    </template>
  </section>
</template>

<style scoped>
.device-control label { display: block; margin: 1rem 0; }
.device-control select, .device-control textarea { display: block; width: 100%; max-width: 44rem; margin-top: .5rem; }
.device-control pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 22rem; overflow: auto; }
</style>
