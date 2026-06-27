<script setup lang="ts">
import {
  VButton,
  VCard,
  VEmpty,
  VLoading,
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
  supportsValueScan,
  type MetadataModelSummary,
} from './metadata'
import ResourceAnnotationsForm from './ResourceAnnotationsForm.vue'

const props = defineProps<{
  model?: MetadataModelSummary
  fields: AnnotationFieldDefinition[]
  conflicts: AnnotationConflictVo[]
  values: AnnotationValueUsageVo[]
  resources: AnnotationResourceVo[]
  settingForms: AnnotationSettingFormVo[]
  loading: boolean
  valueLoading: boolean
  resourceLoading: boolean
  deletingSetting: boolean
  cleaningValues: boolean
  savingResource: boolean
}>()

const emit = defineEmits<{
  refreshValues: []
  previewDelete: [name: string]
  previewCleanup: [annotationKey: string]
  saveResource: [resource: AnnotationResourceVo, annotations: Record<string, string>]
  removeResourceAnnotation: [resource: AnnotationResourceVo, annotationKey: string]
}>()

const modelFields = computed(() => {
  if (!props.model) {
    return []
  }
  return props.fields.filter((field) => field.targetRef === props.model?.targetRef)
})

const modelConflicts = computed(() => {
  if (!props.model) {
    return []
  }
  return props.conflicts.filter((conflict) => conflict.targetRef === props.model?.targetRef)
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

const selectedResourceName = ref<string>()
const selectedResource = computed(() =>
  props.resources.find((resource) => resource.name === selectedResourceName.value),
)

function resourceMetadataCount(resource: AnnotationResourceVo) {
  return Object.keys(resource.annotations || {}).length
}

function resourceConflictCount(resource: AnnotationResourceVo) {
  return Object.keys(resource.annotations || {}).filter((key) => conflictKeys.value.has(key)).length
}

watch(
  () => [props.model?.targetRef, props.resources.map((resource) => resource.name).join(',')] as const,
  () => {
    if (!props.resources.length) {
      selectedResourceName.value = undefined
      return
    }
    if (!selectedResourceName.value || !props.resources.some((resource) => resource.name === selectedResourceName.value)) {
      selectedResourceName.value = props.resources[0]?.name
    }
  },
  { immediate: true },
)
</script>

<template>
  <div v-if="!model" class="model-detail">
    <VEmpty title="选择左侧模型" message="选择模型后查看字段定义、扩展来源、冲突和现有值" />
  </div>

  <div v-else class="model-detail">
    <VCard :body-class="['!p-0']" class="model-hero-card">
      <div class="model-hero">
        <div>
          <h2>{{ model.displayName }}</h2>
          <p>{{ model.targetRef }}</p>
          <p v-if="model.description" class="model-description">{{ model.description }}</p>
          <div class="model-tags">
            <VTag v-if="model.sourceDisplayName || model.sourceName" class="vtag" rounded>
              {{ modelSourceLabel(model) }}
            </VTag>
          </div>
        </div>
        <div class="model-metrics">
          <VTag class="vtag" theme="primary" rounded>字段 {{ model.fieldCount }}</VTag>
          <VTag class="vtag" theme="secondary" rounded>来源 {{ model.sourceCount }}</VTag>
          <VTag
            class="vtag"
            :class="{ 'vtag--warning': model.conflictCount > 0, 'vtag--success': !model.conflictCount }"
            :theme="model.conflictCount > 0 ? 'danger' : 'secondary'"
            rounded
          >
            冲突 {{ model.conflictCount }}
          </VTag>
          <VTag class="vtag" rounded>现有值 {{ model.valueKeyCount }}</VTag>
        </div>
      </div>
    </VCard>

    <VCard :body-class="['!p-0', 'model-governance-card__body']" class="model-governance-card">
      <div class="section-header section-header--sticky">
        <div>
          <strong>元数据</strong>
          <span>选择一条数据后编辑 metadata.annotations</span>
        </div>
        <div class="section-actions">
          <div class="tag-row">
            <VTag class="vtag" theme="primary" rounded>{{ modelFields.length }} 字段</VTag>
            <VTag class="vtag" theme="secondary" rounded>{{ sourceGroups.length }} 来源</VTag>
            <VTag
              class="vtag"
              :class="{ 'vtag--warning': modelConflicts.length > 0, 'vtag--success': !modelConflicts.length }"
              :theme="modelConflicts.length > 0 ? 'danger' : 'secondary'"
              rounded
            >
              {{ modelConflicts.length }} 冲突
            </VTag>
            <VTag class="vtag" rounded>{{ resources.length }} 条数据</VTag>
            <VTag class="vtag" rounded>{{ values.length }} 个 Key</VTag>
          </div>
          <VButton
            size="sm"
            :loading="valueLoading"
            :disabled="!supportsValueScan(model.targetRef)"
            @click="emit('refreshValues')"
          >
            刷新
          </VButton>
        </div>
      </div>

      <VLoading v-if="loading || resourceLoading" />
      <div v-else class="governance-layout">
        <div class="resource-metadata-workbench">
          <div class="resource-list-panel">
            <div class="resource-list-panel__header">
              <strong>模型数据</strong>
              <span>{{ resources.length }} 条数据</span>
            </div>
            <VEmpty v-if="!resources.length" title="暂无模型数据" message="该模型下没有可编辑资源" />
            <button
              v-for="resource in resources"
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

          <ResourceAnnotationsForm
            class="resource-form-panel"
            :resource="selectedResource"
            :setting-forms="settingForms"
            :fields="modelFields"
            :conflicts="modelConflicts"
            :saving="savingResource"
            :deleting-setting="deletingSetting"
            @save="(resource, annotations) => emit('saveResource', resource, annotations)"
            @remove-annotation="(resource, annotationKey) => emit('removeResourceAnnotation', resource, annotationKey)"
            @preview-delete="emit('previewDelete', $event)"
          />
        </div>
      </div>
    </VCard>
  </div>
</template>
