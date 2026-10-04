import type { InjectionKey } from 'vue'
import type { DesignerReadScope } from '@/api/designer-read-scope'

/** 目录与告警借同一顶层调度器；子组件不能自建自动恢复循环。 */
export interface PreviewInteraction {
  run<T>(
    key: string,
    work: (scope: DesignerReadScope, signal: AbortSignal) => Promise<T>
  ): Promise<T>
}
export const previewInteractionKey: InjectionKey<PreviewInteraction> = Symbol(
  'dashboard-preview-interaction'
)
