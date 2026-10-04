/**
 * 仓库级 commitlint 配置。
 *
 * 根配置是CI与本地提交守卫的共同规则来源。
 * 平台由 .githooks/commit-msg 调用 scripts/check-commit-message.cjs，校验完整行宽。
 * 新克隆需设置 core.hooksPath 并安装 tools/commitlint 依赖，Console 提交独立校验。
 */
module.exports = {
  extends: ['@commitlint/config-conventional'],
  // 10d04e9 已在启用仓库级 commitlint 之前进入并推送到 master，且其内容对应现已删除的 Android 骨架。
  // 精确匹配这一条不可重写的历史消息；不要放宽为“所有中文提交都忽略”，否则 CI 会失去强制能力。
  ignores: [(message) => message.trim() === '删除暂时不开发的安卓项目'],
  rules: {
    // 允许的提交类型。与 README.md「提交与安全规范」一节同步，
    // 改动这里要同时审查平台文档与代码 CI 使用的规则
    'type-enum': [
      2,
      'always',
      [
        'feat', // 新增功能
        'fix', // 修复缺陷
        'docs', // 文档变更
        'style', // 代码格式（不影响功能）
        'refactor', // 代码重构（不含缺陷修复与功能新增）
        'perf', // 性能优化
        'test', // 补充或修改测试
        'build', // 构建流程、依赖变更
        'ci', // CI 配置与脚本
        'revert', // 回滚
        'chore', // 辅助工具与库的变更
        'wip' // 阶段性提交
      ]
    ],
    // 主题大小写不校验：本仓库提交信息以中文为主，大小写规则没有意义
    'subject-case': [0],
    // 保留既有CI正文规则以免追溯拒绝已发布历史；新消息完整100字符行宽由本地守卫强制。
    // footer规则仍继承config-conventional，不添加历史消息豁免。
    'body-max-line-length': [0],
    // 正文与页脚前必须有空行，否则 git log 的解析会把它们并成一行
    'body-leading-blank': [2, 'always'],
    'footer-leading-blank': [2, 'always']
  }
}
