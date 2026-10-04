/**
 * 图标注册表。
 *
 * 统一 24×24 viewBox、线宽 2、圆角线帽，对应视觉规范里
 * “统一 SVG 线宽、尺寸和视觉风格，替换 Emoji”的要求。
 *
 * 只登记当前实际使用的图标。新增图标时保持同一几何规格，
 * 不要混入填充式或不同线宽的图形。
 */

export interface IconDefinition {
  /** SVG 内部路径数据；可能有多条 */
  paths: string[]
}

export const ICONS = {
  /** 设备接入 */
  plug: {
    paths: ['M9 2v6M15 2v6M6 8h12v3a6 6 0 0 1-6 6 6 6 0 0 1-6-6V8ZM12 17v5']
  },
  /** 数据采集 */
  chart: {
    paths: ['M3 3v16a2 2 0 0 0 2 2h16', 'M7 15l4-5 4 3 5-7']
  },
  /** 远程控制 */
  sliders: {
    paths: [
      'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3',
      'M1 14h6M9 8h6M17 16h6'
    ]
  },
  /** 规则引擎 */
  bolt: {
    paths: ['M13 2 3 14h9l-1 8 10-12h-9l1-8Z']
  },
  /** 按钮箭头 */
  'arrow-right': {
    paths: ['M5 12h14', 'm13 6 6 6-6 6']
  },
  /** 文档入口 */
  book: {
    paths: [
      'M12 6.25v13',
      'M12 6.25C10.83 5.48 9.25 5 7.5 5S4.17 5.48 3 6.25v13C4.17 18.48 5.75 18 7.5 18s3.33.48 4.5 1.25',
      'M12 6.25C13.17 5.48 14.75 5 16.5 5s3.33.48 4.5 1.25v13C19.83 18.48 18.25 18 16.5 18s-3.33.48-4.5 1.25'
    ]
  },
  /**
   * 展开菜单。消费方是原生 Astro 组件（MobileMenu.astro），
   * 与其他图标一样由 Icon.astro 读取本注册表渲染。
   */
  menu: {
    paths: ['M4 6h16M4 12h16M4 18h16']
  },
  /** 收起菜单，与 menu 成对使用 */
  close: {
    paths: ['M6 18L18 6M6 6l12 12']
  }
} as const satisfies Record<string, IconDefinition>

export type IconName = keyof typeof ICONS
