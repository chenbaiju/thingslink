// 从 URL 和路径模块中导入必要的功能
import fs from 'fs'
import path, { dirname } from 'path'
import { fileURLToPath } from 'url'

// 从 ESLint 插件中导入推荐配置
import pluginJs from '@eslint/js'
import eslintPluginPrettierRecommended from 'eslint-plugin-prettier/recommended'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import unusedImports from 'eslint-plugin-unused-imports'

// 使用 import.meta.url 获取当前模块的路径
const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)

// 读取 .auto-import.json 文件的内容，并将其解析为 JSON 对象
const autoImportConfig = JSON.parse(
  fs.readFileSync(path.resolve(__dirname, '.auto-import.json'), 'utf-8')
)

export default [
  // 指定文件匹配规则
  {
    files: ['**/*.{js,mjs,cjs,ts,tsx,vue}']
  },
  // 指定全局变量和环境
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node
      }
    }
  },
  // 扩展配置
  pluginJs.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  // 自定义规则
  {
    // 针对所有 JavaScript、TypeScript 和 Vue 文件应用以下配置
    files: ['**/*.{js,mjs,cjs,ts,tsx,vue}'],

    plugins: {
      // 未使用 import 由它自动删除（--fix）；no-unused-vars 也由它接管，避免与 TS 推荐规则重复报告
      'unused-imports': unusedImports
    },

    languageOptions: {
      globals: {
        // 合并从 autoImportConfig 中读取的全局变量配置
        ...autoImportConfig.globals,
        // TypeScript 全局命名空间
        Api: 'readonly'
      }
    },
    rules: {
      quotes: ['error', 'single'], // 使用单引号
      semi: ['error', 'never'], // 语句末尾不加分号
      'no-var': 'error', // 要求使用 let 或 const 而不是 var
      '@typescript-eslint/no-explicit-any': 'off', // 禁用 any 检查
      '@typescript-eslint/no-unused-vars': 'off', // 由 unused-imports 接管
      'unused-imports/no-unused-imports': 'error', // 未使用 import，--fix 可自动删除
      'unused-imports/no-unused-vars': [
        'error',
        { vars: 'all', varsIgnorePattern: '^_', args: 'after-used', argsIgnorePattern: '^_' }
      ],
      'vue/multi-word-component-names': 'off', // 禁用对 Vue 组件名称的多词要求检查
      'no-multiple-empty-lines': ['warn', { max: 1 }], // 不允许多个空行
      'no-unexpected-multiline': 'error' // 禁止空余的多行
    }
  },
  // vue 规则
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser }
    }
  },
  // 忽略文件
  {
    ignores: [
      'node_modules',
      'dist',
      'public',
      '.vscode/**',
      // Playwright 浏览器与运行产物（本地装在仓库内，CI 装在缓存目录），不能进 lint
      '.playwright-browsers/**',
      'test-results/**',
      'playwright-report/**',
      // 本地验证日志中的浏览器报告/trace是生成证据，不参与源码静态检查。
      'logs/**',
      // G2 按候选/场景隔离的原始证据，不属于源码或可被格式化的文件。
      '.e2e-evidence/**',
      'src/assets/**',
      'src/utils/console.ts',
      // 由 OpenAPI 契约生成（pnpm api:generate）。
      // 必须忽略：否则 eslint --fix 会重排格式，而 api:check 重新生成的版本
      // 与被格式化过的版本对不上，两者永远互相判对方错 —— 与 stylelint 和
      // prettier 争夺空行是同一类问题。一个文件只能有一个所有者，这个文件
      // 归生成器。
      'src/types/api/**'
    ]
  },
  // prettier 配置
  eslintPluginPrettierRecommended
]
