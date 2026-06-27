<script setup lang="ts">
import { Dialog, IconRefreshLine, Toast, VButton, VCard, VPageHeader } from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'

import {
  annotationManagerApi,
  type AnnotationConflictVo,
  type AnnotationFieldDefinition,
  type AnnotationModelVo,
  type AnnotationResourceVo,
  type AnnotationSettingFormVo,
  type AnnotationValueUsageVo,
  type BackupVo,
  type CleanupPreviewVo,
  type DeleteSettingPreviewVo,
} from '@/api'
import BackupModal from '@/components/BackupModal.vue'
import DeleteSettingPreviewModal from '@/components/DeleteSettingPreviewModal.vue'
import ModelDetail from '@/components/ModelDetail.vue'
import ModelSidebar from '@/components/ModelSidebar.vue'
import { buildModelSummaries, sourceTypeLabel, supportsValueScan } from '@/components/metadata'

const loading = ref(false)
const valueLoading = ref(false)
const resourceLoading = ref(false)
const savingResource = ref(false)
const annotationModels = ref<AnnotationModelVo[]>([])
const fields = ref<AnnotationFieldDefinition[]>([])
const conflicts = ref<AnnotationConflictVo[]>([])
const valuesByModel = ref<Record<string, AnnotationValueUsageVo[]>>({})
const settingFormsByModel = ref<Record<string, AnnotationSettingFormVo[]>>({})
const resourcesByModel = ref<Record<string, AnnotationResourceVo[]>>({})

const modelKeyword = ref('')
const modelSourceType = ref<string>()
const selectedTargetRef = ref<string>()

const valueAnnotationKey = ref('')
const valuePreview = ref<CleanupPreviewVo>()
const valueConfirmation = ref('')
const cleaningValues = ref(false)

const deletePreview = ref<DeleteSettingPreviewVo>()
const deleteConfirmation = ref('')
const deletingSetting = ref(false)

const backupVisible = ref(false)
const backupPayload = ref<BackupVo>()

const models = computed(() =>
  buildModelSummaries(annotationModels.value, fields.value, conflicts.value, valuesByModel.value),
)
const selectedModel = computed(() => models.value.find((model) => model.targetRef === selectedTargetRef.value))
const selectedValues = computed(() => {
  if (!selectedTargetRef.value) {
    return []
  }
  return valuesByModel.value[selectedTargetRef.value] || []
})
const selectedSettingForms = computed(() => {
  if (!selectedTargetRef.value) {
    return []
  }
  return settingFormsByModel.value[selectedTargetRef.value] || []
})
const selectedResources = computed(() => {
  if (!selectedTargetRef.value) {
    return []
  }
  return resourcesByModel.value[selectedTargetRef.value] || []
})
const modelSourceTypeItems = computed(() => {
  const counts = models.value.reduce<Record<string, number>>((acc, model) => {
    acc[model.sourceType] = (acc[model.sourceType] || 0) + 1
    return acc
  }, {})
  return [
    { label: `全部 ${models.value.length}` },
    ...Object.entries(counts).map(([sourceType, count]) => ({
      label: `${sourceTypeLabel(sourceType)} ${count}`,
      value: sourceType,
    })),
  ]
})
const hasModelFilters = computed(() => Boolean(modelKeyword.value.trim() || modelSourceType.value))

async function loadAll() {
  loading.value = true
  try {
    const [modelResponse, fieldResponse, conflictResponse] = await Promise.all([
      annotationManagerApi.listAnnotationModels().catch(() => ({ data: [] as AnnotationModelVo[] })),
      annotationManagerApi.listAnnotationFields(),
      annotationManagerApi.listAnnotationConflicts(),
    ])
    annotationModels.value = modelResponse.data
    fields.value = fieldResponse.data
    conflicts.value = conflictResponse.data
    if (!selectedTargetRef.value || !models.value.some((model) => model.targetRef === selectedTargetRef.value)) {
      selectedTargetRef.value = models.value[0]?.targetRef
    }
    await loadSelectedModelDetails()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function loadSelectedModelDetails() {
  const targetRef = selectedTargetRef.value
  if (!targetRef) {
    return
  }
  resourceLoading.value = true
  try {
    const [formsResponse, resourcesResponse] = await Promise.all([
      annotationManagerApi.listAnnotationSettingForms({ targetRef }),
      supportsValueScan(targetRef)
        ? annotationManagerApi.listAnnotationResources({
            annotationResourceListRequest: {
              targetRef,
            },
          })
        : Promise.resolve({ data: [] as AnnotationResourceVo[] }),
    ])
    settingFormsByModel.value = {
      ...settingFormsByModel.value,
      [targetRef]: formsResponse.data,
    }
    resourcesByModel.value = {
      ...resourcesByModel.value,
      [targetRef]: resourcesResponse.data,
    }
    await scanSelectedModelValues()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    resourceLoading.value = false
  }
}

async function scanSelectedModelValues() {
  const targetRef = selectedTargetRef.value
  if (!targetRef || !supportsValueScan(targetRef)) {
    if (targetRef) {
      valuesByModel.value = {
        ...valuesByModel.value,
        [targetRef]: [],
      }
    }
    return
  }
  valueLoading.value = true
  try {
    const response = await annotationManagerApi.scanModelAnnotationValues({
      modelAnnotationValuesScanRequest: {
        targetRef,
        sampleSize: 5,
      },
    })
    valuesByModel.value = {
      ...valuesByModel.value,
      [targetRef]: response.data,
    }
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    valueLoading.value = false
  }
}

async function handleSaveResource(resource: AnnotationResourceVo, annotations: Record<string, string>) {
  const targetRef = selectedTargetRef.value
  if (!targetRef || !resource.name) {
    return
  }
  savingResource.value = true
  try {
    const response = await annotationManagerApi.updateAnnotationResource({
      annotationResourceMetadataUpdateRequest: {
        targetRef,
        name: resource.name,
        annotations,
      },
    })
    Toast.success('元数据已保存')
    showBackup(response.data.backup)
    await loadSelectedModelDetails()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    savingResource.value = false
  }
}

async function handleRemoveResourceAnnotation(resource: AnnotationResourceVo, annotationKey: string) {
  if (!resource.annotations || !annotationKey) {
    return
  }
  const next = { ...resource.annotations }
  delete next[annotationKey]
  await handleSaveResource(resource, next)
}

async function handlePreviewCleanupValues(annotationKey: string) {
  const targetRef = selectedTargetRef.value
  if (!targetRef || !annotationKey) {
    return
  }
  valueAnnotationKey.value = annotationKey
  valueConfirmation.value = ''
  valueLoading.value = true
  try {
    const response = await annotationManagerApi.previewCleanupAnnotationValues({
      cleanupRequest: {
        targetRef,
        annotationKey,
      },
    })
    valuePreview.value = response.data
    showBackup(response.data.backup)
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    valueLoading.value = false
  }
}

function confirmCleanupValues() {
  if (!valuePreview.value) {
    Toast.warning('请先生成清理预览')
    return
  }
  Dialog.warning({
    title: '清理存量 annotation 值',
    description: `将从 ${valuePreview.value.resourcesWithKey || 0} 个资源中移除 ${valuePreview.value.annotationKey}，不会删除字段定义。`,
    confirmType: 'danger',
    confirmText: '清理',
    cancelText: '取消',
    onConfirm: cleanupValues,
  })
}

async function cleanupValues() {
  const targetRef = selectedTargetRef.value
  const annotationKey = valueAnnotationKey.value
  if (!targetRef || !annotationKey) {
    return
  }
  if (valueConfirmation.value !== annotationKey) {
    Toast.warning('确认文本必须与 annotation key 完全一致')
    return
  }
  cleaningValues.value = true
  try {
    const response = await annotationManagerApi.cleanupAnnotationValues({
      cleanupRequest: {
        targetRef,
        annotationKey,
        confirmedAnnotationKey: valueConfirmation.value,
      },
    })
    Toast.success(`已更新 ${response.data.updatedResources || 0} 个资源`)
    showBackup(response.data.backup)
    valuePreview.value = undefined
    valueConfirmation.value = ''
    await loadSelectedModelDetails()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    cleaningValues.value = false
  }
}

async function handlePreviewDeleteSetting(name: string) {
  if (!name) {
    return
  }
  deletingSetting.value = true
  try {
    const response = await annotationManagerApi.previewDeleteAnnotationSetting({ name })
    deletePreview.value = response.data
    deleteConfirmation.value = ''
    showBackup(response.data.backup)
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    deletingSetting.value = false
  }
}

function confirmDeleteSetting() {
  if (!deletePreview.value?.annotationSettingName) {
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
  const name = deletePreview.value?.annotationSettingName
  if (!name) {
    return
  }
  if (deleteConfirmation.value !== name) {
    Toast.warning('确认文本必须与 AnnotationSetting 名称完全一致')
    return
  }
  deletingSetting.value = true
  try {
    const response = await annotationManagerApi.deleteDuplicateAnnotationSetting({
      name,
      deleteSettingRequest: {
        confirmedName: deleteConfirmation.value,
      },
    })
    Toast.success('字段定义已删除')
    showBackup(response.data.backup)
    deletePreview.value = undefined
    deleteConfirmation.value = ''
    await loadAll()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    deletingSetting.value = false
  }
}

function showBackup(backup?: BackupVo) {
  if (!backup) {
    return
  }
  backupPayload.value = backup
  backupVisible.value = true
}

function errorMessage(error: unknown) {
  const maybeError = error as { response?: { data?: { error?: string; message?: string } }; message?: string }
  return maybeError.response?.data?.error || maybeError.response?.data?.message || maybeError.message || '请求失败'
}

function clearModelFilters() {
  modelKeyword.value = ''
  modelSourceType.value = undefined
}

watch(selectedTargetRef, () => {
  valuePreview.value = undefined
  valueAnnotationKey.value = ''
  valueConfirmation.value = ''
  void loadSelectedModelDetails()
})

onMounted(loadAll)
</script>

<template>
  <VPageHeader title="元数据字段管家" />

  <VCard class="metadata-workbench-card" :body-class="['!p-0', 'metadata-workbench-body']">
    <template #header>
      <div class="metadata-toolbar">
        <SearchInput v-model="modelKeyword" placeholder="搜索模型" />
        <div class="metadata-toolbar__actions">
          <FilterCleanButton v-if="hasModelFilters" @click="clearModelFilters" />
          <FilterDropdown v-model="modelSourceType" label="分类" :items="modelSourceTypeItems" />
          <div class="metadata-refresh" @click="loadAll">
            <IconRefreshLine
              v-tooltip="'刷新'"
              :class="{ 'metadata-refresh__icon--loading': loading }"
              class="metadata-refresh__icon"
            />
          </div>
        </div>
      </div>
    </template>

    <div class="metadata-manager">
      <ModelSidebar
        :keyword="modelKeyword"
        :source-type="modelSourceType"
        :models="models"
        :selected-target-ref="selectedTargetRef"
        @select="selectedTargetRef = $event"
      />

      <ModelDetail
        :model="selectedModel"
        :fields="fields"
        :conflicts="conflicts"
        :values="selectedValues"
        :resources="selectedResources"
        :setting-forms="selectedSettingForms"
        :loading="loading"
        :value-loading="valueLoading"
        :resource-loading="resourceLoading"
        :deleting-setting="deletingSetting"
        :cleaning-values="cleaningValues"
        :saving-resource="savingResource"
        @refresh-values="scanSelectedModelValues"
        @preview-delete="handlePreviewDeleteSetting"
        @preview-cleanup="handlePreviewCleanupValues"
        @save-resource="handleSaveResource"
        @remove-resource-annotation="handleRemoveResourceAnnotation"
      />
    </div>
  </VCard>

    <DeleteSettingPreviewModal
      v-if="deletePreview"
      v-model:confirmation="deleteConfirmation"
      :preview="deletePreview"
      :deleting="deletingSetting"
      @close="deletePreview = undefined"
      @confirm="confirmDeleteSetting"
    />

    <BackupModal v-if="backupVisible" :backup="backupPayload" @close="backupVisible = false" />

    <div v-if="valuePreview" class="cleanup-panel">
      <div>
        <strong>清理确认</strong>
        <span>输入 annotation key 后执行清理：{{ valuePreview.annotationKey }}</span>
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
</template>

<style scoped>
.metadata-workbench-card {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 104px);
  height: calc(100dvh - 104px);
  min-height: 560px;
  width: calc(100% - 32px);
  max-width: none;
  margin: 16px;
  overflow: hidden;
  box-sizing: border-box;
}

.metadata-workbench-card :deep(.metadata-workbench-body) {
  flex: 1;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 0 !important;
}

.metadata-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 12px 16px;
  background: #f9fafb;
}

.metadata-toolbar__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.metadata-refresh {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  cursor: pointer;
}

.metadata-refresh:hover {
  background: #e5e7eb;
}

.metadata-refresh__icon {
  width: 16px;
  height: 16px;
  color: #4b5563;
}

.metadata-refresh:hover .metadata-refresh__icon {
  color: #111827;
}

.metadata-refresh__icon--loading {
  animation: metadata-refresh-spin 0.8s linear infinite;
}

@keyframes metadata-refresh-spin {
  to {
    transform: rotate(360deg);
  }
}

.metadata-manager {
  display: grid;
  grid-template-columns: minmax(230px, 290px) minmax(0, 1fr);
  gap: 12px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 12px;
}

.metadata-manager :deep(.model-sidebar) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.metadata-manager :deep(.model-sidebar > div) {
  min-height: 0;
}

.metadata-manager :deep(.model-sidebar__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  padding: 0 !important;
}

.metadata-manager :deep(.model-list) {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-bottom: 8px;
}

.metadata-manager :deep(.model-list),
.metadata-manager :deep(.model-detail),
.metadata-manager :deep(.resource-list-panel),
.metadata-manager :deep(.resource-form-panel),
.metadata-manager :deep(.backup-json) {
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}

.metadata-manager :deep(.model-list::-webkit-scrollbar),
.metadata-manager :deep(.model-detail::-webkit-scrollbar),
.metadata-manager :deep(.resource-list-panel::-webkit-scrollbar),
.metadata-manager :deep(.resource-form-panel::-webkit-scrollbar),
.metadata-manager :deep(.backup-json::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.metadata-manager :deep(.model-list::-webkit-scrollbar-track),
.metadata-manager :deep(.model-detail::-webkit-scrollbar-track),
.metadata-manager :deep(.resource-list-panel::-webkit-scrollbar-track),
.metadata-manager :deep(.resource-form-panel::-webkit-scrollbar-track),
.metadata-manager :deep(.backup-json::-webkit-scrollbar-track) {
  background: transparent;
}

.metadata-manager :deep(.model-list::-webkit-scrollbar-thumb),
.metadata-manager :deep(.model-detail::-webkit-scrollbar-thumb),
.metadata-manager :deep(.resource-list-panel::-webkit-scrollbar-thumb),
.metadata-manager :deep(.resource-form-panel::-webkit-scrollbar-thumb),
.metadata-manager :deep(.backup-json::-webkit-scrollbar-thumb) {
  border-radius: 999px;
  background: #cbd5e1;
}

.metadata-manager :deep(.model-list::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.model-detail::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.resource-list-panel::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.resource-form-panel::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.backup-json::-webkit-scrollbar-thumb:hover) {
  background: #94a3b8;
}

.metadata-manager :deep(.model-item) {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  height: 84px;
  box-sizing: border-box;
  border: 0;
  border-bottom: 1px solid #f3f4f6;
  padding: 8px 10px;
  overflow: hidden;
  background: #fff;
  text-align: left;
  cursor: pointer;
}

.metadata-manager :deep(.model-item:hover),
.metadata-manager :deep(.model-item--active) {
  background: #f9fafb;
}

.metadata-manager :deep(.model-item--active) {
  box-shadow: inset 3px 0 0 #2563eb;
}

.metadata-manager :deep(.model-item__main),
.metadata-manager :deep(.model-item__meta),
.metadata-manager :deep(.section-header),
.metadata-manager :deep(.status-stack),
.metadata-manager :deep(.definition-list),
.metadata-manager :deep(.value-samples) {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.metadata-manager :deep(.model-item__main strong) {
  font-size: 12px;
  line-height: 17px;
}

.metadata-manager :deep(.model-item__main .model-item__description) {
  display: -webkit-box;
  overflow: hidden;
  min-height: 16px;
  color: #64748b;
  font-size: 11px;
  line-height: 16px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
}

.metadata-manager :deep(.model-item__main span),
.metadata-manager :deep(.section-header span),
.metadata-manager :deep(.model-hero p),
.metadata-manager :deep(.source-card span),
.metadata-manager :deep(.model-item__meta) {
  color: #6b7280;
  font-size: 11px;
}

.metadata-manager :deep(.model-item__meta) {
  flex-direction: row;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  max-height: 22px;
  overflow: hidden;
  font-size: 11px;
}

.metadata-manager :deep(.model-detail) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  min-width: 0;
}

.metadata-manager :deep(.model-governance-card) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.metadata-manager :deep(.model-hero-card) {
  flex-shrink: 0;
}

.metadata-manager :deep(.model-governance-card__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.metadata-manager :deep(.model-hero) {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 14px;
}

.metadata-manager :deep(.model-hero h2) {
  margin: 0;
  font-size: 18px;
  line-height: 24px;
}

.metadata-manager :deep(.model-hero p) {
  margin: 4px 0 0;
}

.metadata-manager :deep(.model-description) {
  display: -webkit-box;
  overflow: hidden;
  max-width: 720px;
  color: #4b5563;
  font-size: 12px;
  line-height: 18px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
}

.metadata-manager :deep(.model-tags),
.metadata-manager :deep(.source-fields) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.metadata-manager :deep(.source-fields span) {
  border-radius: 999px;
  background: #f3f4f6;
  padding: 2px 8px;
  color: #4b5563;
  font-size: 12px;
}

.metadata-manager :deep(.model-metrics) {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  max-width: 420px;
}

.metadata-manager :deep(.source-card) {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 10px;
}

.metadata-manager :deep(.vtag) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: fit-content;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #f9fafb;
  padding: 2px 8px;
  color: #4b5563;
  font-size: 12px;
  line-height: 18px;
}

.metadata-manager :deep(.vtag--success) {
  border-color: #bbf7d0;
  background: #f0fdf4;
  color: #166534;
}

.metadata-manager :deep(.vtag--warning) {
  border-color: #fed7aa;
  background: #fff7ed;
  color: #9a3412;
}

.metadata-manager :deep(.tag-row) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.metadata-manager :deep(.section-header) {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  background: #f9fafb;
}

.metadata-manager :deep(.section-header > div:first-child) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.metadata-manager :deep(.section-actions) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.metadata-manager :deep(.insight-grid) {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  gap: 16px;
}

.metadata-manager :deep(.source-grid) {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

.metadata-manager :deep(.source-card) {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.metadata-manager :deep(.annotation-form-preview) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
}

.metadata-manager :deep(.governance-layout) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  padding: 10px;
}

.metadata-manager :deep(.section-header--sticky) {
  position: sticky;
  top: 0;
  z-index: 2;
  border-bottom: 1px solid #eef2f7;
}

.metadata-manager :deep(.source-summary),
.metadata-manager :deep(.value-summary) {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 12px 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}

.metadata-manager :deep(.source-summary__main),
.metadata-manager :deep(.value-summary > div:first-child),
.metadata-manager :deep(.custom-annotation-section__title) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.metadata-manager :deep(.source-chip-list),
.metadata-manager :deep(.value-key-strip) {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
}

.metadata-manager :deep(.source-chip),
.metadata-manager :deep(.value-key-strip__item) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #f9fafb;
  padding: 4px 10px;
  color: #4b5563;
  font-size: 12px;
  line-height: 18px;
}

.metadata-manager :deep(.source-chip strong),
.metadata-manager :deep(.value-key-strip__item) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.source-chip span),
.metadata-manager :deep(.value-key-strip__item small) {
  flex-shrink: 0;
  color: #6b7280;
}

.metadata-manager :deep(.value-key-strip__item--conflict) {
  border-color: #fed7aa;
  background: #fff7ed;
  color: #9a3412;
}

.metadata-manager :deep(.resource-metadata-workbench) {
  display: grid;
  grid-template-columns: minmax(250px, 32%) minmax(0, 1fr);
  gap: 10px;
  flex: 1;
  min-height: 0;
  margin: 0;
  overflow: hidden;
}

.metadata-manager :deep(.resource-list-panel),
.metadata-manager :deep(.resource-form-panel) {
  min-height: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.metadata-manager :deep(.resource-list-panel) {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: auto;
}

.metadata-manager :deep(.resource-form-panel) {
  height: 100%;
  overflow: auto;
}

.metadata-manager :deep(.resource-list-panel__header) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-bottom: 1px solid #f3f4f6;
  padding: 10px 12px;
}

.metadata-manager :deep(.resource-list-panel__header span) {
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}

.metadata-manager :deep(.resource-row) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  border: 0;
  border-bottom: 1px solid #f3f4f6;
  background: #fff;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
}

.metadata-manager :deep(.resource-row:hover),
.metadata-manager :deep(.resource-row--active) {
  background: #f9fafb;
}

.metadata-manager :deep(.resource-row--active) {
  background: #f8fafc;
  box-shadow: inset 3px 0 0 #2563eb;
}

.metadata-manager :deep(.resource-row strong) {
  display: block;
  overflow: hidden;
  color: #111827;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.resource-row small) {
  color: #6b7280;
  font-size: 12px;
  line-height: 17px;
}

.metadata-manager :deep(.resource-row__meta) {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.metadata-manager :deep(.resource-annotation-form) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.metadata-manager :deep(.resource-annotation-form__header) {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #f3f4f6;
  padding: 12px 16px;
  background: #fff;
}

.metadata-manager :deep(.resource-annotation-form__header > div) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.metadata-manager :deep(.resource-annotation-form__header span) {
  color: #6b7280;
  font-size: 12px;
}

.metadata-manager :deep(.resource-save-button),
.metadata-manager :deep(.resource-save-button *) {
  color: #fff !important;
}

.metadata-manager :deep(.metadata-rendered-form) {
  display: block;
  flex: 1;
  min-height: 0;
  min-width: 0;
  padding: 16px 18px 22px;
}

.metadata-manager :deep(.annotation-setting-schema) {
  position: relative;
  border-bottom: 1px solid #f3f4f6;
  padding-bottom: 20px;
  margin-bottom: 22px;
}

.metadata-manager :deep(.annotation-setting-schema--conflict) {
  border-bottom-color: #fed7aa;
}

.metadata-manager :deep(.annotation-setting-governance) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
}

.metadata-manager :deep(.annotation-setting-governance > span) {
  overflow: hidden;
  color: #6b7280;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-setting-schema .formkit-outer) {
  margin-bottom: 14px;
}

.metadata-manager :deep(.annotation-field-source-list) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.metadata-manager :deep(.annotation-field-source-list span) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #f9fafb;
  padding: 3px 8px;
  color: #4b5563;
  font-size: 12px;
}

.metadata-manager :deep(.annotation-field-source-list__text) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.metadata-manager :deep(.metadata-inline-action) {
  border: 0;
  border-left: 1px solid #e5e7eb;
  background: transparent;
  padding: 0 0 0 6px;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
}

.metadata-manager :deep(.metadata-inline-action--danger) {
  color: #dc2626;
}

.metadata-manager :deep(.metadata-inline-action:disabled) {
  color: #9ca3af;
  cursor: not-allowed;
}

.metadata-manager :deep(.annotation-field-source-list small) {
  overflow: hidden;
  color: #6b7280;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-field-source-list code) {
  overflow: hidden;
  max-width: 160px;
  color: #374151;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-field-source-list__item--conflict) {
  border-color: #fed7aa !important;
  background: #fff7ed !important;
  color: #9a3412 !important;
}

.metadata-manager :deep(.custom-annotations-form) {
  margin-top: 4px;
  padding: 0;
}

.metadata-manager :deep(.custom-annotations-form summary) {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  cursor: pointer;
  border-top: 1px solid #f3f4f6;
  padding: 10px 0;
  color: #64748b;
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
  list-style: none;
}

.metadata-manager :deep(.custom-annotations-form summary span:first-child) {
  color: #475569;
}

.metadata-manager :deep(.custom-annotations-form summary span:nth-child(2)) {
  color: #2563eb;
}

.metadata-manager :deep(.custom-annotations-form summary span:last-child) {
  margin-left: auto;
  color: #64748b;
}

.metadata-manager :deep(.custom-annotations-form summary::-webkit-details-marker) {
  display: none;
}

.metadata-manager :deep(.custom-annotations-form__body) {
  padding: 8px 0 0;
}

.metadata-manager :deep(.custom-annotation-row) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  gap: 8px;
}

.metadata-manager :deep(.conflict-summary-inline) {
  margin-top: 12px;
}

.metadata-manager :deep(.governance-field) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 340px);
  gap: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 14px;
  background: #fff;
}

.metadata-manager :deep(.form-field--conflict) {
  border-color: #fed7aa;
  box-shadow: inset 3px 0 0 #f97316;
}

.metadata-manager :deep(.governance-field__form),
.metadata-manager :deep(.governance-field__meta),
.metadata-manager :deep(.form-field__label) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.metadata-manager :deep(.form-field__label) {
  flex-direction: row;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.metadata-manager :deep(.form-field__control) {
  min-height: 34px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #f9fafb;
  padding: 7px 10px;
  color: #6b7280;
  font-size: 13px;
}

.metadata-manager :deep(.governance-field__form p),
.metadata-manager :deep(.governance-field__meta span),
.metadata-manager :deep(.source-summary span),
.metadata-manager :deep(.value-summary span),
.metadata-manager :deep(.custom-value-row span) {
  margin: 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}

.metadata-manager :deep(.governance-field__meta) {
  border-left: 1px solid #f3f4f6;
  padding-left: 12px;
}

.metadata-manager :deep(.meta-grid) {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 6px 8px;
  min-width: 0;
  font-size: 12px;
}

.metadata-manager :deep(.meta-grid strong) {
  overflow-wrap: anywhere;
  color: #374151;
  font-weight: 500;
}

.metadata-manager :deep(.field-status-row),
.metadata-manager :deep(.field-actions) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.metadata-manager :deep(.value-insight) {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-radius: 6px;
  background: #f9fafb;
  padding: 8px;
}

.metadata-manager :deep(.value-insight code),
.metadata-manager :deep(.custom-value-row code) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #374151;
  font-size: 12px;
}

.metadata-manager :deep(.custom-annotation-section) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 2px;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  padding: 12px;
  background: #fafafa;
}

.metadata-manager :deep(.custom-value-row) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-radius: 6px;
  background: #fff;
  padding: 10px;
}

.metadata-manager :deep(.custom-value-row > div) {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.metadata-manager :deep(.conflict-list) {
  display: flex;
  flex-direction: column;
}

.metadata-manager :deep(.conflict-row) {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #f3f4f6;
  padding: 12px 16px;
}

.metadata-manager :deep(.conflict-row__main) {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.metadata-manager :deep(.conflict-row__main > span) {
  color: #6b7280;
  font-size: 12px;
}

.metadata-manager :deep(.definition-line) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #4b5563;
}

.metadata-manager :deep(.definition-line span) {
  padding: 2px 6px;
  border-radius: 4px;
  background: #f3f4f6;
}

.metadata-manager :deep(.value-samples span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.value-samples code) {
  font-size: 12px;
}

.cleanup-panel {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 30;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  max-width: 720px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  padding: 12px;
  background: #fef2f2;
  box-shadow: 0 12px 32px rgb(15 23 42 / 18%);
}

.cleanup-panel div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cleanup-panel span {
  color: #7f1d1d;
  font-size: 13px;
}

.cleanup-panel input,
.metadata-manager :deep(input) {
  min-height: 32px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 0 10px;
  font-size: 14px;
  background: #fff;
}

.cleanup-panel input {
  width: 260px;
}

.metadata-manager :deep(.modal-stack) {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.metadata-manager :deep(.modal-stack input) {
  width: 100%;
  max-width: 360px;
}

.metadata-manager :deep(.backup-json) {
  max-height: 420px;
  overflow: auto;
  border-radius: 6px;
  background: #111827;
  color: #f9fafb;
  padding: 12px;
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 960px) {
  .metadata-workbench-card {
    height: auto;
    min-height: calc(100vh - 132px);
    min-height: calc(100dvh - 132px);
  }

  .metadata-manager {
    grid-template-columns: 1fr;
    overflow: visible;
  }

  .metadata-manager :deep(.model-sidebar) {
    max-height: 280px;
  }

  .metadata-manager :deep(.model-metrics) {
    min-width: 0;
    justify-content: flex-start;
  }

  .metadata-manager :deep(.insight-grid) {
    grid-template-columns: 1fr;
  }

  .metadata-manager :deep(.model-hero) {
    flex-direction: column;
  }

  .metadata-manager :deep(.governance-field) {
    grid-template-columns: 1fr;
  }

  .metadata-manager :deep(.governance-field__meta) {
    border-left: 0;
    border-top: 1px solid #f3f4f6;
    padding-top: 12px;
    padding-left: 0;
  }

  .metadata-manager :deep(.source-summary),
  .metadata-manager :deep(.value-summary),
  .metadata-manager :deep(.custom-value-row) {
    flex-direction: column;
    align-items: stretch;
  }

  .metadata-manager :deep(.source-chip-list),
  .metadata-manager :deep(.value-key-strip) {
    justify-content: flex-start;
  }

  .metadata-manager :deep(.resource-metadata-workbench),
  .metadata-manager :deep(.metadata-rendered-form),
  .metadata-manager :deep(.custom-annotation-row) {
    grid-template-columns: 1fr;
  }

  .metadata-manager :deep(.resource-list-panel) {
    max-height: 320px;
  }

  .metadata-manager :deep(.resource-metadata-workbench) {
    height: auto;
    min-height: 0;
    overflow: visible;
  }

  .metadata-manager :deep(.resource-form-panel) {
    height: auto;
    overflow: visible;
  }
}
</style>
