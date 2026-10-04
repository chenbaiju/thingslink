/**
 * 基础组件的样式映射。
 *
 * 变体到 class 字符串的唯一来源。组件只负责选择变体，不各自拼样式，
 * 避免同一视觉在不同页面被写成不同 class。
 *
 * 注意：Tailwind 在构建时扫描源码里的 class 字符串来生成 CSS，
 * 因此这里的类名必须保持为完整字面量，不要用字符串拼接或变量插值。
 */

/** 按钮层级，对应视觉规范的“主要、次要、文本”三级 */
export type ButtonVariant = 'primary' | 'emphasis' | 'secondary' | 'outline-light' | 'ghost'

/** 按钮尺寸：sm 用于导航，md 用于首屏与区块 CTA */
export type ButtonSize = 'sm' | 'md'

const BUTTON_BASE =
  'inline-flex items-center justify-center gap-2 font-semibold whitespace-nowrap ' +
  'transition-all duration-fast ease-out'

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  /* 主要：品牌渐变底，悬停轻微上移并加深阴影 */
  primary:
    'bg-brand-gradient text-white shadow-lg shadow-brand-500/25 ' +
    'hover:-translate-y-px hover:shadow-xl hover:shadow-brand-500/35',
  /* 强调：深色区块上的主按钮 */
  emphasis:
    'bg-white text-brand-700 shadow-lg ' +
    'hover:bg-gray-50 hover:-translate-y-px hover:shadow-xl',
  /* 次要：浅色区块上的描边按钮 */
  secondary:
    'border border-border text-text-secondary hover:border-brand-300 ' +
    'hover:text-brand-600 hover:bg-brand-50/60',
  /* 浅色描边：深色区块上的次按钮 */
  'outline-light':
    'border border-white/30 text-white hover:bg-white/10 hover:border-white/50',
  /* 文本级：顶栏次级入口 */
  ghost: 'text-text-secondary hover:text-brand-600'
}

const BUTTON_SIZES: Record<ButtonSize, string> = {
  sm: 'px-4 py-2 text-sm rounded-lg',
  md: 'px-8 py-3.5 text-base rounded-xl'
}

/** 禁用态统一降透明度并屏蔽指针 */
const BUTTON_DISABLED = 'disabled opacity-50 pointer-events-none'

export interface ButtonClassOptions {
  variant?: ButtonVariant
  size?: ButtonSize
  disabled?: boolean
  /** 追加在末尾的调用方覆盖，用于少量尺寸调整（如移动菜单的全宽堆叠） */
  className?: string
}

/** 组合某一份按钮变体的完整 class */
export function buttonClasses({
  variant = 'primary',
  size = 'md',
  disabled = false,
  className = ''
}: ButtonClassOptions = {}): string {
  const classes = [BUTTON_BASE, BUTTON_VARIANTS[variant], BUTTON_SIZES[size]]
  if (disabled) classes.push(BUTTON_DISABLED)
  if (className) classes.push(className)
  return classes.join(' ')
}

export interface CardClassOptions {
  /** 是否为可交互卡片，启用悬停抬升与阴影变化 */
  hoverable?: boolean
}

const CARD_BASE = 'bg-white rounded-xl shadow-card'
const CARD_HOVERABLE =
  'transition-all duration-base ease-out hover:shadow-card-hover hover:-translate-y-0.5'

/** 组合卡片的完整 class；额外样式由调用方通过 class 传入 */
export function cardClasses({ hoverable = false }: CardClassOptions = {}): string {
  const classes = [CARD_BASE]
  if (hoverable) classes.push(CARD_HOVERABLE)
  return classes.join(' ')
}
