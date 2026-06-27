<script setup lang="ts">
import { VCard, VEmpty, VStatusDot, VTag } from '@halo-dev/components'
import { computed } from 'vue'

import { sourceTypeLabel, type MetadataModelSummary } from './metadata'

const props = defineProps<{
  models: MetadataModelSummary[]
  selectedTargetRef?: string
  keyword: string
  sourceType?: string
}>()

const emit = defineEmits<{
  select: [targetRef: string]
}>()

const filteredModels = computed(() => {
  const keyword = props.keyword.trim().toLowerCase()
  return props.models.filter((model) => {
    if (props.sourceType && model.sourceType !== props.sourceType) {
      return false
    }
    if (!keyword) {
      return true
    }
    return `${model.displayName} ${model.targetRef} ${model.sourceName || ''} ${model.sourceDisplayName || ''} ${model.description || ''}`
      .toLowerCase()
      .includes(keyword)
  })
})
</script>

<template>
  <VCard :body-class="['!p-0', 'model-sidebar__body']" class="model-sidebar">
    <VEmpty v-if="!filteredModels.length" title="暂无模型" message="没有匹配的元数据模型" />

    <div v-else class="model-list">
      <button
        v-for="model in filteredModels"
        :key="model.targetRef"
        class="model-item"
        :class="{ 'model-item--active': model.targetRef === selectedTargetRef }"
        type="button"
        @click="emit('select', model.targetRef)"
      >
        <span class="model-item__main">
          <strong>{{ model.displayName }}</strong>
          <span class="model-item__description">{{ model.description || '暂无描述' }}</span>
        </span>
        <span class="model-item__meta">
          <VTag class="vtag" theme="secondary" rounded>{{ sourceTypeLabel(model.sourceType) }}</VTag>
          <VTag v-if="model.sourceDisplayName || model.sourceName" class="vtag" rounded>
            {{ model.sourceDisplayName || model.sourceName }}
          </VTag>
          <VTag class="vtag" rounded>{{ model.fieldCount }} 字段</VTag>
          <VStatusDot
            v-if="model.conflictCount"
            state="warning"
            :text="`${model.conflictCount} 冲突`"
          />
          <VStatusDot v-else state="success" text="无冲突" />
        </span>
      </button>
    </div>
  </VCard>
</template>
