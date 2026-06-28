<script setup lang="ts">
import { VButton, VEmpty, VModal, VSpace, VTag } from '@halo-dev/components'
import { computed, ref, watch } from 'vue'

import type { MetadataModelSummary } from './metadata'
import {
  fieldDraftToSchema,
  modelOptions,
  schemaToFieldDrafts,
  type CustomAnnotationFieldDraft,
  type CustomAnnotationSettingRequest,
  type CustomFieldType,
} from './customAnnotationSetting'

const props = defineProps<{
  models: MetadataModelSummary[]
  saving: boolean
  editing?: boolean
  initialName?: string
  initialTargetRef?: string
  initialFormSchema?: object[]
}>()

const emit = defineEmits<{
  close: []
  submit: [request: CustomAnnotationSettingRequest]
}>()

const fieldTypes: Array<{ value: CustomFieldType; label: string }> = [
  { value: 'text', label: '单行文本' },
  { value: 'textarea', label: '多行文本' },
  { value: 'select', label: '下拉选择' },
  { value: 'radio', label: '单选' },
  { value: 'url', label: '链接' },
  { value: 'email', label: '邮箱' },
  { value: 'password', label: '密码' },
  { value: 'color', label: '颜色' },
  { value: 'date', label: '日期' },
]

const targetRef = ref<unknown>('')
const settingName = ref('')
const fields = ref<CustomAnnotationFieldDraft[]>([])
const previewValues = ref<Record<string, unknown>>({})
const errorMessage = ref('')

const targetOptions = computed(() => modelOptions(props.models))
const formSchema = computed(() => fields.value.map(fieldDraftToSchema))
const previewSchema = computed(() =>
  fields.value.map((field, index) =>
    previewFieldSchema(field, index),
  ),
)
const normalizedTargetRef = computed(() => normalizeTargetRef(targetRef.value))
const canSubmit = computed(() => Boolean(normalizedTargetRef.value && fields.value.length && !validationError.value))
const validationError = computed(() => validateDraft())
const fieldItemLabels = [{ type: 'text', label: '$value.label' }]
const optionItemLabels = [{ type: 'text', label: '$value.label' }]
const modalTitle = computed(() => props.editing ? '编辑元数据表单定义' : '新增元数据表单定义')
const submitText = computed(() => props.editing ? '保存定义' : '创建定义')

watch(
  targetOptions,
  (options) => {
    if (props.initialTargetRef && !normalizedTargetRef.value) {
      targetRef.value = props.initialTargetRef
      return
    }
    if (!props.editing && options.length && !options.some((option) => option.value === normalizedTargetRef.value)) {
      targetRef.value = options[0].value
    }
  },
  { immediate: true },
)

watch(
  () => [props.initialName, props.initialTargetRef, props.initialFormSchema, props.editing] as const,
  () => {
    targetRef.value = props.initialTargetRef || targetRef.value || ''
    settingName.value = props.initialName || ''
    fields.value = props.initialFormSchema?.length ? schemaToFieldDrafts(props.initialFormSchema) : []
    previewValues.value = {}
    errorMessage.value = ''
  },
  { immediate: true },
)

function newFieldDraft(): CustomAnnotationFieldDraft {
  return {
    id: Math.random().toString(36).slice(2),
    name: '',
    label: '',
    type: 'text',
    help: '',
    placeholder: '',
    required: false,
    options: [newOptionDraft()],
  }
}

function newOptionDraft() {
  return {
    id: Math.random().toString(36).slice(2),
    value: 'default',
    label: '默认',
  }
}

function normalizeTargetRef(value: unknown) {
  if (typeof value === 'string') {
    return value
  }
  if (value && typeof value === 'object') {
    const candidate = value as { value?: unknown; targetRef?: unknown }
    if (typeof candidate.value === 'string') {
      return candidate.value
    }
    if (typeof candidate.targetRef === 'string') {
      return candidate.targetRef
    }
  }
  return ''
}

function validateDraft() {
  if (!normalizedTargetRef.value) {
    return '请选择目标模型。'
  }
  if (!/^[^/]+\/[^/]+$/.test(normalizedTargetRef.value)) {
    return '目标模型格式异常，请重新选择左侧模型列表中的模型。'
  }
  const names = new Set<string>()
  for (const field of fields.value) {
    const name = (field.name || '').trim()
    if (!name) {
      return '字段 Key 不能为空。'
    }
    if (!/^[A-Za-z0-9_.-]+(\/[A-Za-z0-9_.-]+)*$/.test(name)) {
      return `字段 Key ${name} 只能包含字母、数字、_、-、.，可以用 / 分层。`
    }
    if (names.has(name)) {
      return `字段 Key ${name} 重复了。`
    }
    names.add(name)
    if (['select', 'radio'].includes(field.type)) {
      const optionValues = (field.options || []).map((option) => (option.value || '').trim()).filter(Boolean)
      if (!optionValues.length) {
        return `${field.label || name} 至少需要一个选项。`
      }
      if (new Set(optionValues).size !== optionValues.length) {
        return `${field.label || name} 的选项值不能重复。`
      }
    }
    if (['select', 'radio'].includes(field.type) && !(field.options || []).some((option) => (option.value || '').trim())) {
      return `${field.label || name} 至少需要一个选项。`
    }
  }
  if (settingName.value && !/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(settingName.value)) {
    return '定义名称只能使用小写字母、数字和中划线。'
  }
  return ''
}

function submit() {
  errorMessage.value = validationError.value
  if (errorMessage.value) {
    return
  }
  emit('submit', {
    name: props.editing ? props.initialName : settingName.value.trim() || undefined,
    targetRef: normalizedTargetRef.value,
    formSchema: formSchema.value,
  })
}

function previewFieldSchema(field: CustomAnnotationFieldDraft, index: number) {
  const schema = fieldDraftToSchema({
    ...field,
    id: field.id || `field_${index + 1}`,
    type: field.type || 'text',
    name: (field.name || '').trim() || `field_${index + 1}`,
    label: (field.label || '').trim() || (field.name || '').trim() || `字段 ${index + 1}`,
    help: field.help || '',
    placeholder: field.placeholder || '',
    required: Boolean(field.required),
    options: (field.options || []).some((option) => (option.value || '').trim())
      ? field.options
      : [newOptionDraft()],
  })
  delete schema.validation
  if (['select', 'radio'].includes(String(schema.$formkit)) && Array.isArray(schema.options)) {
    schema.value = (schema.options[0] as { value?: string }).value || ''
  }
  return schema
}
</script>

<template>
  <VModal :title="modalTitle" :width="920" @close="emit('close')">
    <div class="custom-setting-builder">
      <div class="custom-setting-builder__header">
        <div class="custom-setting-builder__header-cell">
          <FormKit
            v-model="targetRef"
            v-tooltip="'选择这个表单要挂载到哪个 Halo 模型，例如文章、页面、分类或插件自定义模型。'"
            type="select"
            label="目标模型"
            :options="targetOptions"
            validation="required"
            outer-class="custom-setting-builder__header-field"
          />
        </div>
        <div class="custom-setting-builder__header-cell">
          <FormKit
            v-model="settingName"
            v-tooltip="'可选。留空时会自动生成 AnnotationSetting 名称；手动填写时只能使用小写字母、数字和中划线。'"
            type="text"
            label="定义名称"
            placeholder="自动生成"
            :disabled="editing"
            outer-class="custom-setting-builder__header-field"
          />
          <p class="custom-setting-builder__hint">
            可选；只能使用小写字母、数字和中划线，留空时自动生成。
          </p>
        </div>
      </div>

      <VEmpty
        v-if="!targetOptions.length"
        title="暂无可用模型"
        message="请先加载模型数据后再创建元数据表单"
      />

      <div v-else class="custom-setting-builder__grid">
        <div class="custom-setting-builder__editor">
          <div class="custom-setting-builder__section-title">
            <strong>可视化字段</strong>
            <VTag class="vtag" rounded>{{ fields.length }} 字段</VTag>
          </div>

          <FormKit
            v-model="fields"
            type="array"
            label="字段定义"
            :item-labels="fieldItemLabels"
            outer-class="custom-fields-array"
          >
            <div class="custom-field-array-item">
              <div class="custom-field-card__grid">
                <FormKit type="text" name="name" label="字段 Key" placeholder="example_key" />
                <FormKit type="text" name="label" label="显示名称" placeholder="示例字段" />
                <FormKit type="select" name="type" label="组件类型" :options="fieldTypes" value="text" />
                <FormKit type="text" name="placeholder" label="占位提示" placeholder="请输入" />
              </div>

              <FormKit type="text" name="help" label="帮助说明" placeholder="这个字段会保存为字符串值" />
              <FormKit type="checkbox" name="required" label="必填" outer-class="custom-field-card__check" />

              <FormKit
                type="array"
                name="options"
                label="选项"
                :value="[newOptionDraft()]"
                :item-labels="optionItemLabels"
                outer-class="custom-field-options-array"
              >
                <p class="custom-field-options-array__hint">
                  仅当组件类型选择“下拉选择”或“单选”时生效。
                </p>
                <div class="custom-field-option-array-row">
                  <FormKit type="text" name="value" label="值" placeholder="yes" />
                  <FormKit type="text" name="label" label="显示名" placeholder="是" />
                </div>
              </FormKit>
            </div>
          </FormKit>
        </div>

        <div class="custom-setting-builder__preview">
          <div class="custom-setting-builder__section-title">
            <strong>表单预览</strong>
            <VTag class="vtag" rounded>{{ formSchema.length }} 字段</VTag>
          </div>
          <FormKit v-model="previewValues" type="form" :actions="false" :preserve="true">
            <FormKitSchema :schema="previewSchema" :data="{ formData: {} }" />
          </FormKit>
          <p>
            {{ editing ? '保存后会更新当前 AnnotationSetting，不会重复创建新的表单定义。' : '生成后会创建 AnnotationSetting，并归类到“系统/自定义”下的“自定义”分组。' }}
          </p>
        </div>
      </div>

      <p v-if="errorMessage || validationError" class="custom-setting-builder__error">
        {{ errorMessage || validationError }}
      </p>
    </div>

    <template #footer>
      <VSpace>
        <VButton @click="emit('close')">取消</VButton>
        <VButton
          v-permission="['plugin:annotation-manager:metadata:manage']"
          type="primary"
          :loading="saving"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ submitText }}
        </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
