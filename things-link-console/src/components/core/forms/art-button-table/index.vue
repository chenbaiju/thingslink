<!-- 表格按钮 -->
<template>
  <ElTooltip :content="label" :disabled="!label" placement="top" :show-after="150">
    <span class="art-table-action">
      <button
        v-bind="attrs"
        type="button"
        :disabled="disabled || loading"
        :aria-label="label || type || icon"
        :aria-busy="loading"
        :class="[
          'inline-flex items-center justify-center w-8 h-8 p-0 text-sm c-p rounded-md align-middle',
          buttonClass
        ]"
        :style="{ backgroundColor: buttonBgColor, color: iconColor }"
        @click="handleClick"
      >
        <span aria-hidden="true"
          ><ArtSvgIcon
            :icon="loading ? 'ri:loader-4-line' : iconContent"
            :class="{ 'art-table-action__loading': loading }"
        /></span>
      </button>
    </span>
  </ElTooltip>
</template>

<script setup lang="ts">
  defineOptions({ name: 'ArtButtonTable', inheritAttrs: false })
  const attrs = useAttrs()

  interface Props {
    label?: string
    disabled?: boolean
    loading?: boolean
    /** 按钮类型 */
    type?: 'add' | 'edit' | 'delete' | 'more' | 'view'
    /** 按钮图标 */
    icon?: string
    /** 按钮样式类 */
    iconClass?: string
    /** icon 颜色 */
    iconColor?: string
    /** 按钮背景色 */
    buttonBgColor?: string
  }

  const props = withDefaults(defineProps<Props>(), {})

  const emit = defineEmits<{
    (e: 'click', event: MouseEvent): void
  }>()

  // 默认按钮配置
  const defaultButtons = {
    add: { icon: 'ri:add-fill', class: 'bg-theme/12 text-theme' },
    edit: { icon: 'ri:pencil-line', class: 'bg-secondary/12 text-secondary' },
    delete: { icon: 'ri:delete-bin-5-line', class: 'bg-error/12 text-error' },
    view: { icon: 'ri:eye-line', class: 'bg-info/12 text-info' },
    more: { icon: 'ri:more-2-fill', class: '' }
  } as const

  // 获取图标内容
  const iconContent = computed(() => {
    return props.icon || (props.type ? defaultButtons[props.type]?.icon : '') || ''
  })

  // 获取按钮样式类
  const buttonClass = computed(() => {
    return props.iconClass || (props.type ? defaultButtons[props.type]?.class : '') || ''
  })

  const handleClick = (event: MouseEvent) => {
    if (!props.disabled && !props.loading) emit('click', event)
  }
</script>

<style scoped>
  .art-table-action {
    display: inline-flex;
    vertical-align: middle;
  }
  button {
    font: inherit;
    border: 0;
    transition: filter 0.15s;
  }
  button:hover:not(:disabled) {
    filter: brightness(0.96);
  }
  button:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
  button:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: 2px;
  }
  .art-table-action__loading {
    animation: table-action-spin 1s linear infinite;
  }
  @keyframes table-action-spin {
    to {
      transform: rotate(360deg);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .art-table-action__loading {
      animation: none;
    }
  }
</style>
