<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { discoverHostCandidate, loadHostCandidate } from '../runtime/host'
interface InstallOffer extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}
const production = import.meta.env.PROD
const state = ref('INITIALIZING')
const offline = ref(!navigator.onLine)
const installOffer = ref<InstallOffer | null>(null)
const installing = ref(false)
let alive = true
let checking = false
let registration: ServiceWorkerRegistration | undefined
const removeListeners: (() => void)[] = []
const text = computed(() => offline.value ? '当前离线，仅保留公开页面；联网后重新确认访问权限。'
  : state.value === 'UPDATE_WAITING' ? '新版本已准备好，请关闭此站点的所有窗口后重新打开完成更新。'
    : state.value === 'UNAVAILABLE' ? '公开离线页面暂未准备完成；在线功能可继续使用。'
      : state.value === 'UNSUPPORTED' ? '当前浏览器可在线使用，安装入口以浏览器提供的选项为准。'
        : state.value === 'READY' ? '公开离线页面已准备好；私有业务数据始终需要在线确认。' : '正在准备公开离线页面…')
function observe(worker: ServiceWorker) {
  const owner = registration
  const changed = () => {
    if (!alive || offline.value || registration !== owner) return
    if (worker.state !== 'redundant' && ![owner?.active, owner?.waiting, owner?.installing].includes(worker)) return
    if (worker.state === 'installed') state.value = registration?.waiting ? 'UPDATE_WAITING' : 'READY'
    else if (worker.state === 'activated') state.value = 'READY'
    else if (worker.state === 'redundant') state.value = owner?.waiting ? 'UPDATE_WAITING' : owner?.active ? 'READY' : 'UNAVAILABLE'
  }
  worker.addEventListener('statechange', changed)
  removeListeners.push(() => worker.removeEventListener('statechange', changed))
  changed()
}
async function prepare() {
  if (!import.meta.env.PROD || checking || !alive || !navigator.onLine) return
  if (!window.isSecureContext || !('serviceWorker' in navigator)) { state.value = 'UNSUPPORTED'; return }
  checking = true
  try {
    // 当前页面业务身份只读精确同代候选；最新D仅用于完整新SW安装，不用于当前业务。
    await loadHostCandidate()
    const latest = await discoverHostCandidate()
    if (!alive) return
    for (const remove of removeListeners.splice(0)) remove()
    registration = await navigator.serviceWorker.register(`/app/releases/${latest.artifactDigest}/sw.js`, { scope: '/app/', updateViaCache: 'none' })
    if (!alive) return
    state.value = registration.waiting ? 'UPDATE_WAITING' : registration.active ? 'READY' : 'INITIALIZING'
    if (registration.installing) observe(registration.installing)
    const current = registration
    const update = () => { if (current.installing) observe(current.installing) }
    current.addEventListener('updatefound', update)
    removeListeners.push(() => current.removeEventListener('updatefound', update))
  } catch { if (alive) state.value = 'UNAVAILABLE' }
  finally { checking = false }
}
function connectivity() { offline.value = !navigator.onLine; if (!offline.value) void prepare() }
function offer(event: Event) { event.preventDefault(); installOffer.value = event as InstallOffer }
function installed() { installOffer.value = null; installing.value = false }
async function install() {
  const event = installOffer.value
  if (!event || installing.value) return
  installing.value = true; installOffer.value = null
  try { await event.prompt(); await event.userChoice }
  catch { /* 浏览器拒绝安装不影响在线业务，也不伪造安装成功。 */ }
  finally { installing.value = false }
}
onMounted(() => {
  if (!import.meta.env.PROD) return
  window.addEventListener('online', connectivity); window.addEventListener('offline', connectivity)
  window.addEventListener('beforeinstallprompt', offer); window.addEventListener('appinstalled', installed)
  void prepare()
})
onBeforeUnmount(() => {
  alive = false; installOffer.value = null
  for (const remove of removeListeners) remove()
  window.removeEventListener('online', connectivity); window.removeEventListener('offline', connectivity)
  window.removeEventListener('beforeinstallprompt', offer); window.removeEventListener('appinstalled', installed)
})
</script>
<template>
  <aside v-if="production" class="pwa-status" aria-label="安装与离线页面">
    <p data-testid="pwa-status" :data-status="offline ? 'OFFLINE' : state" role="status">{{ text }}</p>
    <button v-if="installOffer" type="button" :disabled="installing || offline" @click="install">安装到设备</button>
    <button v-if="state === 'UNAVAILABLE' && !offline" type="button" @click="prepare">重新准备离线页面</button>
  </aside>
</template>
<style scoped>
.pwa-status { max-width: 1140px; margin: 12px auto 0; padding: 0 22px; color: #53657e; font-size: 14px; }
.pwa-status p { margin: 0 0 8px; }
button { font: inherit; padding: 6px 10px; }
</style>
