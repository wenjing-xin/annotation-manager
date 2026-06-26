<script setup lang="ts">
import { VButton, VModal, VSpace } from '@halo-dev/components'
import { computed } from 'vue'

import type { DeleteSettingPreviewVo } from '@/api'
import { sourceLabel } from './metadata'

const props = defineProps<{
  preview: DeleteSettingPreviewVo
  confirmation: string
  deleting: boolean
}>()

const emit = defineEmits<{
  'update:confirmation': [value: string]
  close: []
  confirm: []
}>()

const confirmationModel = computed({
  get: () => props.confirmation,
  set: (value: string) => emit('update:confirmation', value),
})
</script>

<template>
  <VModal title="删除 AnnotationSetting 预览" :width="720" @close="emit('close')">
    <div class="modal-stack">
      <p>
        将删除 <strong>{{ preview.annotationSettingName }}</strong>。这只删除字段定义，不会删除已保存的
        <code>metadata.annotations</code> 值。
      </p>
      <div class="definition-list">
        <div
          v-for="definition in preview.definitions || []"
          :key="`${definition.annotationSettingName}:${definition.annotationKey}`"
          class="definition-line"
        >
          <span>{{ definition.annotationKey }}</span>
          <span>{{ definition.targetRef }}</span>
          <span>{{ sourceLabel(definition) }}</span>
        </div>
      </div>
      <ul>
        <li v-for="warning in preview.warnings || []" :key="warning">{{ warning }}</li>
      </ul>
      <input v-model="confirmationModel" :placeholder="preview.annotationSettingName" />
    </div>
    <template #footer>
      <VSpace>
        <VButton @click="emit('close')">取消</VButton>
        <VButton type="danger" :loading="deleting" @click="emit('confirm')">删除</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
