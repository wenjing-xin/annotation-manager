<script setup lang="ts">
import { VButton, VEmpty, VLoading, VStatusDot, VTag } from '@halo-dev/components'
import { computed, nextTick, ref, watch } from 'vue'

import type {
  AnnotationConflictVo,
  AnnotationFieldDefinition,
  AnnotationResourceVo,
  AnnotationSettingFormVo,
} from '@/api'
import { sourceLabel, sourceTypeLabel, uniqueFieldDefinitions } from './metadata'

type SourceTabKey = 'all' | 'plugin' | 'theme' | 'system'

interface SettingRenderItem {
  setting: AnnotationSettingFormVo
  fields: AnnotationFieldDefinition[]
  hasConflict: boolean
  conflictingSettingNames: string[]
}

interface SourceSection {
  key: string
  label: string
  sourceType: string
  items: SettingRenderItem[]
}

interface SourceNameTab {
  key: string
  label: string
  count: number
}

const props = defineProps<{
  resource?: AnnotationResourceVo
  settingForms: AnnotationSettingFormVo[]
  fields: AnnotationFieldDefinition[]
  conflicts: AnnotationConflictVo[]
  saving: boolean
  deletingSetting: boolean
  persistenceKey?: string
  focusSettingName?: string
  focusAnnotationKey?: string
}>()

const emit = defineEmits<{
  save: [resource: AnnotationResourceVo, annotations: Record<string, string>]
  previewDelete: [names: string[]]
  focusHandled: []
}>()

const REMOTE_SCHEMA_KEYS = new Set([
  'action',
  'api',
  'apiUrl',
  'dataSource',
  'endpoint',
  'fetch',
  'loadOptions',
  'optionsLoader',
  'remote',
  'request',
  'uri',
  'url',
])
const annotations = ref<Record<string, string>>({})
const customAnnotationsState = ref<Array<{ key: string; value: string }>>([])
const showCustomForm = ref(false)
const activeSourceTab = ref<SourceTabKey>('all')
const activeSourceSectionKey = ref('all')
const rendering = ref(false)
const renderedFormRef = ref<HTMLElement>()

const fieldKeys = computed(() => new Set(props.fields.map((field) => field.annotationKey).filter(Boolean)))

const fieldsBySetting = computed(() => {
  const grouped = new Map<string, AnnotationFieldDefinition[]>()
  uniqueFieldDefinitions(props.fields).forEach((field) => {
    if (!field.annotationSettingName) {
      return
    }
    grouped.set(field.annotationSettingName, [...(grouped.get(field.annotationSettingName) || []), field])
  })
  return grouped
})

const settingFormsWithSchema = computed(() =>
  props.settingForms.filter((setting) => setting.formSchema?.length),
)

const schemaBySettingName = computed(() => {
  const schemas = new Map<string, Array<Record<string, unknown>>>()
  settingFormsWithSchema.value.forEach((setting) => {
    if (!setting.name) {
      return
    }
    schemas.set(setting.name, sanitizeSchema(setting.formSchema || []))
  })
  return schemas
})

const latestSettingNamesBySourceTarget = computed(() => {
  const grouped = new Map<string, AnnotationSettingFormVo[]>()
  settingFormsWithSchema.value.forEach((setting) => {
    const key = `${setting.targetRef || ''}\n${setting.sourceType || 'unknown'}\n${setting.sourceName || ''}`
    grouped.set(key, [...(grouped.get(key) || []), setting])
  })
  return new Set(
    Array.from(grouped.values())
      .map((items) => [...items].sort(compareSettingsByRecency)[0]?.name)
      .filter(Boolean) as string[],
  )
})

const crossSourceConflictKeys = computed(() => {
  const grouped = new Map<string, AnnotationFieldDefinition[]>()
  uniqueFieldDefinitions(props.fields).forEach((field) => {
    const key = `${field.targetRef || ''}\n${field.annotationKey || ''}`
    grouped.set(key, [...(grouped.get(key) || []), field])
  })
  const keys = new Set<string>()
  grouped.forEach((items, key) => {
    const sources = new Set(items.map((field) => `${field.sourceType || 'unknown'}\n${field.sourceName || ''}`))
    if (items.length > 1 && sources.size > 1) {
      keys.add(key)
    }
  })
  return keys
})

const renderItems = computed<SettingRenderItem[]>(() =>
  settingFormsWithSchema.value.map((setting) => {
    const fields = setting.name ? fieldsBySetting.value.get(setting.name) || [] : []
    const conflictingSettingNames = uniqueSettingNames(
      fields.filter((field) => fieldHasActionableConflict(setting, field)),
    )
    return {
      setting,
      fields,
      hasConflict: conflictingSettingNames.length > 0,
      conflictingSettingNames,
    }
  }),
)

const schemaDefaultAnnotations = computed(() => {
  const defaults: Record<string, string> = {}
  schemaBySettingName.value.forEach((schema) => collectSchemaDefaultAnnotations(schema, defaults))
  return defaults
})

const sourceTabs = computed(() => {
  const counts: Record<SourceTabKey, number> = {
    all: renderItems.value.length + customAnnotationEntries.value.length,
    plugin: 0,
    theme: 0,
    system: customAnnotationEntries.value.length,
  }

  renderItems.value.forEach((item) => {
    counts[sourceTypeKey(item.setting.sourceType)] += 1
  })

  return [
    { key: 'all' as const, label: '全部', count: counts.all },
    { key: 'plugin' as const, label: '插件扩展', count: counts.plugin },
    { key: 'theme' as const, label: '主题扩展', count: counts.theme },
    { key: 'system' as const, label: '系统/自定义', count: counts.system },
  ]
})

const visibleItems = computed(() => {
  if (activeSourceTab.value === 'all') {
    return renderItems.value
  }
  return renderItems.value.filter((item) => sourceTypeKey(item.setting.sourceType) === activeSourceTab.value)
})

const allSourceSections = computed<SourceSection[]>(() => {
  const grouped = new Map<string, SettingRenderItem[]>()
  visibleItems.value.forEach((item) => {
    const key = settingSourceKey(item.setting)
    grouped.set(key, [...(grouped.get(key) || []), item])
  })
  return Array.from(grouped.entries()).map(([key, items]) => ({
    key,
    label: settingSourceNameLabel(items[0].setting),
    sourceType: sourceTypeLabel(items[0].setting.sourceType),
    items: [...items].sort(compareSettingRenderItems),
  }))
})

const sourceNameTabs = computed<SourceNameTab[]>(() => {
  if (!['plugin', 'theme'].includes(activeSourceTab.value)) {
    return []
  }
  const sections = allSourceSections.value
  return [
    { key: 'all', label: '全部', count: sections.reduce((total, section) => total + section.items.length, 0) },
    ...sections.map((section) => ({
      key: section.key,
      label: section.label,
      count: section.items.length,
    })),
  ]
})

const sourceSections = computed<SourceSection[]>(() => {
  if (!sourceNameTabs.value.length || activeSourceSectionKey.value === 'all') {
    return allSourceSections.value
  }
  return allSourceSections.value.filter((section) => section.key === activeSourceSectionKey.value)
})

const formRenderKey = computed(() =>
  [
    props.resource?.name || '',
    activeSourceTab.value,
    activeSourceSectionKey.value,
    sourceSections.value
      .map((section) =>
        `${section.key}:${section.items.map((item) => item.setting.name || '').join(',')}`,
      )
      .join('|'),
    customAnnotationEntries.value.map((item) => item.key).join('|'),
  ].join(':'),
)

const customAnnotationEntries = computed(() =>
  customAnnotationsState.value
    .filter((item) => item.key)
    .sort((left, right) => left.key.localeCompare(right.key)),
)

const showCustomAnnotationsInActiveTab = computed(() =>
  ['all', 'system'].includes(activeSourceTab.value),
)

function resetFromResource() {
  const source = props.resource?.annotations || {}
  annotations.value = {}
  customAnnotationsState.value = []

  Object.entries(schemaDefaultAnnotations.value).forEach(([key, value]) => {
    annotations.value[key] = source[key] ?? value
  })

  Object.entries(source).forEach(([key, value]) => {
    if (fieldKeys.value.has(key)) {
      annotations.value[key] = value
      return
    }
    customAnnotationsState.value.push({ key, value })
  })
}

function sourceTypeKey(sourceType?: string): Exclude<SourceTabKey, 'all'> {
  return sourceType === 'plugin' || sourceType === 'theme' ? sourceType : 'system'
}

function settingSourceKey(setting: AnnotationSettingFormVo) {
  return `${sourceTypeKey(setting.sourceType)}:${setting.sourceName || ''}`
}

function settingSourceLabel(setting: AnnotationSettingFormVo) {
  const displayName = setting.sourceDisplayName || setting.sourceName
  if (displayName) {
    return `${sourceTypeLabel(setting.sourceType)}/${displayName}`
  }
  return sourceTypeLabel(setting.sourceType)
}

function settingSourceNameLabel(setting: AnnotationSettingFormVo) {
  return setting.sourceDisplayName || setting.sourceName || sourceTypeLabel(setting.sourceType)
}

function compareSettingRenderItems(left: SettingRenderItem, right: SettingRenderItem) {
  const timeCompare = compareSettingsByRecency(left.setting, right.setting)
  if (timeCompare) {
    return timeCompare
  }
  return (left.setting.name || '').localeCompare(right.setting.name || '')
}

function compareSettingsByRecency(left: AnnotationSettingFormVo, right: AnnotationSettingFormVo) {
  const timeCompare = timestampValue(right.creationTimestamp) - timestampValue(left.creationTimestamp)
  if (timeCompare) {
    return timeCompare
  }
  return (right.name || '').localeCompare(left.name || '')
}

function timestampValue(value?: string) {
  if (!value) {
    return 0
  }
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : 0
}

function fieldSourceLabel(field: AnnotationFieldDefinition) {
  const matchedSetting = renderItems.value.find((item) => item.setting.name === field.annotationSettingName)?.setting
  return matchedSetting ? settingSourceLabel(matchedSetting) : sourceLabel(field)
}

function uniqueSettingNames(definitions: AnnotationFieldDefinition[]) {
  return Array.from(
    new Set(definitions.map((definition) => definition.annotationSettingName).filter(Boolean) as string[]),
  )
}

function fieldHasConflict(field: AnnotationFieldDefinition) {
  return Boolean(field.duplicate)
}

function fieldHasActionableConflict(setting: AnnotationSettingFormVo, field: AnnotationFieldDefinition) {
  if (!field.duplicate) {
    return false
  }
  if (crossSourceConflictKeys.value.has(`${field.targetRef || ''}\n${field.annotationKey || ''}`)) {
    return true
  }
  return Boolean(setting.name && !latestSettingNamesBySourceTarget.value.has(setting.name))
}

function fieldsWithConflict(fields: AnnotationFieldDefinition[], setting?: AnnotationSettingFormVo) {
  if (!setting) {
    return fields.filter((field) => fieldHasConflict(field))
  }
  return fields.filter((field) => fieldHasActionableConflict(setting, field))
}

function fieldsWithoutConflict(fields: AnnotationFieldDefinition[]) {
  return fields.filter((field) => !fieldHasConflict(field))
}

function sectionConflictingSettingNames(section: SourceSection) {
  const itemsWithConflict = section.items.filter((item) => item.hasConflict)
  return uniqueSettingNames(itemsWithConflict.flatMap((item) => fieldsWithConflict(item.fields, item.setting)))
}

function sectionConflictFieldCount(section: SourceSection) {
  return section.items.reduce((total, item) => total + fieldsWithConflict(item.fields, item.setting).length, 0)
}

function sectionNormalFieldCount(section: SourceSection) {
  return section.items.reduce((total, item) => total + fieldsWithoutConflict(item.fields).length, 0)
}

function canRemoveSectionConflicts(section: SourceSection) {
  const sourceType = sourceTypeKey(section.items[0]?.setting.sourceType)
  return ['plugin', 'theme'].includes(sourceType) && sectionConflictingSettingNames(section).length > 0
}

function schemaForRender(setting: AnnotationSettingFormVo) {
  return setting.name ? schemaBySettingName.value.get(setting.name) || [] : []
}

function save() {
  if (!props.resource) {
    return
  }
  const next: Record<string, string> = {}
  customAnnotationsState.value.forEach((item) => {
    const key = item.key.trim()
    if (key) {
      next[key] = item.value ?? ''
    }
  })
  Object.entries(annotations.value).forEach(([key, value]) => {
    if (key) {
      next[key] = value ?? ''
    }
  })
  emit('save', props.resource, next)
}

function previewDeleteSettings(names: string[]) {
  const normalizedNames = Array.from(new Set(names.filter(Boolean)))
  if (!normalizedNames.length) {
    return
  }
  emit('previewDelete', normalizedNames)
}

interface FormSelectionState {
  activeSourceTab?: SourceTabKey
  activeSourceSectionKey?: string
  showCustomForm?: boolean
}

function readFormSelectionState(): FormSelectionState {
  return {
    activeSourceTab: sourceTabFromQuery(queryString('metadataSource')),
    activeSourceSectionKey: queryString('metadataSourceKey') || 'all',
    showCustomForm: queryString('customOpen') === 'true',
  }
}

function saveFormSelectionState() {
  replaceQuery({
    metadataSource: activeSourceTab.value === 'all' ? undefined : activeSourceTab.value,
    metadataSourceKey: activeSourceSectionKey.value === 'all' ? undefined : activeSourceSectionKey.value,
    customOpen: showCustomForm.value ? 'true' : undefined,
  })
}

function restoreFormSelectionState() {
  const state = readFormSelectionState()
  if (state.activeSourceTab && sourceTabs.value.some((tab) => tab.key === state.activeSourceTab)) {
    activeSourceTab.value = state.activeSourceTab
  } else {
    activeSourceTab.value = 'all'
  }
  activeSourceSectionKey.value = state.activeSourceSectionKey || 'all'
  showCustomForm.value = Boolean(state.showCustomForm)
}

function queryString(name: string) {
  return new URLSearchParams(window.location.search).get(name) || undefined
}

function sourceTabFromQuery(value?: string): SourceTabKey | undefined {
  return value === 'plugin' || value === 'theme' || value === 'system' || value === 'all'
    ? value
    : undefined
}

function replaceQuery(updates: Record<string, string | undefined>) {
  const url = new URL(window.location.href)
  Object.entries(updates).forEach(([key, value]) => {
    if (value === undefined || value === '') {
      url.searchParams.delete(key)
      return
    }
    url.searchParams.set(key, value)
  })
  window.history.replaceState(window.history.state, '', url)
}

function focusOperationTarget() {
  if (!props.focusSettingName && !props.focusAnnotationKey) {
    return
  }
  void nextTick(() => {
    const container = renderedFormRef.value
    if (!container) {
      emit('focusHandled')
      return
    }
    const settingElement = Array.from(
      container.querySelectorAll<HTMLElement>('.annotation-setting-schema'),
    ).find((element) => element.dataset.settingName === props.focusSettingName)
    const annotationElement = Array.from(
      container.querySelectorAll<HTMLElement>('.annotation-conflict-row'),
    ).find((element) => element.dataset.annotationKey === props.focusAnnotationKey)
    const target = settingElement || annotationElement || container.querySelector<HTMLElement>('.annotation-source-section')
    target?.scrollIntoView({ block: 'center' })
    emit('focusHandled')
  })
}

watch(
  () => [
    props.resource?.name,
    props.resource?.annotations,
    props.fields,
    props.settingForms,
  ] as const,
  () => {
    rendering.value = true
    resetFromResource()
    void nextTick(() => {
      rendering.value = false
    })
  },
  { immediate: true },
)

watch(
  () => [
    props.persistenceKey,
  ] as const,
  restoreFormSelectionState,
  { immediate: true },
)

watch([activeSourceTab, activeSourceSectionKey, showCustomForm], saveFormSelectionState)

watch(sourceTabs, (tabs) => {
  if (!tabs.some((tab) => tab.key === activeSourceTab.value)) {
    activeSourceTab.value = 'all'
  }
})

watch(activeSourceTab, () => {
  activeSourceSectionKey.value = 'all'
})

watch(sourceNameTabs, (tabs) => {
  if (!tabs.some((tab) => tab.key === activeSourceSectionKey.value)) {
    activeSourceSectionKey.value = 'all'
  }
})

watch(
  () => [
    props.focusSettingName,
    props.focusAnnotationKey,
    props.resource?.name,
    sourceSections.value.map((section) => section.key).join(','),
  ] as const,
  focusOperationTarget,
  { flush: 'post' },
)

function sanitizeSchema(schema: object[]) {
  return sanitizeSchemaNode(schema) as Array<Record<string, unknown>>
}

function collectSchemaDefaultAnnotations(node: unknown, defaults: Record<string, string>) {
  if (Array.isArray(node)) {
    node.forEach((item) => collectSchemaDefaultAnnotations(item, defaults))
    return
  }
  if (!node || typeof node !== 'object') {
    return
  }

  const schemaNode = node as Record<string, unknown>
  if (schemaNode.$formkit && typeof schemaNode.name === 'string' && !(schemaNode.name in defaults)) {
    defaults[schemaNode.name] = schemaValueToAnnotation(schemaNode.value)
  }
  Object.values(schemaNode).forEach((value) => collectSchemaDefaultAnnotations(value, defaults))
}

function schemaValueToAnnotation(value: unknown) {
  if (value === undefined || value === null) {
    return ''
  }
  return typeof value === 'string' ? value : String(value)
}

function sanitizeSchemaNode(node: unknown): unknown {
  if (Array.isArray(node)) {
    return node.map((item) => sanitizeSchemaNode(item))
  }
  if (!node || typeof node !== 'object') {
    return node
  }

  const sanitized: Record<string, unknown> = {}
  Object.entries(node as Record<string, unknown>).forEach(([key, value]) => {
    if (shouldDropRemoteSchemaEntry(key, value)) {
      if (key === 'options') {
        sanitized[key] = []
      }
      return
    }
    sanitized[key] = sanitizeSchemaNode(value)
  })
  return sanitized
}

function shouldDropRemoteSchemaEntry(key: string, value: unknown) {
  const normalizedKey = key.toLowerCase()
  if (key === 'options' && containsDynamicApiUrl(value) && !Array.isArray(value)) {
    return true
  }
  if (!containsDynamicApiUrl(value)) {
    return false
  }
  return REMOTE_SCHEMA_KEYS.has(key) || normalizedKey.includes('url') || normalizedKey.includes('api')
}

function containsDynamicApiUrl(value: unknown): boolean {
  if (typeof value === 'string') {
    return /(^|["'\s])\/?apis\/(?!api\.annotation-manager\.wenjing\.com)/.test(value)
  }
  if (Array.isArray(value)) {
    return value.some((item) => containsDynamicApiUrl(item))
  }
  if (value && typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).some((item) => containsDynamicApiUrl(item))
  }
  return false
}
</script>

<template>
  <VEmpty v-if="!resource" title="选择资源" message="选择一条模型数据后查看和编辑 metadata.annotations" />

  <div v-else class="resource-annotation-form">
    <div class="resource-annotation-form__header">
      <div class="resource-annotation-form__actions">
        <div class="metadata-source-tabs" role="tablist" aria-label="元数据来源">
          <button
            v-for="tab in sourceTabs"
            :key="tab.key"
            class="metadata-source-tab"
            :class="{ 'metadata-source-tab--active': activeSourceTab === tab.key }"
            type="button"
            role="tab"
            :aria-selected="activeSourceTab === tab.key"
            @click="activeSourceTab = tab.key"
          >
            <span>{{ tab.label }}</span>
            <small>{{ tab.count }}</small>
          </button>
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
    </div>

    <div ref="renderedFormRef" class="metadata-rendered-form">
      <VLoading v-if="rendering" />

      <VEmpty
        v-else-if="!renderItems.length && !customAnnotationEntries.length && !showCustomAnnotationsInActiveTab"
        title="暂无元数据表单"
        message="该模型没有已定义的 AnnotationSetting 表单字段，也没有自定义 annotations"
      />

      <VEmpty
        v-else-if="!sourceSections.length && !showCustomAnnotationsInActiveTab"
        title="暂无该来源的元数据"
        message="切换其他来源查看插件、主题或系统字段"
      />

      <template v-else>
        <div
          v-if="sourceNameTabs.length"
          class="metadata-source-section-strip"
          role="tablist"
          aria-label="扩展来源"
        >
          <button
            v-for="tab in sourceNameTabs"
            :key="tab.key"
            class="metadata-source-name-tab"
            :class="{ 'metadata-source-name-tab--active': activeSourceSectionKey === tab.key }"
            type="button"
            role="tab"
            :aria-selected="activeSourceSectionKey === tab.key"
            @click="activeSourceSectionKey = tab.key"
          >
            <span>{{ tab.label }}</span>
            <small>{{ tab.count }}</small>
          </button>
        </div>

        <FormKit
          v-if="sourceSections.length"
          :key="formRenderKey"
          v-model="annotations"
          type="form"
          :actions="false"
          :preserve="true"
        >
          <section
            v-for="section in sourceSections"
            :key="`${formRenderKey}:${section.key}`"
            class="annotation-source-section"
          >
            <div class="annotation-source-section__header">
              <div class="tag-row">
                <VTag class="vtag" rounded>{{ section.label }}</VTag>
                <VTag class="vtag" rounded>{{ section.sourceType }}</VTag>
                <VTag class="vtag" rounded>{{ section.items.length }} 个定义</VTag>
              </div>
              <button
                v-if="canRemoveSectionConflicts(section)"
                v-permission="['plugin:annotation-manager:metadata:manage']"
                class="metadata-inline-action metadata-inline-action--danger"
                type="button"
                :disabled="deletingSetting"
                @click="previewDeleteSettings(sectionConflictingSettingNames(section))"
              >
                一键移除本来源冲突定义
              </button>
            </div>
            <div v-if="canRemoveSectionConflicts(section)" class="annotation-section-notice">
              <VStatusDot
                state="warning"
                :text="`将预览 ${sectionConflictingSettingNames(section).length} 个重复 AnnotationSetting，涉及 ${sectionConflictFieldCount(section)} 个冲突字段`"
              />
              <span v-if="sectionNormalFieldCount(section)">
                将保留该来源最新的一份定义；旧定义里还包含 {{ sectionNormalFieldCount(section) }} 个正常字段，删除定义时会一起从表单定义中移除，但不会删除当前资源已保存的 metadata.annotations 值。
              </span>
            </div>

            <div
              v-for="item in section.items"
              :key="item.setting.name"
              class="annotation-setting-schema"
              :class="{ 'annotation-setting-schema--conflict': item.hasConflict }"
              :data-setting-name="item.setting.name"
            >
              <FormKitSchema
                :key="`${formRenderKey}:${item.setting.name}`"
                :schema="schemaForRender(item.setting)"
                :data="{ formData: resource }"
              />

              <div class="annotation-setting-governance">
                <div class="tag-row">
                  <VTag class="vtag" rounded>{{ settingSourceLabel(item.setting) }}</VTag>
                  <VTag
                    v-if="item.hasConflict"
                    class="vtag vtag--warning"
                    theme="danger"
                    rounded
                  >
                    冲突
                  </VTag>
                  <VTag v-if="item.setting.effective" class="vtag vtag--success" theme="secondary" rounded>
                    有效
                  </VTag>
                  <VTag v-else class="vtag" rounded>未生效</VTag>
                </div>
                <span>{{ item.setting.name }}</span>
              </div>

              <div v-if="item.fields.length" class="annotation-field-overview">
                <div
                  v-for="field in item.fields"
                  :key="`${field.annotationSettingName}:${field.annotationKey}`"
                  class="annotation-field-overview__item"
                  :class="{ 'annotation-field-overview__item--conflict': fieldHasActionableConflict(item.setting, field) }"
                >
                  <span>{{ field.label || field.annotationKey }}</span>
                  <code>{{ field.annotationKey }}</code>
                  <VTag
                    v-if="fieldHasActionableConflict(item.setting, field)"
                    class="vtag vtag--warning"
                    theme="danger"
                    rounded
                  >
                    冲突
                  </VTag>
                  <VTag v-else class="vtag" rounded>正常</VTag>
                </div>
              </div>

              <div v-if="item.hasConflict" class="annotation-conflict-list">
                <div
                  v-for="field in fieldsWithConflict(item.fields, item.setting)"
                  :key="`${field.annotationSettingName}:${field.annotationKey}`"
                  class="annotation-conflict-row"
                  :data-annotation-key="field.annotationKey"
                >
                  <div class="annotation-conflict-row__main">
                    <div class="tag-row">
                      <VTag class="vtag vtag--warning" theme="danger" rounded>冲突字段</VTag>
                      <VTag class="vtag" rounded>{{ fieldSourceLabel(field) }}</VTag>
                    </div>
                    <strong>{{ field.label || field.annotationKey }}</strong>
                    <span>{{ field.annotationKey }}</span>
                  </div>
                  <button
                    v-permission="['plugin:annotation-manager:metadata:manage']"
                    class="metadata-inline-action metadata-inline-action--danger"
                    type="button"
                    :disabled="deletingSetting"
                    @click="previewDeleteSettings([field.annotationSettingName || ''])"
                  >
                    移除定义
                  </button>
                </div>
              </div>
            </div>
          </section>
        </FormKit>

        <details
          v-if="showCustomAnnotationsInActiveTab"
          :open="showCustomForm"
          class="custom-annotations-form"
          @toggle="showCustomForm = ($event.target as HTMLDetailsElement).open"
        >
          <summary>
            <span>{{ showCustomForm ? '收起自定义元数据' : '添加自定义元数据' }}</span>
            <small>{{ customAnnotationEntries.length }} 项</small>
          </summary>
          <FormKit
            type="form"
            :actions="false"
            :preserve="true"
            form-class="custom-annotations-form__body"
          >
            <FormKit
              v-model="customAnnotationsState"
              type="array"
              label="自定义"
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
      </template>
    </div>
  </div>
</template>
