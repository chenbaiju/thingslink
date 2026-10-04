<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
  import {
    deviceVariableReferences,
    type TextEnumInput,
    type TextComponentInput
  } from '@/features/dashboard/designer-model'
  const props = defineProps<{
    schema: DashboardSchemaV1
    selected?: DashboardSchemaV1['pages'][number]['components'][number] | null
    disabled: boolean
  }>()
  const emit = defineEmits<{
    upsert: [input: TextEnumInput]
    remove: [key: string]
    add: [input: TextComponentInput]
    rebind: [input: TextComponentInput]
  }>()
  const variables = computed(() => props.schema.variables.filter((v) => v.type === 'TEXT_ENUM'))
  const editing = ref(''),
    title = ref('文本选项'),
    required = ref(true),
    options = ref([{ value: 'option_1', label: '选项一' }]),
    defaultValue = ref('')
  const references = computed(() => deviceVariableReferences(props.schema, editing.value))
  function forbiddenControl(value: string, allowWhitespace = false) {
    return Array.from(value).some((character) => {
      const code = character.codePointAt(0)!
      return (
        (code < 32 || (code >= 127 && code <= 159)) &&
        !(allowWhitespace && (code === 9 || code === 10))
      )
    })
  }
  const validTitle = (value: string) =>
    Array.from(value).length >= 1 &&
    Array.from(value).length <= 80 &&
    !!value.trim() &&
    !forbiddenControl(value)
  const validOptions = computed(
    () =>
      options.value.length >= 1 &&
      options.value.length <= 20 &&
      new Set(options.value.map((o) => o.value)).size === options.value.length &&
      options.value.every((o) => /^[a-z][a-z0-9_]{0,63}$/.test(o.value) && validTitle(o.label))
  )
  const readyVariable = computed(
    () =>
      !props.disabled &&
      validTitle(title.value) &&
      validOptions.value &&
      (!defaultValue.value || options.value.some((o) => o.value === defaultValue.value))
  )
  function newVariable() {
    editing.value = ''
    title.value = '文本选项'
    required.value = true
    options.value = [{ value: 'option_1', label: '选项一' }]
    defaultValue.value = ''
  }
  function editVariable(key: string) {
    const variable = variables.value.find((v) => v.key === key)
    if (!variable) return
    editing.value = variable.key
    title.value = variable.title
    required.value = variable.required
    options.value = variable.options.map((o) => ({ ...o }))
    defaultValue.value = variable.defaultValue ?? ''
  }
  function append() {
    if (props.disabled || options.value.length >= 20) return
    let index = 1
    while (options.value.some((o) => o.value === `option_${index}`)) index++
    options.value.push({ value: `option_${index}`, label: `选项${index}` })
  }
  function saveVariable() {
    if (!readyVariable.value) return
    emit('upsert', {
      key: editing.value || undefined,
      title: title.value,
      required: required.value,
      options: options.value.map((o) => ({ ...o })),
      ...(defaultValue.value ? { defaultValue: defaultValue.value } : {})
    })
  }
  const mode = ref<TextComponentInput['mode']>('STATIC'),
    content = ref('新文本'),
    variableKey = ref(''),
    align = ref<TextComponentInput['align']>('LEFT'),
    size = ref<TextComponentInput['size']>('MEDIUM'),
    tone = ref<TextComponentInput['tone']>('REGULAR')
  watch(
    () => props.selected,
    (selected) => {
      if (selected?.kind !== 'TEXT') return
      align.value = selected.props.align
      size.value = selected.props.size
      tone.value = selected.props.tone
      if ('text' in selected.bindings) {
        mode.value = 'DYNAMIC'
        variableKey.value = selected.bindings.text.variableKey
        content.value = ''
      } else {
        mode.value = 'STATIC'
        content.value = 'content' in selected.props ? selected.props.content : ''
        variableKey.value = ''
      }
    },
    { immediate: true }
  )
  const validContent = computed(
    () => Array.from(content.value).length <= 4096 && !forbiddenControl(content.value, true)
  )
  const readyText = computed(
    () =>
      !props.disabled &&
      (mode.value === 'STATIC'
        ? validContent.value
        : variables.value.some((v) => v.key === variableKey.value))
  )
  function add(rebind = false) {
    if (!readyText.value || (rebind && props.selected?.kind !== 'TEXT')) return
    const input: TextComponentInput = {
      mode: mode.value,
      align: align.value,
      size: size.value,
      tone: tone.value,
      ...(mode.value === 'STATIC' ? { content: content.value } : { variableKey: variableKey.value })
    }
    if (rebind) emit('rebind', input)
    else emit('add', input)
  }
</script>
<template>
  <section class="console-fragment" aria-label="枚举文本编辑"
    ><h3 class="console-heading">文本枚举变量</h3
    ><p class="console-description"
      >选项值是稳定标识，文本组件仅展示标签。默认选项写入草稿，预览选择不会写回默认。</p
    >
    <div class="console-actions">
      <button :disabled="disabled" @click="newVariable">新建文本变量</button
      ><button
        v-for="variable in variables"
        :key="variable.key"
        :disabled="disabled"
        @click="editVariable(variable.key)"
        >编辑文本变量 {{ variable.title }}</button
      >
    </div>
    <fieldset :disabled="disabled"
      ><label>文本变量标题<input v-model="title" aria-label="文本变量标题" /></label
      ><label><input v-model="required" type="checkbox" aria-label="文本变量必选" />必选</label>
      <div v-for="(option, index) in options" :key="index"
        ><label
          >第{{ index + 1 }}项值<input
            v-model="option.value"
            :aria-label="`第${index + 1}项值`" /></label
        ><label
          >第{{ index + 1 }}项标签<input
            v-model="option.label"
            :aria-label="`第${index + 1}项标签`" /></label
        ><button :disabled="options.length <= 1" @click="options.splice(index, 1)"
          >删除第{{ index + 1 }}项</button
        ></div
      >
      <button :disabled="options.length >= 20" @click="append">添加文本选项</button>
      <label
        >文本默认选项<select v-model="defaultValue" aria-label="文本默认选项"
          ><option value="">无默认选项</option
          ><option v-for="(option, index) in options" :key="index" :value="option.value">{{
            option.label
          }}</option></select
        ></label
      >
      <p class="console-description" v-if="!validOptions"
        >请配置 1～20 个值唯一的选项；值使用小写字母开头的字母、数字、下划线，标签为 1～80
        个字符且不含控制字符。</p
      ><p
        class="console-description"
        v-if="defaultValue && !options.some((o) => o.value === defaultValue)"
        >原默认选项已移除，请明确选择新默认值或无默认选项。</p
      >
      <div class="console-actions">
        <button :disabled="!readyVariable" data-testid="text-variable-save" @click="saveVariable"
          >保存文本变量</button
        ><button
          v-if="editing"
          :disabled="references.length > 0"
          data-testid="text-variable-delete"
          @click="emit('remove', editing)"
          >删除文本变量</button
        > </div
      ><p class="console-description" v-if="editing && references.length"
        >变量仍被引用，不能删除：{{ references.join('；') }}</p
      ></fieldset
    >
    <h3 class="console-heading">文本组件</h3
    ><fieldset :disabled="disabled">
      <label
        >文本模式<select v-model="mode" aria-label="文本模式"
          ><option value="STATIC">静态文本</option
          ><option value="DYNAMIC">枚举动态文本</option></select
        ></label
      >
      <label v-if="mode === 'STATIC'"
        >静态文本内容<textarea
          v-model="content"
          aria-label="静态文本内容"
          rows="4"
        ></textarea></label
      ><label v-else
        >文本绑定变量<select v-model="variableKey" aria-label="文本绑定变量"
          ><option value="">请选择文本枚举变量</option
          ><option v-for="variable in variables" :key="variable.key" :value="variable.key"
            >{{ variable.title }} · {{ variable.key }}</option
          ></select
        ></label
      >
      <label
        >文本对齐<select v-model="align" aria-label="文本对齐"
          ><option value="LEFT">左</option
          ><option value="CENTER">居中</option
          ><option value="RIGHT">右</option></select
        ></label
      ><label
        >文本大小<select v-model="size" aria-label="文本大小"
          ><option value="SMALL">小</option
          ><option value="MEDIUM">中</option
          ><option value="LARGE">大</option></select
        ></label
      ><label
        >文本色调<select v-model="tone" aria-label="文本色调"
          ><option value="REGULAR">正文</option
          ><option value="SECONDARY">次要</option
          ><option value="PRIMARY">主题色</option></select
        ></label
      >
      <p class="console-description" v-if="mode === 'STATIC' && !validContent"
        >静态文本最多4096个字符，只允许制表和换行控制字符。</p
      >
      <div class="console-actions">
        <button :disabled="!readyText" data-testid="text-component-add" @click="add()"
          >添加文本组件</button
        ><button
          :disabled="!readyText || selected?.kind !== 'TEXT'"
          data-testid="text-component-rebind"
          @click="add(true)"
          >替换选中文本组件</button
        >
      </div>
    </fieldset></section
  >
</template>
<style scoped>
  section,
  fieldset {
    min-width: 0;
  }
  fieldset {
    margin: 8px 0;
  }
  label {
    display: block;
    margin: 6px 0;
  }
  input:not([type='checkbox']),
  select,
  textarea {
    width: 100%;
    max-width: 100%;
  }
  button {
    margin: 4px;
  }
  p {
    overflow-wrap: anywhere;
  }
</style>
