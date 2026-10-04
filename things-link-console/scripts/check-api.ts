import { spawnSync } from 'node:child_process'
import { readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

// 使用系统临时目录而不是硬编码 /tmp；后者在 Windows 会被解析成非法 file URL。
const generated = join(tmpdir(), `things-link-api-${process.pid}.d.ts`)
const specification = resolve('../docs/openapi.json')
const committed = resolve('src/types/api/schema.d.ts')
const generatorCli = resolve('node_modules/openapi-typescript/bin/cli.js')

try {
  // 直接交给当前 Node 执行包内 CLI，避开 Windows .cmd shim 和 shell 参数拼接差异。
  const result = spawnSync(process.execPath, [generatorCli, specification, '-o', generated], {
    stdio: 'inherit'
  })
  if (result.status !== 0) process.exit(result.status ?? 1)

  if (readFileSync(generated).equals(readFileSync(committed))) {
    console.log('OpenAPI TypeScript 契约与 docs/openapi.json 一致')
  } else {
    console.error('API 类型已过期：请执行 pnpm api:generate 并提交生成结果')
    process.exitCode = 1
  }
} finally {
  rmSync(generated, { force: true })
}
