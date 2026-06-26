<script setup lang="ts">
import {
  Dialog,
  IconDeleteBin,
  IconRefreshLine,
  Toast,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VModal,
  VPageHeader,
  VSpace,
  VStatusDot,
} from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'
import IconDownloadLine from '~icons/ri/download-line'
import {
  annotationManagerApi,
  type AnnotationConflictVo,
  type AnnotationFieldDefinition,
  type AnnotationValueUsageVo,
  type BackupVo,
  type CleanupPreviewVo,
  type DeleteSettingPreviewVo,
} from '@/api'

type TabKey = 'definitions' | 'conflicts' | 'values'

const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'definitions', label: '字段定义' },
  { key: 'conflicts', label: '重复冲突' },
  { key: 'values', label: '存量值' },
]

const targetItems = [
  { label: '全部模型' },
  { label: 'Post', value: 'content.halo.run/Post' },
  { label: 'SinglePage', value: 'content.halo.run/SinglePage' },
  { label: 'Category', value: 'content.halo.run/Category' },
  { label: 'Tag', value: 'content.halo.run/Tag' },
]

const sourceTypeItems = [
  { label: '全部来源' },
  { label: '插件', value: 'plugin' },
  { label: '主题', value: 'theme' },
  { label: '系统', value: 'system' },
  { label: '未知', value: 'unknown' },
]

const booleanItems = [
  { label: '全部' },
  { label: '是', value: 'true' },
  { label: '否', value: 'false' },
]

const activeTab = ref<TabKey>('definitions')
const loading = ref(false)
const fields = ref<AnnotationFieldDefinition[]>([])
const conflicts = ref<AnnotationConflictVo[]>([])

const keyword = ref('')
const selectedTargetRef = ref<string>()
const selectedSourceType = ref<string>()
const selectedSourceName = ref('')
const effectiveOnly = ref<string>()
const duplicateOnly = ref<string>()

const valueTargetRef = ref('content.halo.run/Post')
const valueAnnotationKey = ref('')
const valueUsage = ref<AnnotationValueUsageVo>()
const valuePreview = ref<CleanupPreviewVo>()
const valueConfirmation = ref('')
const scanningValues = ref(false)
const cleaningValues = ref(false)

const deletePreview = ref<DeleteSettingPreviewVo>()
const deleteConfirmation = ref('')
const deletingSetting = ref(false)

const backupVisible = ref(false)
const backupPayload = ref<BackupVo>()

const hasDefinitionFilters = computed(() => {
  return Boolean(
    selectedTargetRef.value ||
      selectedSourceType.value ||
      selectedSourceName.value ||
      effectiveOnly.value ||
      duplicateOnly.value,
  )
})

const filteredFields = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase()
  return fields.value.filter((field) => {
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
    if (selectedTargetRef.value && field.targetRef !== selectedTargetRef.value) {
      return false
    }
    if (selectedSourceType.value && field.sourceType !== selectedSourceType.value) {
      return false
    }
    if (
      selectedSourceName.value &&
      !(field.sourceName || '').toLowerCase().includes(selectedSourceName.value.toLowerCase())
    ) {
      return false
    }
    if (effectiveOnly.value && String(field.effective) !== effectiveOnly.value) {
      return false
    }
    if (duplicateOnly.value && String(field.duplicate) !== duplicateOnly.value) {
      return false
    }
    return true
  })
})

const filteredConflicts = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase()
  return conflicts.value.filter((conflict) => {
    if (selectedTargetRef.value && conflict.targetRef !== selectedTargetRef.value) {
      return false
    }
    if (!normalizedKeyword) {
      return true
    }
    return [
      conflict.targetRef,
      conflict.annotationKey,
      ...conflict.definitions.map((definition) => definition.annotationSettingName),
      ...conflict.definitions.map((definition) => definition.sourceName || ''),
    ]
      .join(' ')
      .toLowerCase()
      .includes(normalizedKeyword)
  })
})

const backupJson = computed(() => JSON.stringify(backupPayload.value, null, 2))

async function loadAll() {
  loading.value = true
  try {
    const [fieldData, conflictData] = await Promise.all([
      annotationManagerApi.listFields(),
      annotationManagerApi.listConflicts(),
    ])
    fields.value = fieldData
    conflicts.value = conflictData
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function clearDefinitionFilters() {
  selectedTargetRef.value = undefined
  selectedSourceType.value = undefined
  selectedSourceName.value = ''
  effectiveOnly.value = undefined
  duplicateOnly.value = undefined
}

async function handleScanValues() {
  if (!valueAnnotationKey.value.trim()) {
    Toast.warning('请输入 annotation key')
    return
  }
  scanningValues.value = true
  valuePreview.value = undefined
  try {
    valueUsage.value = await annotationManagerApi.scanValues({
      targetRef: valueTargetRef.value,
      annotationKey: valueAnnotationKey.value.trim(),
      sampleSize: 10,
    })
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    scanningValues.value = false
  }
}

async function handlePreviewCleanupValues() {
  if (!valueUsage.value) {
    await handleScanValues()
  }
  if (!valueAnnotationKey.value.trim()) {
    return
  }
  scanningValues.value = true
  try {
    valuePreview.value = await annotationManagerApi.previewCleanupValues({
      targetRef: valueTargetRef.value,
      annotationKey: valueAnnotationKey.value.trim(),
    })
    showBackup(valuePreview.value.backup)
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    scanningValues.value = false
  }
}

function confirmCleanupValues() {
  if (!valuePreview.value) {
    Toast.warning('请先生成清理预览')
    return
  }
  Dialog.warning({
    title: '清理存量 annotation 值',
    description: `将从 ${valuePreview.value.resourcesWithKey} 个资源中移除 ${valuePreview.value.annotationKey}，不会删除字段定义。`,
    confirmType: 'danger',
    confirmText: '清理',
    cancelText: '取消',
    onConfirm: cleanupValues,
  })
}

async function cleanupValues() {
  if (valueConfirmation.value !== valueAnnotationKey.value.trim()) {
    Toast.warning('确认文本必须与 annotation key 完全一致')
    return
  }
  cleaningValues.value = true
  try {
    const result = await annotationManagerApi.cleanupValues({
      targetRef: valueTargetRef.value,
      annotationKey: valueAnnotationKey.value.trim(),
      confirmedAnnotationKey: valueConfirmation.value,
    })
    Toast.success(`已更新 ${result.updatedResources} 个资源`)
    showBackup(result.backup)
    valueConfirmation.value = ''
    await handleScanValues()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    cleaningValues.value = false
  }
}

async function handlePreviewDeleteSetting(name: string) {
  deletingSetting.value = true
  try {
    deletePreview.value = await annotationManagerApi.previewDeleteSetting(name)
    deleteConfirmation.value = ''
    showBackup(deletePreview.value.backup)
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    deletingSetting.value = false
  }
}

function confirmDeleteSetting() {
  if (!deletePreview.value) {
    return
  }
  Dialog.warning({
    title: '删除重复字段定义',
    description: `将删除 AnnotationSetting: ${deletePreview.value.annotationSettingName}。已存储的 metadata.annotations 值不会被删除。`,
    confirmType: 'danger',
    confirmText: '删除',
    cancelText: '取消',
    onConfirm: deleteSetting,
  })
}

async function deleteSetting() {
  if (!deletePreview.value) {
    return
  }
  if (deleteConfirmation.value !== deletePreview.value.annotationSettingName) {
    Toast.warning('确认文本必须与 AnnotationSetting 名称完全一致')
    return
  }
  deletingSetting.value = true
  try {
    const result = await annotationManagerApi.deleteSetting(
      deletePreview.value.annotationSettingName,
      deleteConfirmation.value,
    )
    Toast.success('字段定义已删除')
    showBackup(result.backup)
    deletePreview.value = undefined
    deleteConfirmation.value = ''
    await loadAll()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    deletingSetting.value = false
  }
}

function showBackup(backup: BackupVo) {
  backupPayload.value = backup
  backupVisible.value = true
}

function downloadBackup() {
  if (!backupPayload.value) {
    return
  }
  const blob = new Blob([backupJson.value], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `annotation-manager-${backupPayload.value.operation}-${Date.now()}.json`
  link.click()
  URL.revokeObjectURL(url)
}

function sourceLabel(field: AnnotationFieldDefinition) {
  if (field.sourceName) {
    return `${field.sourceType}/${field.sourceName}`
  }
  return field.sourceType
}

function errorMessage(error: unknown) {
  const maybeError = error as { response?: { data?: { error?: string; message?: string } }; message?: string }
  return maybeError.response?.data?.error || maybeError.response?.data?.message || maybeError.message || '请求失败'
}

watch([valueTargetRef, valueAnnotationKey], () => {
  valueUsage.value = undefined
  valuePreview.value = undefined
  valueConfirmation.value = ''
})

onMounted(loadAll)
</script>

<template>
  <VPageHeader title="元数据字段管家">
    <template #actions>
      <VButton :loading="loading" @click="loadAll">
        <template #icon>
          <IconRefreshLine />
        </template>
        刷新
      </VButton>
    </template>
  </VPageHeader>

  <div class="metadata-manager">
    <div class="tabs">
      <VButton
        v-for="tab in tabs"
        :key="tab.key"
        size="sm"
        :type="activeTab === tab.key ? 'secondary' : 'default'"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </VButton>
    </div>

    <VCard v-if="activeTab !== 'values'" :body-class="['!p-0']">
      <template #header>
        <div class="toolbar">
          <div class="toolbar-main">
            <SearchInput v-model="keyword" placeholder="搜索字段、来源或 AnnotationSetting" />
          </div>
          <VSpace spacing="lg" class="toolbar-actions">
            <FilterCleanButton v-if="hasDefinitionFilters" @click="clearDefinitionFilters" />
            <FilterDropdown v-model="selectedTargetRef" label="模型" :items="targetItems" />
            <FilterDropdown
              v-if="activeTab === 'definitions'"
              v-model="selectedSourceType"
              label="来源"
              :items="sourceTypeItems"
            />
            <FilterDropdown
              v-if="activeTab === 'definitions'"
              v-model="effectiveOnly"
              label="有效"
              :items="booleanItems"
            />
            <FilterDropdown
              v-if="activeTab === 'definitions'"
              v-model="duplicateOnly"
              label="重复"
              :items="booleanItems"
            />
          </VSpace>
        </div>
        <div v-if="activeTab === 'definitions'" class="source-name-filter">
          <input v-model="selectedSourceName" placeholder="按来源名称过滤" />
        </div>
      </template>

      <VLoading v-if="loading" />

      <VEmpty
        v-else-if="activeTab === 'definitions' && !filteredFields.length"
        title="暂无字段定义"
        message="可以刷新或调整筛选条件"
      />

      <VEntityContainer v-else-if="activeTab === 'definitions'">
        <VEntity v-for="field in filteredFields" :key="`${field.annotationSettingName}:${field.annotationKey}`">
          <template #start>
            <VEntityField :title="field.annotationKey" width="18rem">
              <template #description>
                <span>{{ field.label || field.annotationKey }}</span>
              </template>
            </VEntityField>
            <VEntityField title="模型" :description="field.targetRef" width="14rem" />
            <VEntityField title="来源" :description="sourceLabel(field)" width="14rem" />
            <VEntityField title="输入组件" :description="field.inputType || '-'" width="8rem" />
            <VEntityField title="AnnotationSetting" :description="field.annotationSettingName" width="16rem" />
            <VEntityField>
              <template #description>
                <div class="status-stack">
                  <VStatusDot :state="field.effective ? 'success' : 'default'" :text="field.effective ? '有效' : '未生效'" />
                  <VStatusDot :state="field.duplicate ? 'warning' : 'success'" :text="field.duplicate ? '重复' : '唯一'" />
                </div>
              </template>
            </VEntityField>
          </template>
        </VEntity>
      </VEntityContainer>

      <VEmpty
        v-else-if="activeTab === 'conflicts' && !filteredConflicts.length"
        title="暂无重复冲突"
        message="当前筛选条件下没有重复字段定义"
      />

      <VEntityContainer v-else>
        <VEntity v-for="conflict in filteredConflicts" :key="conflict.conflictKey">
          <template #start>
            <VEntityField :title="conflict.annotationKey" width="18rem">
              <template #description>
                <span>{{ conflict.targetRef }}</span>
              </template>
            </VEntityField>
            <VEntityField title="定义数量" :description="String(conflict.definitions.length)" width="8rem" />
            <VEntityField width="42rem">
              <template #description>
                <div class="definition-list">
                  <div
                    v-for="definition in conflict.definitions"
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
              :loading="deletingSetting"
              @click="handlePreviewDeleteSetting(conflict.definitions[0].annotationSettingName)"
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

    <VCard v-else>
      <template #header>
        <div class="toolbar">
          <div class="value-controls">
            <FilterDropdown v-model="valueTargetRef" label="模型" :items="targetItems.slice(1)" />
            <input v-model="valueAnnotationKey" placeholder="annotation key，例如 copyright" />
          </div>
          <VSpace>
            <VButton :loading="scanningValues" @click="handleScanValues">扫描</VButton>
            <VButton
              v-permission="['plugin:annotation-manager:metadata:manage']"
              :disabled="!valueUsage?.resourcesWithKey"
              :loading="scanningValues"
              type="secondary"
              @click="handlePreviewCleanupValues"
            >
              生成清理预览
            </VButton>
          </VSpace>
        </div>
      </template>

      <VEmpty v-if="!valueUsage" title="选择模型并输入 annotation key 后开始扫描" />
      <div v-else class="usage-grid">
        <div class="metric">
          <span>资源总数</span>
          <strong>{{ valueUsage.totalResources }}</strong>
        </div>
        <div class="metric">
          <span>包含该 Key</span>
          <strong>{{ valueUsage.resourcesWithKey }}</strong>
        </div>
        <div class="metric">
          <span>非空值</span>
          <strong>{{ valueUsage.resourcesWithNonEmptyValue }}</strong>
        </div>
      </div>

      <div v-if="valueUsage" class="sample-block">
        <h3>样例资源</h3>
        <div v-if="!valueUsage.sampleResourceNames.length" class="muted">没有资源包含该 key</div>
        <div v-else class="sample-list">
          <div v-for="name in valueUsage.sampleResourceNames" :key="name" class="sample-item">
            <span>{{ name }}</span>
            <code>{{ valueUsage.sampleValues[name] || '(empty)' }}</code>
          </div>
        </div>
      </div>

      <div v-if="valuePreview" class="danger-zone">
        <div>
          <strong>清理确认</strong>
          <p>输入 annotation key 后才能执行清理：{{ valuePreview.annotationKey }}</p>
        </div>
        <input v-model="valueConfirmation" :placeholder="valuePreview.annotationKey" />
        <VButton
          v-permission="['plugin:annotation-manager:metadata:manage']"
          type="danger"
          :loading="cleaningValues"
          @click="confirmCleanupValues"
        >
          清理存量值
        </VButton>
      </div>
    </VCard>

    <VModal
      v-if="deletePreview"
      title="删除 AnnotationSetting 预览"
      :width="720"
      @close="deletePreview = undefined"
    >
      <div class="modal-stack">
        <p>
          将删除 <strong>{{ deletePreview.annotationSettingName }}</strong>。这只删除字段定义，不会删除已保存的
          <code>metadata.annotations</code> 值。
        </p>
        <div class="definition-list">
          <div
            v-for="definition in deletePreview.definitions"
            :key="`${definition.annotationSettingName}:${definition.annotationKey}`"
            class="definition-line"
          >
            <span>{{ definition.annotationKey }}</span>
            <span>{{ definition.targetRef }}</span>
            <span>{{ sourceLabel(definition) }}</span>
          </div>
        </div>
        <ul>
          <li v-for="warning in deletePreview.warnings" :key="warning">{{ warning }}</li>
        </ul>
        <input v-model="deleteConfirmation" :placeholder="deletePreview.annotationSettingName" />
      </div>
      <template #footer>
        <VSpace>
          <VButton @click="deletePreview = undefined">取消</VButton>
          <VButton type="danger" :loading="deletingSetting" @click="confirmDeleteSetting">删除</VButton>
        </VSpace>
      </template>
    </VModal>

    <VModal v-if="backupVisible" title="备份 JSON" :width="760" @close="backupVisible = false">
      <pre class="backup-json">{{ backupJson }}</pre>
      <template #footer>
        <VSpace>
          <VButton @click="backupVisible = false">关闭</VButton>
          <VButton type="secondary" @click="downloadBackup">
            <template #icon>
              <IconDownloadLine />
            </template>
            下载
          </VButton>
        </VSpace>
      </template>
    </VModal>
  </div>
</template>

<style scoped>
.metadata-manager {
  padding: 16px;
}

.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 12px 16px;
  background: #f9fafb;
}

.toolbar-main {
  flex: 1;
  min-width: 260px;
}

.toolbar-actions,
.value-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.source-name-filter {
  padding: 0 16px 12px;
  background: #f9fafb;
}

input {
  min-height: 32px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 0 10px;
  font-size: 14px;
  background: #fff;
}

.source-name-filter input,
.value-controls input,
.danger-zone input,
.modal-stack input {
  width: 100%;
  max-width: 360px;
}

.status-stack,
.definition-list,
.modal-stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.definition-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #4b5563;
}

.definition-line span {
  padding: 2px 6px;
  border-radius: 4px;
  background: #f3f4f6;
}

.usage-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.metric {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px;
}

.metric span {
  display: block;
  color: #6b7280;
  font-size: 13px;
}

.metric strong {
  display: block;
  margin-top: 6px;
  font-size: 24px;
}

.sample-block,
.danger-zone {
  margin-top: 16px;
}

.sample-block h3 {
  margin: 0 0 8px;
  font-size: 14px;
}

.sample-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sample-item {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f3f4f6;
}

.sample-item code,
.danger-zone code,
.modal-stack code {
  font-size: 12px;
}

.danger-zone {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
}

.danger-zone p {
  margin: 4px 0 0;
  color: #7f1d1d;
  font-size: 13px;
}

.muted {
  color: #6b7280;
  font-size: 13px;
}

.backup-json {
  max-height: 420px;
  overflow: auto;
  border-radius: 6px;
  background: #111827;
  color: #f9fafb;
  padding: 12px;
  font-size: 12px;
  line-height: 1.5;
}
</style>
