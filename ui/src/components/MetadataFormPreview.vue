<script setup lang="ts">
import { IconDeleteBin, VButton, VEmpty, VStatusDot, VTag } from '@halo-dev/components'
import { computed } from 'vue'

import type { AnnotationConflictVo, AnnotationFieldDefinition, AnnotationValueUsageVo } from '@/api'
import { sourceLabel } from './metadata'

const props = defineProps<{
  fields: AnnotationFieldDefinition[]
  conflicts: AnnotationConflictVo[]
  values: AnnotationValueUsageVo[]
  deletingSetting: boolean
  cleaningValues: boolean
  valueLoading: boolean
  supportsValueCleanup: boolean
}>()

const emit = defineEmits<{
  previewDelete: [name: string]
  previewCleanup: [annotationKey: string]
}>()

const conflictDefinitions = computed(() => {
  const names = new Set<string>()
  props.conflicts.forEach((conflict) => {
    ;(conflict.definitions || []).forEach((definition) => {
      if (definition.annotationSettingName && definition.annotationKey) {
        names.add(`${definition.annotationSettingName}\n${definition.annotationKey}`)
      }
    })
  })
  return names
})

function isConflict(field: AnnotationFieldDefinition) {
  return conflictDefinitions.value.has(`${field.annotationSettingName}\n${field.annotationKey}`)
}

const valuesByKey = computed(() => {
  const map = new Map<string, AnnotationValueUsageVo>()
  props.values.forEach((usage) => {
    if (usage.annotationKey) {
      map.set(usage.annotationKey, usage)
    }
  })
  return map
})

const definedKeys = computed(() => new Set(props.fields.map((field) => field.annotationKey).filter(Boolean)))
const customValues = computed(() =>
  props.values.filter((usage) => usage.annotationKey && !definedKeys.value.has(usage.annotationKey)),
)

function valueUsage(field: AnnotationFieldDefinition) {
  return field.annotationKey ? valuesByKey.value.get(field.annotationKey) : undefined
}

function sampleValue(usage?: AnnotationValueUsageVo) {
  const firstName = usage?.sampleResourceNames?.[0]
  if (!firstName) {
    return undefined
  }
  return usage?.sampleValues?.[firstName] || '(empty)'
}
</script>

<template>
  <VEmpty
    v-if="!fields.length && !customValues.length"
    title="暂无元数据"
    message="该模型目前没有元数据表单扩展，也没有扫描到已存储 annotation 值"
  />

  <div v-else class="annotation-form-preview">
    <div
      v-for="field in fields"
      :key="`${field.annotationSettingName}:${field.annotationKey}`"
      class="governance-field"
      :class="{ 'form-field--conflict': isConflict(field) }"
    >
      <div class="governance-field__form">
        <div class="form-field__label">
          <strong>{{ field.label || field.annotationKey }}</strong>
          <VTag class="vtag" theme="secondary" rounded>{{ field.inputType || 'input' }}</VTag>
          <VTag v-if="isConflict(field)" class="vtag vtag--warning" theme="danger" rounded>重复</VTag>
          <VTag v-if="field.effective" class="vtag vtag--success" theme="secondary" rounded>有效</VTag>
          <VTag v-else class="vtag" rounded>未生效</VTag>
        </div>
        <div class="form-field__control">
          <span>{{ field.annotationKey }}</span>
        </div>
        <p v-if="field.help">{{ field.help }}</p>
      </div>

      <div class="governance-field__meta">
        <div class="meta-grid">
          <span>来源</span>
          <strong>{{ sourceLabel(field) }}</strong>
          <span>定义</span>
          <strong>{{ field.annotationSettingName || '-' }}</strong>
          <span v-if="field.validation">校验</span>
          <strong v-if="field.validation">{{ field.validation }}</strong>
        </div>
        <div class="field-status-row">
          <VStatusDot
            :state="isConflict(field) ? 'warning' : 'success'"
            :text="isConflict(field) ? '与其他定义冲突' : '未发现重复定义'"
          />
          <VStatusDot
            :state="valueUsage(field)?.resourcesWithKey ? 'success' : 'default'"
            :text="
              valueUsage(field)?.resourcesWithKey
                ? `${valueUsage(field)?.resourcesWithKey || 0} 个资源有值`
                : '暂无存量值'
            "
          />
        </div>
        <div v-if="valueUsage(field)" class="value-insight">
          <span>非空值 {{ valueUsage(field)?.resourcesWithNonEmptyValue || 0 }}</span>
          <code v-if="sampleValue(valueUsage(field))">{{ sampleValue(valueUsage(field)) }}</code>
        </div>
        <div class="field-actions">
          <VButton
            v-if="isConflict(field)"
            v-permission="['plugin:annotation-manager:metadata:manage']"
            size="sm"
            type="danger"
            :loading="deletingSetting"
            @click="emit('previewDelete', field.annotationSettingName || '')"
          >
            <template #icon>
              <IconDeleteBin />
            </template>
            移除
          </VButton>
          <VButton
            v-if="valueUsage(field)"
            v-permission="['plugin:annotation-manager:metadata:manage']"
            size="sm"
            type="secondary"
            :disabled="!supportsValueCleanup || !valueUsage(field)?.resourcesWithKey"
            :loading="cleaningValues || valueLoading"
            @click="emit('previewCleanup', field.annotationKey || '')"
          >
            清理值
          </VButton>
        </div>
      </div>
    </div>

    <div v-if="customValues.length" class="custom-annotation-section">
      <div class="custom-annotation-section__title">
        <strong>自定义字段</strong>
        <span>对应 Halo AnnotationsForm 中未被 schema 覆盖的 metadata.annotations</span>
      </div>
      <div v-for="usage in customValues" :key="usage.annotationKey" class="custom-value-row">
        <div>
          <strong>{{ usage.annotationKey }}</strong>
          <span>{{ usage.resourcesWithKey || 0 }} 个资源包含该 key，{{ usage.resourcesWithNonEmptyValue || 0 }} 个非空值</span>
          <code v-if="sampleValue(usage)">{{ sampleValue(usage) }}</code>
        </div>
        <VButton
          v-permission="['plugin:annotation-manager:metadata:manage']"
          size="sm"
          type="danger"
          :disabled="!supportsValueCleanup || !usage.resourcesWithKey"
          :loading="cleaningValues || valueLoading"
          @click="emit('previewCleanup', usage.annotationKey || '')"
        >
          清理值
        </VButton>
      </div>
    </div>
  </div>
</template>
