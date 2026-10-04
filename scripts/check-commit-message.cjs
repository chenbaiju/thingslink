#!/usr/bin/env node
// 文档维护指南§5.1：提交前校验最终消息文件，完整行宽和真实commitlint缺一不可。
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const messageFile = process.argv[2];
if (!messageFile || process.argv.length !== 3) {
  console.error('用法：node scripts/check-commit-message.cjs <提交消息文件>');
  process.exit(1);
}

try {
  const filename = path.resolve(messageFile);
  const lines = fs.readFileSync(filename, 'utf8').split(/\r?\n/);
  let tooLong = false;
  lines.forEach((line, index) => {
    // 使用与commitlint一致的JavaScript字符串长度；URL也遵循本仓库完整行宽要求。
    if (line.length > 100) {
      console.error(`提交消息第${index + 1}行长度为${line.length}，超过100字符，请在语义边界换行。`);
      tooLong = true;
    }
  });
  const modules = path.join(root, 'tools', 'commitlint', 'node_modules');
  const cli = require.resolve('@commitlint/cli/cli.js', { paths: [modules] });
  // 复用与CI同主版本的本地依赖，始终读取仓库根配置；不下载工具、不修改消息、不吞错误。
  const result = spawnSync(process.execPath, [
    cli, '--config', path.join(root, 'commitlint.config.cjs'), '--edit', filename, '--verbose'
  ], {
    cwd: root,
    env: { ...process.env, NODE_PATH: modules },
    stdio: 'inherit'
  });
  if (result.error) throw result.error;
  process.exit(tooLong ? 1 : (result.status ?? 1));
} catch (error) {
  console.error(`提交消息校验失败：${error.message}`);
  console.error('请确认消息文件存在，并执行pnpm --dir tools/commitlint install --frozen-lockfile准备依赖。');
  process.exit(1);
}
