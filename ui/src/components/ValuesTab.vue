<script setup lang="ts">
import { VButton, VCard, VEmpty, VSpace } from '@halo-dev/components'
import { computed } from 'vue'

import type { AnnotationValueUsageVo, CleanupPreviewVo } from '@/api'
import { valueTargetItems } from './metadata'

const props = defineProps<{
  targetRef: string
  annotationKey: string
  usage?: AnnotationValueUsageVo
  preview?: CleanupPreviewVo
  confirmation: string
  scanning: boolean
  cleaning: boolean
}>()

const emit = defineEmits<{
  'update:targetRef': [value: string]
  'update:annotationKey': [value: string]
  'update:confirmation': [value: string]
  scan: []
  previewCleanup: []
  confirmCleanup: []
}>()

const targetRefModel = computed({
  get: () => props.targetRef,
  set: (value: string) => emit('update:targetRef', value),
})

const annotationKeyModel = computed({
  get: () => props.annotationKey,
  set: (value: string) => emit('update:annotationKey', value),
})

const confirmationModel = computed({
  get: () => props.confirmation,
  set: (value: string) => emit('update:confirmation', value),
})
</script>

<template>
  <VCard>
    <template #header>
      <div class="toolbar">
        <div class="value-controls">
          <FilterDropdown v-model="targetRefModel" label="模型" :items="valueTargetItems" />
          <input v-model="annotationKeyModel" placeholder="annotation key，例如 copyright" />
        </div>
        <VSpace>
          <VButton :loading="scanning" @click="emit('scan')">扫描</VButton>
          <VButton
            v-permission="['plugin:annotation-manager:metadata:manage']"
            :disabled="!usage?.resourcesWithKey"
            :loading="scanning"
            type="secondary"
            @click="emit('previewCleanup')"
          >
            生成清理预览
          </VButton>
        </VSpace>
      </div>
    </template>

    <VEmpty v-if="!usage" title="选择模型并输入 annotation key 后开始扫描" />
    <div v-else class="usage-grid">
      <div class="metric">
        <span>资源总数</span>
        <strong>{{ usage.totalResources || 0 }}</strong>
      </div>
      <div class="metric">
        <span>包含该 Key</span>
        <strong>{{ usage.resourcesWithKey || 0 }}</strong>
      </div>
      <div class="metric">
        <span>非空值</span>
        <strong>{{ usage.resourcesWithNonEmptyValue || 0 }}</strong>
      </div>
    </div>

    <div v-if="usage" class="sample-block">
      <h3>样例资源</h3>
      <div v-if="!usage.sampleResourceNames?.length" class="muted">没有资源包含该 key</div>
      <div v-else class="sample-list">
        <div v-for="name in usage.sampleResourceNames" :key="name" class="sample-item">
          <span>{{ name }}</span>
          <code>{{ usage.sampleValues?.[name] || '(empty)' }}</code>
        </div>
      </div>
    </div>

    <div v-if="preview" class="danger-zone">
      <div>
        <strong>清理确认</strong>
        <p>输入 annotation key 后才能执行清理：{{ preview.annotationKey }}</p>
      </div>
      <input v-model="confirmationModel" :placeholder="preview.annotationKey" />
      <VButton
        v-permission="['plugin:annotation-manager:metadata:manage']"
        type="danger"
        :loading="cleaning"
        @click="emit('confirmCleanup')"
      >
        清理存量值
      </VButton>
    </div>
  </VCard>
</template>
