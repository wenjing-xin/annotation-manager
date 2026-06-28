<script setup lang="ts">
import { VButton, VModal, VSpace } from '@halo-dev/components'
import { computed } from 'vue'

import type { DeleteSettingPreviewVo } from '@/api'
import { sourceLabel } from './metadata'

const props = defineProps<{
  previews: DeleteSettingPreviewVo[]
  deleting: boolean
}>()

const emit = defineEmits<{
  close: []
  confirm: [withBackup: boolean]
}>()

const settingNames = computed(() =>
  props.previews.map((preview) => preview.annotationSettingName).filter(Boolean) as string[],
)

const definitions = computed(() =>
  props.previews.flatMap((preview) => preview.definitions || []),
)

const backupCount = computed(() =>
  props.previews.filter((preview) => preview.backup).length,
)

const fieldNames = computed(() =>
  Array.from(new Set(definitions.value.map((definition) => definition.annotationKey).filter(Boolean))),
)

const conflictFieldNames = computed(() =>
  Array.from(new Set(
    definitions.value
      .filter((definition) => definition.duplicate)
      .map((definition) => definition.annotationKey)
      .filter(Boolean),
  )),
)

const normalFieldNames = computed(() =>
  Array.from(new Set(
    definitions.value
      .filter((definition) => !definition.duplicate)
      .map((definition) => definition.annotationKey)
      .filter(Boolean),
  )),
)

const sourceNames = computed(() =>
  Array.from(new Set(definitions.value.map((definition) => sourceLabel(definition)).filter(Boolean))),
)

const targetRefs = computed(() =>
  Array.from(new Set(definitions.value.map((definition) => definition.targetRef).filter(Boolean))),
)

const removesMultipleFields = computed(() => fieldNames.value.length > 1)

const title = computed(() =>
  settingNames.value.length > 1 ? '移除这些重复字段' : '移除这个字段',
)

const description = computed(() => {
  if (settingNames.value.length > 1) {
    return `将移除 ${settingNames.value.length} 个重复的字段定义。`
  }
  return '将移除这个字段定义。'
})
</script>

<template>
  <VModal :title="title" :width="720" @close="emit('close')">
    <div class="remove-preview">
      <p class="remove-preview__lead">{{ description }}</p>

      <div class="remove-preview__summary">
        <div>
          <span>全部字段</span>
          <strong>{{ fieldNames.join('、') || '-' }}</strong>
        </div>
        <div>
          <span>冲突字段</span>
          <strong>{{ conflictFieldNames.join('、') || '-' }}</strong>
        </div>
        <div v-if="normalFieldNames.length">
          <span>同一设置里的正常字段</span>
          <strong>{{ normalFieldNames.join('、') }}</strong>
        </div>
        <div>
          <span>来源</span>
          <strong>{{ sourceNames.join('、') || '-' }}</strong>
        </div>
        <div>
          <span>模型</span>
          <strong>{{ targetRefs.join('、') || '-' }}</strong>
        </div>
      </div>

      <div class="remove-preview__notice">
        <strong>移除后会怎样？</strong>
        <span>这个字段不会再出现在元数据表单里。</span>
        <span>已经保存过的字段值会保留，不会被一起删除。</span>
        <span>同一插件或主题对同一模型重复加载的定义，只保留创建时间最新的一份。</span>
        <span v-if="removesMultipleFields">这个 AnnotationSetting 里包含多个字段，移除时会一起从表单定义中处理。</span>
        <span v-if="normalFieldNames.length">上方列出的正常字段也属于同一份 AnnotationSetting，删除定义时会一起消失。</span>
        <span>如果字段来自插件或主题，重启后可能会再次生成。</span>
      </div>

      <p v-if="backupCount" class="remove-preview__backup">
        不确定时，建议先下载备份再移除。
      </p>
    </div>
    <template #footer>
      <VSpace>
        <VButton @click="emit('close')">取消</VButton>
        <VButton type="danger" :loading="deleting" @click="emit('confirm', false)">直接移除</VButton>
        <VButton type="secondary" :loading="deleting" @click="emit('confirm', true)">下载备份并移除</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
.remove-preview {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.remove-preview__lead {
  margin: 0;
  color: #111827;
  font-size: 14px;
  line-height: 22px;
}

.remove-preview__summary {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  padding: 12px;
}

.remove-preview__summary div,
.remove-preview__notice {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.remove-preview__summary span,
.remove-preview__notice span,
.remove-preview__backup {
  color: #6b7280;
  font-size: 13px;
  line-height: 20px;
}

.remove-preview__summary strong,
.remove-preview__notice strong {
  overflow-wrap: anywhere;
  color: #374151;
  font-size: 13px;
  line-height: 20px;
}

.remove-preview__notice {
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fff7ed;
  padding: 12px;
}

.remove-preview__notice strong {
  color: #9a3412;
}

.remove-preview__backup {
  margin: 0;
}
</style>
