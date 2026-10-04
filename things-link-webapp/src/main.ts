import { captureShareEntry } from './share/entry'
import { createApp, h, Fragment } from 'vue'
import App from './App.vue'
import ShareApp from './ShareApp.vue'
import PwaStatus from './pwa/PwaStatus.vue'
import './styles.css'

// 分享最早受控启动先清fragment，随后才创建组件；不挂载App会话协调器。
if (window.location.pathname.startsWith('/app/share/')) {
  const entry = captureShareEntry({ href: window.location.href, replaceState: url => window.history.replaceState(null, '', url) })
  createApp({ render: () => h(Fragment, [h(PwaStatus), h(ShareApp, { entry })]) }).mount('#app')
} else createApp({ render: () => h(Fragment, [h(PwaStatus), h(App)]) }).mount('#app')
