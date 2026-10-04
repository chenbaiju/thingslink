import type { DashboardComponent, DashboardResponsiveLayout, DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'

/** 仅由宿主构建注册表提供；Schema不拥有资源URL。 */
export interface StaticImageResource {
  readonly resourceId: string
  readonly digest: string
  readonly src: string
}
export type StaticComponent = DashboardComponent<DashboardResponsiveLayout>

export function imageSource(component: StaticComponent, resources: readonly StaticImageResource[]): string | undefined {
  if (component.kind !== 'IMAGE') return undefined
  const matches = resources.filter(resource => resource.resourceId === component.props.resourceId && resource.digest === component.props.resourceDigest)
  if (matches.length !== 1) return undefined
  const source = matches[0].src
  // 仅受控同源应用资产，拒绝协议、查询串、反斜线及目录穿越。
  return /^[0-9a-f]{64}$/.test(matches[0].digest) && source === `/app/assets/${matches[0].digest}.png` ? source : undefined
}

/** 阅读器已做完整合同校验；呈现边界仍整版关闭未交付能力。 */
export function supportsStaticDashboard(schema: DashboardSchemaV1, resources: readonly StaticImageResource[]): boolean {
  return schema.models.length === 0 && schema.variables.length === 0 && schema.pages.every(page => page.components.every(component =>
    (['1.0.0', '1.0.1'].includes(component.componentVersion)) && Object.keys(component.bindings).length === 0
    && ((component.kind === 'TEXT' && 'content' in component.props)
      || (component.kind === 'IMAGE' && imageSource(component, resources) !== undefined))))
}

export function fixedFit(width: number, height: number): number {
  return Math.max(0, Math.min(width / 1920, height / 1080))
}

/** 768断点按内容视口；单列保留数组顺序与逻辑最小高度，不复写发布布局。 */
export function componentLayout(component: StaticComponent, fixed: boolean, width: number): Record<string, string> {
  const { x, y, w, h } = component.layout
  if (fixed) return { position: 'absolute', left: `${x}px`, top: `${y}px`, width: `${w}px`, height: `${h}px` }
  if (width < 768) return { minHeight: `${h * 8 + (h - 1) * 8}px` }
  return { gridColumn: `${x + 1} / span ${w}`, gridRow: `${y + 1} / span ${h}` }
}
