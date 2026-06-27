<script setup lang="ts">
import { VButton, VEmpty, VStatusDot, VTag } from '@halo-dev/components'
import { computed, ref, watch } from 'vue'

import type {
  AnnotationConflictVo,
  AnnotationFieldDefinition,
  AnnotationResourceVo,
  AnnotationSettingFormVo,
} from '@/api'
import { sourceTypeLabel } from './metadata'

const props = defineProps<{
  resource?: AnnotationResourceVo
  settingForms: AnnotationSettingFormVo[]
  fields: AnnotationFieldDefinition[]
  conflicts: AnnotationConflictVo[]
  saving: boolean
  deletingSetting: boolean
}>()

const emit = defineEmits<{
  save: [resource: AnnotationResourceVo, annotations: Record<string, string>]
  removeAnnotation: [resource: AnnotationResourceVo, annotationKey: string]
  previewDelete: [name: string]
}>()

const annotations = ref<Record<string, string>>({})
const customAnnotations = ref<Array<{ key: string; value: string }>>([])
const showCustomForm = ref(false)

const fieldKeys = computed(() => new Set(props.fields.map((field) => field.annotationKey).filter(Boolean)))
const fieldsBySetting = computed(() => {
  const grouped = new Map<string, AnnotationFieldDefinition[]>()
  props.fields.forEach((field) => {
    if (!field.annotationSettingName) {
      return
    }
    grouped.set(field.annotationSettingName, [...(grouped.get(field.annotationSettingName) || []), field])
  })
  return grouped
})
const conflictKeys = computed(() => new Set(props.conflicts.map((conflict) => conflict.annotationKey).filter(Boolean)))

const settingFormsWithSchema = computed(() =>
  props.settingForms.filter((setting) => setting.formSchema?.length),
)

function resetFromResource() {
  const source = props.resource?.annotations || {}
  annotations.value = {}
  customAnnotations.value = []

  Object.entries(source).forEach(([key, value]) => {
    if (fieldKeys.value.has(key)) {
      annotations.value[key] = value
      return
    }
    customAnnotations.value.push({ key, value })
  })
}

function settingFields(settingName?: string) {
  return settingName ? fieldsBySetting.value.get(settingName) || [] : []
}

function settingHasConflict(settingName?: string) {
  return settingFields(settingName).some((field) => conflictKeys.value.has(field.annotationKey))
}

function fieldHasConflict(field: AnnotationFieldDefinition) {
  return conflictKeys.value.has(field.annotationKey)
}

function fieldValue(field: AnnotationFieldDefinition) {
  return field.annotationKey ? annotations.value[field.annotationKey] : undefined
}

function settingSource(setting: AnnotationSettingFormVo) {
  if (setting.sourceName) {
    return `${sourceTypeLabel(setting.sourceType)}/${setting.sourceName}`
  }
  return sourceTypeLabel(setting.sourceType)
}

function sourceSuffix(setting: AnnotationSettingFormVo) {
  if (!setting.sourceName || !['plugin', 'theme'].includes(setting.sourceType || '')) {
    return ''
  }
  return `${setting.sourceName}${sourceTypeLabel(setting.sourceType)}扩展`
}

function schemaFor(setting: AnnotationSettingFormVo) {
  const suffix = sourceSuffix(setting)
  if (!suffix) {
    return setting.formSchema
  }
  return (setting.formSchema || []).map((node) => decorateSchemaNodeLabel(node, suffix))
}

function decorateSchemaNodeLabel(node: unknown, suffix: string): unknown {
  if (!node || typeof node !== 'object' || Array.isArray(node)) {
    return node
  }
  const copy = { ...(node as Record<string, unknown>) }
  if (typeof copy.label === 'string' && !copy.label.includes(suffix)) {
    copy.label = `${copy.label}（${suffix}）`
  }
  return copy
}

function save() {
  if (!props.resource) {
    return
  }
  const next: Record<string, string> = {}
  Object.entries(annotations.value).forEach(([key, value]) => {
    if (key) {
      next[key] = value ?? ''
    }
  })
  customAnnotations.value.forEach((item) => {
    const key = item.key.trim()
    if (key) {
      next[key] = item.value ?? ''
    }
  })
  emit('save', props.resource, next)
}

function removeAnnotationValue(annotationKey?: string) {
  if (!props.resource || !annotationKey) {
    return
  }
  emit('removeAnnotation', props.resource, annotationKey)
}

function previewDeleteSetting(name?: string) {
  if (!name) {
    return
  }
  emit('previewDelete', name)
}

watch(
  () => [props.resource?.name, props.resource?.annotations, props.fields] as const,
  resetFromResource,
  { immediate: true },
)
</script>

<template>
  <VEmpty v-if="!resource" title="选择资源" message="选择一条模型数据后查看和编辑 metadata.annotations" />

  <div v-else class="resource-annotation-form">
    <div class="resource-annotation-form__header">
      <div>
        <strong>{{ resource.displayName || resource.name }}</strong>
        <span>{{ resource.kind }} / {{ resource.name }}</span>
      </div>
      <VButton
        v-permission="['plugin:annotation-manager:metadata:manage']"
        class="resource-save-button"
        size="xs"
        type="primary"
        :loading="saving"
        @click="save"
      >
        保存元数据
      </VButton>
    </div>

    <div class="metadata-rendered-form">
      <VEmpty
        v-if="!settingFormsWithSchema.length && !customAnnotations.length"
        title="暂无元数据表单"
        message="该资源没有 schema 字段，也没有自定义 annotations"
      />

      <FormKit
        v-else-if="settingFormsWithSchema.length"
        v-model="annotations"
        type="form"
        :actions="false"
        :preserve="true"
      >
        <div
          v-for="setting in settingFormsWithSchema"
          :key="setting.name"
          class="annotation-setting-schema"
          :class="{ 'annotation-setting-schema--conflict': settingHasConflict(setting.name) }"
        >
          <FormKitSchema
            :schema="schemaFor(setting)"
            :data="{
              formData: {},
            }"
          />

          <div class="annotation-setting-governance">
            <div class="tag-row">
              <VTag class="vtag" rounded>{{ settingSource(setting) }}</VTag>
              <VTag
                v-if="settingHasConflict(setting.name)"
                class="vtag vtag--warning"
                theme="danger"
                rounded
              >
                冲突
              </VTag>
              <VTag v-if="setting.effective" class="vtag vtag--success" theme="secondary" rounded>
                有效
              </VTag>
              <VTag v-else class="vtag" rounded>未生效</VTag>
            </div>
            <span>{{ setting.name }}</span>
          </div>

          <div class="annotation-field-source-list">
            <span
              v-for="field in settingFields(setting.name)"
              :key="`${field.annotationSettingName}:${field.annotationKey}`"
              :class="{ 'annotation-field-source-list__item--conflict': conflictKeys.has(field.annotationKey) }"
            >
              <span class="annotation-field-source-list__text">
                {{ field.annotationKey }}
                <small>{{ field.sourceName || field.sourceType }}</small>
                <code v-if="fieldValue(field)">{{ fieldValue(field) }}</code>
              </span>
              <button
                v-if="fieldHasConflict(field) && fieldValue(field)"
                class="metadata-inline-action"
                type="button"
                @click="removeAnnotationValue(field.annotationKey)"
              >
                移除当前值
              </button>
              <button
                v-if="fieldHasConflict(field)"
                v-permission="['plugin:annotation-manager:metadata:manage']"
                class="metadata-inline-action metadata-inline-action--danger"
                type="button"
                :disabled="deletingSetting"
                @click="previewDeleteSetting(field.annotationSettingName)"
              >
                移除定义
              </button>
            </span>
          </div>
        </div>
      </FormKit>

      <details
        :open="showCustomForm"
        class="custom-annotations-form"
        @toggle="showCustomForm = ($event.target as HTMLDetailsElement).open"
      >
        <summary>
          <span>自定义元数据</span>
          <span>{{ showCustomForm ? '收起' : '展开' }}</span>
          <span>{{ customAnnotations.length }} 项</span>
        </summary>
        <FormKit
          type="form"
          :actions="false"
          :preserve="true"
          form-class="custom-annotations-form__body"
        >
          <FormKit
            v-model="customAnnotations"
            type="array"
            label=""
            :item-labels="[{ type: 'text', label: '$value.key' }]"
          >
            <FormKit type="text" label="Key" name="key" validation="required:trim" />
            <FormKit type="text" label="Value" name="value" value="" />
          </FormKit>
        </FormKit>
      </details>

      <div v-if="conflicts.length" class="conflict-summary-inline">
        <VStatusDot state="warning" :text="`${conflicts.length} 个 annotation key 存在重复定义`" />
      </div>
    </div>
  </div>
</template>
