// 只读漂移检查：写入权始终属于仓库根生成器，临时类型不进入源码目录。
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const temporary = await mkdtemp(join(tmpdir(), 'things-link-webapp-api-'))
try {
  const output = join(temporary, 'schema.d.ts')
  const result = spawnSync(process.execPath, [join(root, 'node_modules/openapi-typescript/bin/cli.js'),
    join(root, '../docs/openapi.json'), '-o', output], { cwd: root, stdio: 'inherit' })
  if (result.error || result.status !== 0) throw new Error('OpenAPI临时类型生成失败')
  if (await readFile(output, 'utf8') !== await readFile(join(root, 'src/types/api/schema.d.ts'), 'utf8')) {
    throw new Error('WebApp类型已漂移；请在仓库根执行python3 scripts/generate-openapi-contracts.py')
  }
  console.log('WebApp OpenAPI类型与权威合同一致')
} finally {
  await rm(temporary, { recursive: true, force: true })
}
