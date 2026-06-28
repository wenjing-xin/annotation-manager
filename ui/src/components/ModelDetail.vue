<script setup lang="ts">
import {
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VPagination,
  VStatusDot,
  VTag,
} from '@halo-dev/components'
import { computed, ref, watch } from 'vue'

import type {
  AnnotationConflictVo,
  AnnotationFieldDefinition,
  AnnotationResourceVo,
  AnnotationSettingFormVo,
  AnnotationValueUsageVo,
} from '@/api'
import {
  modelSourceLabel,
  sourceLabel,
  sourceTypeLabel,
  supportsValueScan,
  type MetadataModelSummary,
  uniqueConflicts,
  uniqueFieldDefinitions,
} from './metadata'
import CustomAnnotationSettingModal from './CustomAnnotationSettingModal.vue'
import ResourceAnnotationsForm from './ResourceAnnotationsForm.vue'
import type { CustomAnnotationSettingRequest } from './customAnnotationSetting'

const props = defineProps<{
  model?: MetadataModelSummary
  fields: AnnotationFieldDefinition[]
  conflicts: AnnotationConflictVo[]
  values: AnnotationValueUsageVo[]
  resources: AnnotationResourceVo[]
  settingForms: AnnotationSettingFormVo[]
  models: MetadataModelSummary[]
  loading: boolean
  valueLoading: boolean
  resourceLoading: boolean
  deletingSetting: boolean
  cleaningValues: boolean
  savingResource: boolean
  creatingCustomSetting: boolean
  focusSettingName?: string
  focusAnnotationKey?: string
}>()

const emit = defineEmits<{
  refreshValues: []
  previewDelete: [names: string[]]
  previewCleanup: [annotationKey: string]
  saveResource: [resource: AnnotationResourceVo, annotations: Record<string, string>]
  createCustomSetting: [request: CustomAnnotationSettingRequest]
  updateCustomSetting: [name: string, request: CustomAnnotationSettingRequest]
  deleteCustomSetting: [name: string]
  focusHandled: []
}>()

interface ResourceSelectionState {
  selectedResourceName?: string
  resourcePage?: number
  resourceSize?: number
}

type SourceTabKey = 'all' | 'plugin' | 'theme' | 'system'

interface GovernanceSettingItem {
  name: string
  fields: AnnotationFieldDefinition[]
  creationTimestamp?: string
  effective: boolean
  duplicate: boolean
  fieldConflictCount: number
  formSchemaCount: number
  formSchema: object[]
}

interface GovernanceTargetGroup {
  key: string
  targetRef: string
  source: string
  sourceKey: string
  sourceType: string
  sourceTypeKey: string
  sourceSubtype: string
  settings: GovernanceSettingItem[]
  latestSettingName?: string
  fieldCount: number
  fieldConflictCount: number
  duplicateSettingNames: string[]
}

const modelFields = computed(() => {
  if (!props.model) {
    return []
  }
  return uniqueFieldDefinitions(props.fields.filter((field) => field.targetRef === props.model?.targetRef))
})

const modelConflicts = computed(() => {
  if (!props.model) {
    return []
  }
  return uniqueConflicts(props.conflicts.filter((conflict) => conflict.targetRef === props.model?.targetRef))
})

const sourceGroups = computed(() => {
  const grouped = new Map<string, AnnotationFieldDefinition[]>()
  modelFields.value.forEach((field) => {
    const key = sourceLabel(field)
    grouped.set(key, [...(grouped.get(key) || []), field])
  })
  return Array.from(grouped.entries()).map(([label, fields]) => ({
    label,
    fields,
    effectiveCount: fields.filter((field) => field.effective).length,
  }))
})

const conflictKeys = computed(() => new Set(modelConflicts.value.map((conflict) => conflict.annotationKey)))
const isAnnotationSettingsModel = computed(() => props.model?.special === 'annotation-settings')
const customSettingModalVisible = ref(false)
const editingCustomSetting = ref<GovernanceSettingItem>()
const editingCustomTargetRef = ref<string>()

const fieldsBySettingName = computed(() => {
  const grouped = new Map<string, AnnotationFieldDefinition[]>()
  uniqueFieldDefinitions(props.fields).forEach((field) => {
    const settingName = field.annotationSettingName
    if (!settingName) {
      return
    }
    grouped.set(settingName, [...(grouped.get(settingName) || []), field])
  })
  return grouped
})

const annotationSettingGroups = computed(() => {
  const grouped = new Map<string, {
    key: string
    targetRef: string
    source: string
    sourceKey: string
    sourceType: string
    sourceTypeKey: string
    sourceSubtype: string
    settings: GovernanceSettingItem[]
  }>()
  const seenSettingNames = new Set<string>()

  props.settingForms.forEach((form) => {
    const name = form.name || '-'
    seenSettingNames.add(name)
    const targetRef = form.targetRef || '-'
    const sourceKey = settingSourceIdentity(form)
    const key = `${targetRef}\n${sourceKey}`
    const sourceSubtype = settingSourceSubtype(form)
    const current = grouped.get(key) || {
      key,
      targetRef,
      source: settingSourceLabel(form),
      sourceKey,
      sourceType: sourceTypeLabel(form.sourceType),
      sourceTypeKey: sourceTypeGroupKey(form.sourceType),
      sourceSubtype,
      settings: [],
    }
    const settingFields = fieldsBySettingName.value.get(name) || []
    current.settings.push({
      name,
      fields: settingFields,
      creationTimestamp: form.creationTimestamp,
      effective: Boolean(form.effective),
      duplicate: false,
      fieldConflictCount: settingFields.filter((field) => field.duplicate).length,
      formSchemaCount: form.formSchema?.length || 0,
      formSchema: form.formSchema || [],
    })
    grouped.set(key, current)
  })

  uniqueFieldDefinitions(props.fields).forEach((field) => {
    const settingName = field.annotationSettingName || '-'
    if (seenSettingNames.has(settingName)) {
      return
    }
    const targetRef = field.targetRef || '-'
    const sourceKey = fieldSourceIdentity(field)
    const key = `${targetRef}\n${sourceKey}`
    const current = grouped.get(key) || {
      key,
      targetRef,
      source: sourceLabel(field),
      sourceKey,
      sourceType: sourceTypeLabel(field.sourceType),
      sourceTypeKey: sourceTypeGroupKey(field.sourceType),
      sourceSubtype: 'system',
      settings: [],
    }
    const settingFields = fieldsBySettingName.value.get(settingName) || []
    current.settings.push({
      name: settingName,
      fields: settingFields,
      effective: Boolean(field.effective),
      duplicate: false,
      fieldConflictCount: settingFields.filter((item) => item.duplicate).length,
      formSchemaCount: 0,
      formSchema: [],
    })
    grouped.set(key, current)
  })

  return Array.from(grouped.values())
    .map((group) => {
      const latestSettingName = [...group.settings]
        .sort(compareSettingGovernanceItems)[0]?.name
      const settings = group.settings
        .map((setting) => ({
          ...setting,
          duplicate: Boolean(latestSettingName && setting.name !== latestSettingName),
        }))
        .sort((left, right) => Number(right.duplicate) - Number(left.duplicate) || compareSettingGovernanceItems(left, right))
      return {
        ...group,
        settings,
        latestSettingName,
        fieldCount: settings.reduce((total, setting) => total + setting.fields.length, 0),
        fieldConflictCount: settings.reduce((total, setting) => total + setting.fieldConflictCount, 0),
        duplicateSettingNames: settings
          .filter((setting) => setting.duplicate)
          .map((setting) => setting.name),
      }
    })
    .sort((left, right) =>
      right.duplicateSettingNames.length - left.duplicateSettingNames.length
      || left.targetRef.localeCompare(right.targetRef)
      || left.source.localeCompare(right.source),
    )
})

const annotationSettingSourceTypeGroups = computed(() => {
  const grouped = new Map<string, {
    key: string
    label: string
    sources: Map<string, {
      key: string
      label: string
      sourceType: string
      sourceSubtype: string
      targets: GovernanceTargetGroup[]
      settingCount: number
      fieldCount: number
      duplicateSettingNames: string[]
    }>
  }>()

  annotationSettingGroups.value.forEach((targetGroup) => {
    const typeKey = targetGroup.sourceTypeKey
    const typeGroup = grouped.get(typeKey) || {
      key: typeKey,
      label: sourceTypeGroupLabel(typeKey),
      sources: new Map(),
    }
    const sourceGroup = typeGroup.sources.get(targetGroup.sourceKey) || {
      key: targetGroup.sourceKey,
      label: targetGroup.source,
      sourceType: targetGroup.sourceType,
      sourceSubtype: targetGroup.sourceSubtype,
      targets: [],
      settingCount: 0,
      fieldCount: 0,
      duplicateSettingNames: [],
    }
    sourceGroup.targets.push(targetGroup)
    sourceGroup.settingCount += targetGroup.settings.length
    sourceGroup.fieldCount += targetGroup.fieldCount
    sourceGroup.duplicateSettingNames.push(...targetGroup.duplicateSettingNames)
    typeGroup.sources.set(targetGroup.sourceKey, sourceGroup)
    grouped.set(typeKey, typeGroup)
  })

  return Array.from(grouped.values())
    .sort((left, right) => sourceTypeGroupRank(left.key) - sourceTypeGroupRank(right.key))
    .map((typeGroup) => {
      const sources = Array.from(typeGroup.sources.values())
        .map((source) => ({
          ...source,
          targets: source.targets.sort((left, right) =>
            right.duplicateSettingNames.length - left.duplicateSettingNames.length
            || left.targetRef.localeCompare(right.targetRef),
          ),
          duplicateSettingNames: Array.from(new Set(source.duplicateSettingNames)),
        }))
        .sort((left, right) =>
          right.duplicateSettingNames.length - left.duplicateSettingNames.length
          || left.label.localeCompare(right.label),
        )
      return {
        key: typeGroup.key,
        label: typeGroup.label,
        sources,
        settingCount: sources.reduce((total, source) => total + source.settingCount, 0),
        fieldCount: sources.reduce((total, source) => total + source.fieldCount, 0),
        duplicateSettingNames: Array.from(new Set(sources.flatMap((source) => source.duplicateSettingNames))),
      }
    })
})

const selectedResourceName = ref<string>()
const resourcePage = ref(1)
const resourceSize = ref(20)
const resourceSizeOptions = [10, 20, 50, 100]
const annotationSettingSourceTab = ref<SourceTabKey>(readSourceTabQuery('asSource'))
const annotationSettingSourceKey = ref(queryString('asSourceName') || 'all')
const annotationSettingPage = ref(queryNumber('asPage') || 1)
const annotationSettingSize = ref(queryNumber('asSize') || 10)
const annotationSettingSizeOptions = [10, 20, 50, 100]
const paginatedResources = computed(() => {
  const start = (resourcePage.value - 1) * resourceSize.value
  return props.resources.slice(start, start + resourceSize.value)
})
const selectedResource = computed(() =>
  props.resources.find((resource) => resource.name === selectedResourceName.value),
)

const annotationSettingSourceTabs = computed(() => {
  const counts: Record<SourceTabKey, number> = {
    all: 0,
    plugin: 0,
    theme: 0,
    system: 0,
  }

  annotationSettingSourceTypeGroups.value.forEach((group) => {
    const key = sourceTabKey(group.key)
    counts[key] += group.settingCount
    counts.all += group.settingCount
  })

  return [
    { key: 'all' as const, label: '全部', count: counts.all },
    { key: 'plugin' as const, label: '插件扩展', count: counts.plugin },
    { key: 'theme' as const, label: '主题扩展', count: counts.theme },
    { key: 'system' as const, label: '系统/自定义', count: counts.system },
  ]
})

const visibleAnnotationSettingSources = computed(() => {
  if (annotationSettingSourceTab.value === 'all') {
    return annotationSettingSourceTypeGroups.value.flatMap((group) => group.sources)
  }
  const typeGroup = annotationSettingSourceTypeGroups.value.find(
    (group) => sourceTabKey(group.key) === annotationSettingSourceTab.value,
  )
  return typeGroup?.sources || []
})

const annotationSettingSourceNameTabs = computed(() => {
  if (!['plugin', 'theme', 'system'].includes(annotationSettingSourceTab.value)) {
    return []
  }
  const sources = visibleAnnotationSettingSources.value
  return [
    {
      key: 'all',
      label: '全部',
      count: sources.reduce((total, source) => total + source.settingCount, 0),
    },
    ...sources.map((source) => ({
      key: source.key,
      label: source.label,
      count: source.settingCount,
    })),
  ]
})

const selectedAnnotationSettingSources = computed(() => {
  if (!annotationSettingSourceNameTabs.value.length || annotationSettingSourceKey.value === 'all') {
    return visibleAnnotationSettingSources.value
  }
  return visibleAnnotationSettingSources.value.filter((source) => source.key === annotationSettingSourceKey.value)
})

const annotationSettingTargetGroups = computed(() =>
  selectedAnnotationSettingSources.value
    .flatMap((source) => source.targets)
    .sort((left, right) =>
      right.duplicateSettingNames.length - left.duplicateSettingNames.length
      || left.source.localeCompare(right.source)
      || left.targetRef.localeCompare(right.targetRef),
    ),
)

const paginatedAnnotationSettingGroups = computed(() => {
  const start = (annotationSettingPage.value - 1) * annotationSettingSize.value
  return annotationSettingTargetGroups.value.slice(start, start + annotationSettingSize.value)
})

const visibleAnnotationSettingDuplicateNames = computed(() =>
  Array.from(new Set(annotationSettingTargetGroups.value.flatMap((group) => group.duplicateSettingNames))),
)

function resourceMetadataCount(resource: AnnotationResourceVo) {
  return Object.keys(resource.annotations || {}).length
}

function resourceConflictCount(resource: AnnotationResourceVo) {
  return Object.keys(resource.annotations || {}).filter((key) => conflictKeys.value.has(key)).length
}

function settingFieldKeys(fields: AnnotationFieldDefinition[]) {
  return Array.from(new Set(fields.map((field) => field.annotationKey).filter(Boolean))).join('、') || '-'
}

function settingSourceIdentity(setting: AnnotationSettingFormVo) {
  if (sourceTypeGroupKey(setting.sourceType) === 'system') {
    return `system:${settingSourceSubtype(setting)}`
  }
  return `${setting.sourceType || 'unknown'}:${setting.sourceName || ''}`
}

function fieldSourceIdentity(field: AnnotationFieldDefinition) {
  return `${field.sourceType || 'unknown'}:${field.sourceName || ''}`
}

function sourceTypeGroupKey(sourceType?: string) {
  if (sourceType === 'plugin' || sourceType === 'theme') {
    return sourceType
  }
  return 'system'
}

function sourceTypeGroupLabel(sourceType?: string) {
  switch (sourceType) {
    case 'plugin':
      return '插件扩展'
    case 'theme':
      return '主题扩展'
    default:
      return '系统/自定义'
  }
}

function sourceTypeGroupRank(sourceType?: string) {
  switch (sourceType) {
    case 'plugin':
      return 0
    case 'theme':
      return 1
    default:
      return 2
  }
}

function sourceTabKey(sourceType?: string): Exclude<SourceTabKey, 'all'> {
  return sourceType === 'plugin' || sourceType === 'theme' ? sourceType : 'system'
}

function readSourceTabQuery(name: string): SourceTabKey {
  const value = queryString(name)
  return value === 'plugin' || value === 'theme' || value === 'system' ? value : 'all'
}

function settingSourceLabel(setting: AnnotationSettingFormVo) {
  if (sourceTypeGroupKey(setting.sourceType) === 'system') {
    return sourceSubtypeLabel(settingSourceSubtype(setting))
  }
  const sourceName = setting.sourceDisplayName || setting.sourceName
  if (sourceName) {
    return `${sourceTypeLabel(setting.sourceType)}/${sourceName}`
  }
  return sourceTypeLabel(setting.sourceType)
}

function settingSourceSubtype(setting: AnnotationSettingFormVo) {
  const subtype = (setting as AnnotationSettingFormVo & { sourceSubtype?: string }).sourceSubtype
  return subtype === 'custom' ? 'custom' : 'system'
}

function sourceSubtypeLabel(subtype?: string) {
  return subtype === 'custom' ? '自定义' : '系统'
}

function isCustomSettingGroup(group: GovernanceTargetGroup) {
  return group.sourceSubtype === 'custom'
}

function openCreateCustomSettingModal() {
  editingCustomSetting.value = undefined
  editingCustomTargetRef.value = undefined
  customSettingModalVisible.value = true
}

function openEditCustomSettingModal(group: GovernanceTargetGroup, setting: GovernanceSettingItem) {
  editingCustomTargetRef.value = group.targetRef
  editingCustomSetting.value = setting
  customSettingModalVisible.value = true
}

function closeCustomSettingModal() {
  customSettingModalVisible.value = false
  editingCustomSetting.value = undefined
  editingCustomTargetRef.value = undefined
}

function submitCustomSetting(request: CustomAnnotationSettingRequest) {
  if (editingCustomSetting.value?.name) {
    emit('updateCustomSetting', editingCustomSetting.value.name, request)
  } else {
    emit('createCustomSetting', request)
  }
  closeCustomSettingModal()
}

function sourceTabTooltip(key: SourceTabKey) {
  switch (key) {
    case 'plugin':
      return '插件扩展来源于已启动插件提供的 AnnotationSetting。'
    case 'theme':
      return '主题扩展来源于当前或历史主题提供的 AnnotationSetting。'
    case 'system':
      return '系统/自定义包含未归属于插件主题的系统表单，以及在此处手动创建的自定义表单。'
    default:
      return '查看所有 AnnotationSetting 表单定义。'
  }
}

function sourceNameTooltip(label: string) {
  return `只查看 ${label} 这一组 AnnotationSetting。`
}

function compareSettingGovernanceItems(
  left: { creationTimestamp?: string; name: string },
  right: { creationTimestamp?: string; name: string },
) {
  const timeCompare = timestampValue(right.creationTimestamp) - timestampValue(left.creationTimestamp)
  if (timeCompare) {
    return timeCompare
  }
  return right.name.localeCompare(left.name)
}

function timestampValue(value?: string) {
  if (!value) {
    return 0
  }
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : 0
}

function schemaForGovernanceSetting(setting: GovernanceSettingItem) {
  return sanitizeSchema(setting.formSchema)
}

function sanitizeSchema(schema: object[]) {
  return sanitizeSchemaNode(schema) as Array<Record<string, unknown>>
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
  return ['action', 'api', 'apiurl', 'datasource', 'endpoint', 'fetch', 'loadoptions', 'optionsloader', 'remote', 'request', 'uri', 'url']
    .includes(normalizedKey)
    || normalizedKey.includes('url')
    || normalizedKey.includes('api')
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

function saveResourceSelectionState() {
  replaceQuery({
    resource: selectedResourceName.value,
    page: String(resourcePage.value || 1),
    size: String(resourceSize.value || 20),
  })
}

function clampPage(page: number, size: number) {
  const totalPages = Math.max(1, Math.ceil(props.resources.length / size))
  return Math.min(Math.max(1, page), totalPages)
}

function clampAnnotationSettingPage(page: number, size: number) {
  const totalPages = Math.max(1, Math.ceil(annotationSettingTargetGroups.value.length / size))
  return Math.min(Math.max(1, page), totalPages)
}

function restoreResourceSelection() {
  const targetRef = props.model?.targetRef
  if (!targetRef) {
    selectedResourceName.value = undefined
    return
  }
  if (!props.resources.length) {
    selectedResourceName.value = undefined
    resourcePage.value = 1
    return
  }

  const state = readResourceSelectionState()
  const nextSize = resourceSizeOptions.includes(state.resourceSize || 0) ? state.resourceSize || resourceSize.value : resourceSize.value
  resourceSize.value = nextSize

  const savedResourceIndex = props.resources.findIndex((resource) => resource.name === state.selectedResourceName)
  if (savedResourceIndex >= 0) {
    resourcePage.value = Math.floor(savedResourceIndex / nextSize) + 1
    selectedResourceName.value = state.selectedResourceName
    return
  }

  resourcePage.value = clampPage(state.resourcePage || resourcePage.value, nextSize)
  const start = (resourcePage.value - 1) * nextSize
  selectedResourceName.value = props.resources[start]?.name || props.resources[0]?.name
}

function readResourceSelectionState(): ResourceSelectionState {
  return {
    selectedResourceName: queryString('resource'),
    resourcePage: queryNumber('page'),
    resourceSize: queryNumber('size'),
  }
}

function saveAnnotationSettingDisplayState() {
  if (!isAnnotationSettingsModel.value) {
    return
  }
  replaceQuery({
    asSource: annotationSettingSourceTab.value === 'all' ? undefined : annotationSettingSourceTab.value,
    asSourceName: annotationSettingSourceKey.value === 'all' ? undefined : annotationSettingSourceKey.value,
    asPage: String(annotationSettingPage.value || 1),
    asSize: String(annotationSettingSize.value || 10),
  })
}

function queryString(name: string) {
  return new URLSearchParams(window.location.search).get(name) || undefined
}

function queryNumber(name: string) {
  const value = Number(queryString(name))
  return Number.isFinite(value) && value > 0 ? value : undefined
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

watch(
  () => [
    props.model?.targetRef,
    props.resources.map((resource) => resource.name).join(','),
  ] as const,
  restoreResourceSelection,
  { immediate: true },
)

watch(
  () => [resourcePage.value, resourceSize.value, paginatedResources.value.map((resource) => resource.name).join(',')] as const,
  () => {
    if (!paginatedResources.value.length) {
      selectedResourceName.value = undefined
      return
    }
    if (!paginatedResources.value.some((resource) => resource.name === selectedResourceName.value)) {
      selectedResourceName.value = paginatedResources.value[0]?.name
    }
  },
)

watch(
  () => [props.model?.targetRef, selectedResourceName.value, resourcePage.value, resourceSize.value] as const,
  saveResourceSelectionState,
)

watch(annotationSettingSourceTabs, (tabs) => {
  if (!tabs.some((tab) => tab.key === annotationSettingSourceTab.value)) {
    annotationSettingSourceTab.value = 'all'
  }
})

watch(annotationSettingSourceNameTabs, (tabs) => {
  if (!tabs.length) {
    annotationSettingSourceKey.value = 'all'
    return
  }
  if (!tabs.some((tab) => tab.key === annotationSettingSourceKey.value)) {
    annotationSettingSourceKey.value = 'all'
  }
})

watch(
  () => [annotationSettingSourceTab.value, annotationSettingSourceKey.value, annotationSettingSize.value] as const,
  () => {
    annotationSettingPage.value = 1
  },
)

watch(
  () => [annotationSettingTargetGroups.value.length, annotationSettingSize.value] as const,
  () => {
    annotationSettingPage.value = clampAnnotationSettingPage(annotationSettingPage.value, annotationSettingSize.value)
  },
  { immediate: true },
)

watch(
  () => [
    isAnnotationSettingsModel.value,
    annotationSettingSourceTab.value,
    annotationSettingSourceKey.value,
    annotationSettingPage.value,
    annotationSettingSize.value,
  ] as const,
  saveAnnotationSettingDisplayState,
)
</script>

<template>
  <div v-if="!model" class="model-detail">
    <VEmpty title="选择左侧模型" message="选择模型后查看字段定义、扩展来源、冲突和现有值" />
  </div>

  <div v-else class="model-detail">
    <VCard :body-class="['!p-0', 'model-governance-card__body']" class="model-governance-card">
      <div class="section-header section-header--sticky">
        <div class="model-header-main">
          <div class="model-header-title">
            <strong>{{ model.displayName }}</strong>
            <span>{{ model.targetRef }}</span>
          </div>
          <p v-if="model.description" class="model-description">{{ model.description }}</p>
          <div class="model-tags">
            <VTag v-if="model.sourceDisplayName || model.sourceName" class="vtag" rounded>
              {{ modelSourceLabel(model) }}
            </VTag>
          </div>
        </div>
        <div v-if="!isAnnotationSettingsModel" class="section-actions">
          <div class="tag-row">
            <VTag class="vtag" theme="primary" rounded>字段 {{ modelFields.length }}</VTag>
            <VTag class="vtag" theme="secondary" rounded>来源 {{ sourceGroups.length }}</VTag>
            <VTag
              class="vtag"
              :class="{ 'vtag--warning': modelConflicts.length > 0, 'vtag--success': !modelConflicts.length }"
              :theme="modelConflicts.length > 0 ? 'danger' : 'secondary'"
              rounded
            >
              冲突 {{ modelConflicts.length }}
            </VTag>
            <VTag class="vtag" rounded>数据 {{ resources.length }}</VTag>
            <VTag class="vtag" rounded>Key {{ values.length }}</VTag>
          </div>
          <VButton
            size="sm"
            :loading="valueLoading"
            :disabled="!model.supportsValueScan && !supportsValueScan(model.targetRef)"
            @click="emit('refreshValues')"
          >
            刷新
          </VButton>
        </div>
        <div v-else class="section-actions">
          <VButton
            v-permission="['plugin:annotation-manager:metadata:manage']"
            v-tooltip="'使用可视化组件创建符合 Halo 元数据表单规则的 AnnotationSetting。'"
            class="custom-setting-create-button"
            size="sm"
            type="primary"
            @click="openCreateCustomSettingModal"
          >
            新增表单定义
          </VButton>
        </div>
      </div>

      <VLoading v-if="loading || resourceLoading" />
      <div v-else-if="isAnnotationSettingsModel" class="annotation-settings-governance">
        <VEmpty
          v-if="!annotationSettingSourceTypeGroups.length"
          title="暂无 AnnotationSetting"
          message="当前没有加载到主题、插件或系统提供的元数据表单定义"
        />
        <div v-else class="annotation-settings-governance__shell">
          <div class="annotation-settings-governance__toolbar">
            <div class="metadata-source-tabs" role="tablist" aria-label="AnnotationSetting 来源">
              <button
                v-for="tab in annotationSettingSourceTabs"
                :key="tab.key"
                class="metadata-source-tab"
                :class="{ 'metadata-source-tab--active': annotationSettingSourceTab === tab.key }"
                type="button"
                role="tab"
                v-tooltip="sourceTabTooltip(tab.key)"
                :aria-selected="annotationSettingSourceTab === tab.key"
                @click="annotationSettingSourceTab = tab.key"
              >
                <span>{{ tab.label }}</span>
                <small>{{ tab.count }}</small>
              </button>
            </div>
            <VButton
              v-if="visibleAnnotationSettingDuplicateNames.length"
              v-permission="['plugin:annotation-manager:metadata:manage']"
              v-tooltip="'移除当前筛选视图中的旧定义，只处理已判定为重复旧定义的 AnnotationSetting。'"
              size="xs"
              type="danger"
              :loading="deletingSetting"
              @click="emit('previewDelete', visibleAnnotationSettingDuplicateNames)"
            >
              移除当前视图旧定义
            </VButton>
          </div>

          <div
            v-if="annotationSettingSourceNameTabs.length"
            class="metadata-source-section-strip"
            role="tablist"
            aria-label="AnnotationSetting 扩展来源"
          >
            <button
              v-for="tab in annotationSettingSourceNameTabs"
              :key="tab.key"
              class="metadata-source-name-tab"
              :class="{ 'metadata-source-name-tab--active': annotationSettingSourceKey === tab.key }"
              type="button"
              role="tab"
              v-tooltip="sourceNameTooltip(tab.label)"
              :aria-selected="annotationSettingSourceKey === tab.key"
              @click="annotationSettingSourceKey = tab.key"
            >
              <span>{{ tab.label }}</span>
              <small>{{ tab.count }}</small>
            </button>
          </div>

          <VEmpty
            v-if="!annotationSettingTargetGroups.length"
            title="暂无该来源的 AnnotationSetting"
            message="切换其他来源查看插件、主题或系统字段定义"
          />

          <template v-else>
            <div class="annotation-settings-governance__body">
              <section
                v-for="group in paginatedAnnotationSettingGroups"
                :key="group.key"
                class="annotation-setting-group"
                :class="{ 'annotation-setting-group--conflict': group.duplicateSettingNames.length }"
              >
                <div class="annotation-setting-group__header">
                  <div class="annotation-setting-group__title">
                    <strong>{{ group.targetRef }}</strong>
                    <span>{{ group.source }}</span>
                  </div>
                  <div class="tag-row">
                    <VTag class="vtag" rounded>{{ group.settings.length }} 份定义</VTag>
                    <VTag class="vtag" rounded>{{ group.fieldCount }} 字段</VTag>
                    <VTag
                      class="vtag"
                      :class="{ 'vtag--warning': group.duplicateSettingNames.length, 'vtag--success': !group.duplicateSettingNames.length }"
                      :theme="group.duplicateSettingNames.length ? 'danger' : 'secondary'"
                      rounded
                    >
                      旧定义 {{ group.duplicateSettingNames.length }}
                    </VTag>
                    <VTag
                      v-if="group.fieldConflictCount"
                      class="vtag vtag--warning"
                      theme="danger"
                      rounded
                    >
                      字段冲突 {{ group.fieldConflictCount }}
                    </VTag>
                  </div>
                  <VButton
                    v-if="group.duplicateSettingNames.length"
                    v-permission="['plugin:annotation-manager:metadata:manage']"
                    size="xs"
                    type="danger"
                    :loading="deletingSetting"
                    @click="emit('previewDelete', group.duplicateSettingNames)"
                  >
                    移除该模型旧定义
                  </VButton>
                </div>

                <div class="annotation-setting-list">
                  <div
                    v-for="setting in group.settings"
                    :key="setting.name"
                    class="annotation-setting-row"
                    :class="{ 'annotation-setting-row--duplicate': setting.duplicate }"
                  >
                    <div class="annotation-setting-row__main">
                      <div class="annotation-setting-row__title">
                        <strong>{{ setting.name }}</strong>
                        <VStatusDot
                          v-if="setting.duplicate"
                          state="warning"
                          text="重复旧定义"
                        />
                        <VStatusDot v-else state="success" text="保留最新" />
                        <VStatusDot
                          v-if="setting.effective"
                          state="success"
                          text="当前生效"
                        />
                        <VStatusDot v-else state="default" text="未生效" />
                      </div>
                      <span class="annotation-setting-row__meta">
                        {{ setting.creationTimestamp || '无创建时间' }}
                      </span>
                      <div class="annotation-setting-row__fields">
                        <VTag class="vtag" rounded>schema {{ setting.formSchemaCount }}</VTag>
                        <VTag class="vtag" rounded>{{ setting.fields.length }} 字段</VTag>
                        <VTag
                          v-if="setting.fieldConflictCount"
                          class="vtag vtag--warning"
                          theme="danger"
                          rounded
                        >
                          冲突字段 {{ setting.fieldConflictCount }}
                        </VTag>
                        <span>{{ settingFieldKeys(setting.fields) }}</span>
                      </div>

                      <details class="annotation-setting-detail">
                        <summary>扩充详情</summary>
                        <div class="annotation-setting-detail__body">
                          <FormKit
                            v-if="setting.formSchemaCount"
                            type="form"
                            :actions="false"
                            :disabled="true"
                            :preserve="true"
                            form-class="annotation-setting-detail__form"
                          >
                            <FormKitSchema
                              :schema="schemaForGovernanceSetting(setting)"
                              :data="{ formData: {} }"
                            />
                          </FormKit>
                          <VEmpty
                            v-else
                            title="暂无可渲染表单"
                            message="该定义没有 formSchema，或只包含无法直接渲染的动态字段"
                          />
                          <div v-if="setting.fields.length" class="annotation-setting-detail__fields">
                            <span
                              v-for="field in setting.fields"
                              :key="`${setting.name}:${field.annotationKey}`"
                              class="annotation-setting-detail__field"
                              :class="{ 'annotation-setting-detail__field--conflict': field.duplicate }"
                            >
                              <strong>{{ field.label || field.annotationKey }}</strong>
                              <code>{{ field.annotationKey }}</code>
                              <small>{{ field.inputType || '-' }}</small>
                            </span>
                          </div>
                        </div>
                      </details>
                    </div>
                    <div class="annotation-setting-row__actions">
                      <VButton
                        v-if="isCustomSettingGroup(group)"
                        v-permission="['plugin:annotation-manager:metadata:manage']"
                        size="xs"
                        @click="openEditCustomSettingModal(group, setting)"
                      >
                        编辑
                      </VButton>
                      <VButton
                        v-if="isCustomSettingGroup(group)"
                        v-permission="['plugin:annotation-manager:metadata:manage']"
                        size="xs"
                        type="danger"
                        :loading="deletingSetting"
                        @click="emit('deleteCustomSetting', setting.name)"
                      >
                        删除
                      </VButton>
                      <VButton
                        v-else-if="setting.duplicate"
                        v-permission="['plugin:annotation-manager:metadata:manage']"
                        size="xs"
                        type="danger"
                        :loading="deletingSetting"
                        @click="emit('previewDelete', [setting.name])"
                      >
                        移除
                      </VButton>
                    </div>
                  </div>
                </div>
              </section>
            </div>

            <div
              v-if="annotationSettingTargetGroups.length"
              class="annotation-settings-governance__footer"
            >
              <VPagination
                v-model:page="annotationSettingPage"
                v-model:size="annotationSettingSize"
                :total="annotationSettingTargetGroups.length"
                :size-options="annotationSettingSizeOptions"
                :show-total="false"
              />
            </div>
          </template>
        </div>
      </div>
      <div v-else class="governance-layout">
        <div class="resource-metadata-workbench">
          <div class="resource-list-panel">
            <div class="resource-list-panel__header">
              <strong>模型数据</strong>
              <span>{{ resources.length }} 条数据</span>
            </div>
            <div class="resource-list-panel__body">
              <VEmpty v-if="!resources.length" title="暂无模型数据" message="该模型下没有可编辑资源，元数据统计暂时为空" />
              <button
                v-for="resource in paginatedResources"
                v-else
                :key="resource.name"
                class="resource-row"
                :class="{ 'resource-row--active': resource.name === selectedResourceName }"
                type="button"
                @click="selectedResourceName = resource.name"
              >
                <strong>{{ resource.displayName || resource.name }}</strong>
                <span class="resource-row__meta">
                  <small>{{ resourceMetadataCount(resource) }} 个元数据</small>
                  <VStatusDot
                    v-if="resourceConflictCount(resource)"
                    state="warning"
                    :text="`${resourceConflictCount(resource)} 个冲突`"
                  />
                  <VStatusDot v-else state="success" text="无冲突" />
                </span>
              </button>
            </div>
            <div v-if="resources.length > resourceSizeOptions[0]" class="resource-list-panel__footer">
              <VPagination
                v-model:page="resourcePage"
                v-model:size="resourceSize"
                :total="resources.length"
                :size-options="resourceSizeOptions"
                :show-total="false"
              />
            </div>
          </div>

          <div v-if="!resources.length" class="resource-form-panel resource-form-panel--empty">
            <VEmpty title="暂无可编辑数据" message="当前模型没有加载到数据，选择其他模型或刷新后再查看元数据表单" />
          </div>
          <ResourceAnnotationsForm
            v-else
            class="resource-form-panel"
            :resource="selectedResource"
            :setting-forms="settingForms"
            :fields="modelFields"
            :conflicts="modelConflicts"
            :saving="savingResource"
            :deleting-setting="deletingSetting"
            :persistence-key="model.targetRef"
            :focus-setting-name="focusSettingName"
            :focus-annotation-key="focusAnnotationKey"
            @save="(resource, annotations) => emit('saveResource', resource, annotations)"
            @preview-delete="emit('previewDelete', $event)"
            @focus-handled="emit('focusHandled')"
          />
        </div>
      </div>
    </VCard>

    <CustomAnnotationSettingModal
      v-if="customSettingModalVisible"
      :models="models"
      :saving="creatingCustomSetting"
      :editing="Boolean(editingCustomSetting)"
      :initial-name="editingCustomSetting?.name"
      :initial-target-ref="editingCustomTargetRef"
      :initial-form-schema="editingCustomSetting?.formSchema"
      @close="closeCustomSettingModal"
      @submit="submitCustomSetting"
    />
  </div>
</template>
