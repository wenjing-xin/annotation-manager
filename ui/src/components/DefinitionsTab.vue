<script setup lang="ts">
import {
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VSpace,
  VStatusDot,
} from '@halo-dev/components'
import { computed } from 'vue'

import type { AnnotationFieldDefinition } from '@/api'
import { booleanItems, sourceLabel, sourceTypeItems, targetItems } from './metadata'

const props = defineProps<{
  fields: AnnotationFieldDefinition[]
  loading: boolean
  keyword: string
  selectedTargetRef?: string
  selectedSourceType?: string
  selectedSourceName: string
  effectiveOnly?: string
  duplicateOnly?: string
}>()

const emit = defineEmits<{
  'update:keyword': [value: string]
  'update:selectedTargetRef': [value?: string]
  'update:selectedSourceType': [value?: string]
  'update:selectedSourceName': [value: string]
  'update:effectiveOnly': [value?: string]
  'update:duplicateOnly': [value?: string]
  clearFilters: []
}>()

const keywordModel = computed({
  get: () => props.keyword,
  set: (value: string) => emit('update:keyword', value),
})

const selectedTargetRefModel = computed({
  get: () => props.selectedTargetRef,
  set: (value?: string) => emit('update:selectedTargetRef', value),
})

const selectedSourceTypeModel = computed({
  get: () => props.selectedSourceType,
  set: (value?: string) => emit('update:selectedSourceType', value),
})

const selectedSourceNameModel = computed({
  get: () => props.selectedSourceName,
  set: (value: string) => emit('update:selectedSourceName', value),
})

const effectiveOnlyModel = computed({
  get: () => props.effectiveOnly,
  set: (value?: string) => emit('update:effectiveOnly', value),
})

const duplicateOnlyModel = computed({
  get: () => props.duplicateOnly,
  set: (value?: string) => emit('update:duplicateOnly', value),
})

const hasFilters = computed(() => {
  return Boolean(
    props.selectedTargetRef ||
      props.selectedSourceType ||
      props.selectedSourceName ||
      props.effectiveOnly ||
      props.duplicateOnly,
  )
})

const filteredFields = computed(() => {
  const normalizedKeyword = props.keyword.trim().toLowerCase()
  return props.fields.filter((field) => {
    const haystack = [
      field.annotationKey,
      field.label,
      field.targetRef,
      field.sourceType,
      field.sourceName,
      field.annotationSettingName,
      field.inputType,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()

    if (normalizedKeyword && !haystack.includes(normalizedKeyword)) {
      return false
    }
    if (props.selectedTargetRef && field.targetRef !== props.selectedTargetRef) {
      return false
    }
    if (props.selectedSourceType && field.sourceType !== props.selectedSourceType) {
      return false
    }
    if (
      props.selectedSourceName &&
      !(field.sourceName || '').toLowerCase().includes(props.selectedSourceName.toLowerCase())
    ) {
      return false
    }
    if (props.effectiveOnly && String(Boolean(field.effective)) !== props.effectiveOnly) {
      return false
    }
    if (props.duplicateOnly && String(Boolean(field.duplicate)) !== props.duplicateOnly) {
      return false
    }
    return true
  })
})
</script>

<template>
  <VCard :body-class="['!p-0']">
    <template #header>
      <div class="toolbar">
        <div class="toolbar-main">
          <SearchInput v-model="keywordModel" placeholder="搜索字段、来源或 AnnotationSetting" />
        </div>
        <VSpace spacing="lg" class="toolbar-actions">
          <FilterCleanButton v-if="hasFilters" @click="emit('clearFilters')" />
          <FilterDropdown v-model="selectedTargetRefModel" label="模型" :items="targetItems" />
          <FilterDropdown v-model="selectedSourceTypeModel" label="来源" :items="sourceTypeItems" />
          <FilterDropdown v-model="effectiveOnlyModel" label="有效" :items="booleanItems" />
          <FilterDropdown v-model="duplicateOnlyModel" label="重复" :items="booleanItems" />
        </VSpace>
      </div>
      <div class="source-name-filter">
        <input v-model="selectedSourceNameModel" placeholder="按来源名称过滤" />
      </div>
    </template>

    <VLoading v-if="loading" />

    <VEmpty v-else-if="!filteredFields.length" title="暂无字段定义" message="可以刷新或调整筛选条件" />

    <VEntityContainer v-else>
      <VEntity
        v-for="field in filteredFields"
        :key="`${field.annotationSettingName}:${field.annotationKey}`"
      >
        <template #start>
          <VEntityField :title="field.annotationKey || '-'" width="18rem">
            <template #description>
              <span>{{ field.label || field.annotationKey || '-' }}</span>
            </template>
          </VEntityField>
          <VEntityField title="模型" :description="field.targetRef || '-'" width="14rem" />
          <VEntityField title="来源" :description="sourceLabel(field)" width="14rem" />
          <VEntityField title="输入组件" :description="field.inputType || '-'" width="8rem" />
          <VEntityField
            title="AnnotationSetting"
            :description="field.annotationSettingName || '-'"
            width="16rem"
          />
          <VEntityField>
            <template #description>
              <div class="status-stack">
                <VStatusDot
                  :state="field.effective ? 'success' : 'default'"
                  :text="field.effective ? '有效' : '未生效'"
                />
                <VStatusDot
                  :state="field.duplicate ? 'warning' : 'success'"
                  :text="field.duplicate ? '重复' : '唯一'"
                />
              </div>
            </template>
          </VEntityField>
        </template>
      </VEntity>
    </VEntityContainer>
  </VCard>
</template>
