<script setup lang="ts">
import {
  IconDeleteBin,
  VButton,
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

import type { AnnotationConflictVo } from '@/api'
import { sourceLabel, targetItems } from './metadata'

const props = defineProps<{
  conflicts: AnnotationConflictVo[]
  loading: boolean
  keyword: string
  selectedTargetRef?: string
  deletingSetting: boolean
}>()

const emit = defineEmits<{
  'update:keyword': [value: string]
  'update:selectedTargetRef': [value?: string]
  clearFilters: []
  previewDelete: [name: string]
}>()

const keywordModel = computed({
  get: () => props.keyword,
  set: (value: string) => emit('update:keyword', value),
})

const selectedTargetRefModel = computed({
  get: () => props.selectedTargetRef,
  set: (value?: string) => emit('update:selectedTargetRef', value),
})

const hasFilters = computed(() => Boolean(props.selectedTargetRef))

const filteredConflicts = computed(() => {
  const normalizedKeyword = props.keyword.trim().toLowerCase()
  return props.conflicts.filter((conflict) => {
    if (props.selectedTargetRef && conflict.targetRef !== props.selectedTargetRef) {
      return false
    }
    if (!normalizedKeyword) {
      return true
    }
    return [
      conflict.targetRef,
      conflict.annotationKey,
      ...(conflict.definitions || []).map((definition) => definition.annotationSettingName),
      ...(conflict.definitions || []).map((definition) => definition.sourceName || ''),
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(normalizedKeyword)
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
        </VSpace>
      </div>
    </template>

    <VLoading v-if="loading" />

    <VEmpty v-else-if="!filteredConflicts.length" title="暂无重复冲突" message="当前筛选条件下没有重复字段定义" />

    <VEntityContainer v-else>
      <VEntity v-for="conflict in filteredConflicts" :key="conflict.conflictKey">
        <template #start>
          <VEntityField :title="conflict.annotationKey || '-'" width="18rem">
            <template #description>
              <span>{{ conflict.targetRef || '-' }}</span>
            </template>
          </VEntityField>
          <VEntityField
            title="定义数量"
            :description="String(conflict.definitions?.length || 0)"
            width="8rem"
          />
          <VEntityField width="42rem">
            <template #description>
              <div class="definition-list">
                <div
                  v-for="definition in conflict.definitions || []"
                  :key="`${definition.annotationSettingName}:${definition.sourceName}`"
                  class="definition-line"
                >
                  <span>{{ definition.annotationSettingName }}</span>
                  <span>{{ sourceLabel(definition) }}</span>
                  <VStatusDot
                    :state="definition.effective ? 'success' : 'default'"
                    :text="definition.effective ? '有效' : '未生效'"
                  />
                </div>
              </div>
            </template>
          </VEntityField>
        </template>
        <template #end>
          <VButton
            v-permission="['plugin:annotation-manager:metadata:manage']"
            size="sm"
            type="danger"
            :disabled="!conflict.definitions?.[0]?.annotationSettingName"
            :loading="deletingSetting"
            @click="emit('previewDelete', conflict.definitions?.[0]?.annotationSettingName || '')"
          >
            <template #icon>
              <IconDeleteBin />
            </template>
            删除定义
          </VButton>
        </template>
      </VEntity>
    </VEntityContainer>
  </VCard>
</template>
