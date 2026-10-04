import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { assertProductionEnvironment } from './production-environment.mjs'

try {
  assertProductionEnvironment(process.env)
} catch (error) {
  console.error(error.message)
  process.exit(1)
}

const astroCli = fileURLToPath(new URL('../node_modules/astro/bin/astro.mjs', import.meta.url))
const result = spawnSync(process.execPath, [astroCli, 'build'], {
  env: {
    ...process.env,
    // Prevent Astro from loading a localhost console link from local .env files.
    PUBLIC_CONSOLE_URL: process.env.PUBLIC_CONSOLE_URL ?? ''
  },
  stdio: 'inherit'
})

if (result.error) {
  console.error(`无法启动 Astro 生产构建：${result.error.message}`)
  process.exit(1)
}

process.exit(result.status ?? 1)
