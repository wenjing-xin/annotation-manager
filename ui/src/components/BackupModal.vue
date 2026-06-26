<script setup lang="ts">
import { VButton, VModal, VSpace } from '@halo-dev/components'
import IconDownloadLine from '~icons/ri/download-line'
import { computed } from 'vue'

import type { BackupVo } from '@/api'

const props = defineProps<{
  backup?: BackupVo
}>()

const emit = defineEmits<{
  close: []
}>()

const backupJson = computed(() => JSON.stringify(props.backup, null, 2))

function downloadBackup() {
  if (!props.backup) {
    return
  }
  const blob = new Blob([backupJson.value], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `annotation-manager-${props.backup.operation || 'backup'}-${Date.now()}.json`
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <VModal title="备份 JSON" :width="760" @close="emit('close')">
    <pre class="backup-json">{{ backupJson }}</pre>
    <template #footer>
      <VSpace>
        <VButton @click="emit('close')">关闭</VButton>
        <VButton type="secondary" @click="downloadBackup">
          <template #icon>
            <IconDownloadLine />
          </template>
          下载
        </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
