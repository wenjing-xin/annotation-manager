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
  type CustomAnnotationSettingRequest,
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
const creatingCustomSetting = ref(false)
const annotationModels = ref<AnnotationModelVo[]>([])
const fields = ref<AnnotationFieldDefinition[]>([])
const conflicts = ref<AnnotationConflictVo[]>([])
const valuesByModel = ref<Record<string, AnnotationValueUsageVo[]>>({})
const settingFormsByModel = ref<Record<string, AnnotationSettingFormVo[]>>({})
const resourcesByModel = ref<Record<string, AnnotationResourceVo[]>>({})

const modelKeyword = ref(queryString('keyword') || '')
const modelSourceType = ref<string | undefined>(queryString('source'))
const selectedTargetRef = ref<string | undefined>(queryString('model'))
const focusSettingName = ref<string>()
const focusAnnotationKey = ref<string>()

const valueAnnotationKey = ref('')
const valuePreview = ref<CleanupPreviewVo>()
const valueConfirmation = ref('')
const cleaningValues = ref(false)

const deletePreviews = ref<DeleteSettingPreviewVo[]>([])
const deletingSetting = ref(false)
const deletedSettingNames = ref<Set<string>>(new Set())

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
const selectedModelSupportsResources = computed(() =>
  Boolean(!selectedModel.value?.special && (selectedModel.value?.supportsValueScan || supportsValueScan(selectedTargetRef.value))),
)
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
    fields.value = activeFields(fieldResponse.data)
    conflicts.value = activeConflicts(conflictResponse.data, fields.value)
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
  if (selectedModel.value?.special === 'annotation-settings') {
    resourceLoading.value = true
    try {
      const targetRefs = Array.from(
        new Set(fields.value.map((field) => field.targetRef).filter(Boolean) as string[]),
      )
      const responses = await Promise.all(
        targetRefs.map((item) =>
          annotationManagerApi.listAnnotationSettingForms({ targetRef: item })
            .catch((error) => {
              if (isNotFoundError(error)) {
                return { data: [] as AnnotationSettingFormVo[] }
              }
              throw error
            }),
        ),
      )
      settingFormsByModel.value = {
        ...settingFormsByModel.value,
        [targetRef]: filterDeletedForms(responses.flatMap((response) => response.data)),
      }
      resourcesByModel.value = {
        ...resourcesByModel.value,
        [targetRef]: [],
      }
      valuesByModel.value = {
        ...valuesByModel.value,
        [targetRef]: [],
      }
    } catch (error) {
      Toast.error(errorMessage(error))
    } finally {
      resourceLoading.value = false
    }
    return
  }
  const canLoadResources = selectedModelSupportsResources.value
  resourceLoading.value = true
  try {
    const [formsResponse, resourcesResponse] = await Promise.all([
      annotationManagerApi.listAnnotationSettingForms({ targetRef }),
      canLoadResources
        ? annotationManagerApi.listAnnotationResources({
            annotationResourceListRequest: {
              targetRef,
            },
          }).catch((error) => {
            if (isNotFoundError(error)) {
              return { data: [] as AnnotationResourceVo[] }
            }
            throw error
          })
        : Promise.resolve({ data: [] as AnnotationResourceVo[] }),
    ])
    settingFormsByModel.value = {
      ...settingFormsByModel.value,
      [targetRef]: filterDeletedForms(formsResponse.data),
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
  if (!targetRef || !selectedModelSupportsResources.value) {
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
    if (isNotFoundError(error)) {
      valuesByModel.value = {
        ...valuesByModel.value,
        [targetRef]: [],
      }
      return
    }
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
    await annotationManagerApi.updateAnnotationResource({
      annotationResourceMetadataUpdateRequest: {
        targetRef,
        name: resource.name,
        annotations,
      },
    })
    Toast.success('元数据已保存')
    await loadSelectedModelDetails()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    savingResource.value = false
  }
}

async function handleCreateCustomSetting(request: CustomAnnotationSettingRequest) {
  creatingCustomSetting.value = true
  try {
    await annotationManagerApi.createCustomAnnotationSetting({
      customAnnotationSettingRequest: request,
    })
    Toast.success('元数据表单定义已创建')
    await loadAll()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    creatingCustomSetting.value = false
  }
}

async function handleUpdateCustomSetting(name: string, request: CustomAnnotationSettingRequest) {
  if (!name) {
    return
  }
  creatingCustomSetting.value = true
  try {
    await annotationManagerApi.updateCustomAnnotationSetting({
      name,
      customAnnotationSettingRequest: request,
    })
    Toast.success('元数据表单定义已更新')
    await loadAll()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    creatingCustomSetting.value = false
  }
}

function handleDeleteCustomSetting(name: string) {
  if (!name || isDeletedSettingName(name)) {
    return
  }
  Dialog.warning({
    title: '删除自定义表单定义',
    description: `将删除 ${name} 这份自定义 AnnotationSetting。该操作只删除表单定义，不会删除任何模型数据里的 metadata.annotations。`,
    confirmType: 'danger',
    confirmText: '删除',
    cancelText: '取消',
    onConfirm: () => deleteCustomSetting(name),
  })
}

async function deleteCustomSetting(name: string) {
  deletingSetting.value = true
  try {
    const response = await annotationManagerApi.deleteCustomAnnotationSetting({ name })
    Toast.success('自定义表单定义已删除')
    rememberDeletedSettings([name])
    pruneDeletedSettings([name])
    showBackup(response.data.backup)
    await loadAll()
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    deletingSetting.value = false
  }
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

async function handlePreviewDeleteSettings(names: string[]) {
  const normalizedNames = Array.from(new Set(names.filter((name) => name && !isDeletedSettingName(name))))
  if (!normalizedNames.length) {
    return
  }
  deletingSetting.value = true
  try {
    const responses = await Promise.all(
      normalizedNames.map((name) => annotationManagerApi.previewDeleteAnnotationSetting({ name })),
    )
    deletePreviews.value = responses.map((response) => response.data)
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    deletingSetting.value = false
  }
}

async function deleteSettings(withBackup: boolean) {
  const previews = deletePreviews.value
  const names = previews.map((preview) => preview.annotationSettingName).filter(Boolean) as string[]
  if (!names.length) {
    return
  }
  const focusDefinition = previews.flatMap((preview) => preview.definitions || [])[0]
  if (withBackup) {
    downloadDeleteBackups(previews)
  }
  deletingSetting.value = true
  try {
    await Promise.all(
      names.map((name) =>
        annotationManagerApi.deleteDuplicateAnnotationSetting({
          name,
          deleteSettingRequest: {
            confirmedName: name,
          },
        }),
      ),
    )
    Toast.success(names.length > 1 ? `已移除 ${names.length} 个字段定义` : '字段定义已移除')
    deletePreviews.value = []
    focusSettingName.value = names[0]
    focusAnnotationKey.value = focusDefinition?.annotationKey
    rememberDeletedSettings(names)
    pruneDeletedSettings(names)
  } catch (error) {
    Toast.error(errorMessage(error))
  } finally {
    deletingSetting.value = false
  }
}

function pruneDeletedSettings(names: string[]) {
  const deletedNames = new Set(names)
  fields.value = normalizeFieldDuplicates(
    fields.value.filter((field) => !deletedNames.has(field.annotationSettingName || '')),
  )
  conflicts.value = rebuildConflicts(fields.value)
  settingFormsByModel.value = Object.fromEntries(
    Object.entries(settingFormsByModel.value).map(([targetRef, forms]) => [
      targetRef,
      forms.filter((form) => !deletedNames.has(form.name || '')),
    ]),
  )
}

function rememberDeletedSettings(names: string[]) {
  deletedSettingNames.value = new Set([...deletedSettingNames.value, ...names.filter(Boolean)])
}

function isDeletedSettingName(name?: string) {
  return Boolean(name && deletedSettingNames.value.has(name))
}

function filterDeletedFields(nextFields: AnnotationFieldDefinition[]) {
  return nextFields.filter((field) => !isDeletedSettingName(field.annotationSettingName))
}

function filterDeletedForms(forms: AnnotationSettingFormVo[]) {
  return forms.filter((form) => !isDeletedSettingName(form.name))
}

function activeFields(nextFields: AnnotationFieldDefinition[]) {
  const filtered = filterDeletedFields(nextFields)
  return deletedSettingNames.value.size ? normalizeFieldDuplicates(filtered) : filtered
}

function activeConflicts(
  nextConflicts: AnnotationConflictVo[],
  nextFields: AnnotationFieldDefinition[],
) {
  if (!deletedSettingNames.value.size) {
    return nextConflicts
  }
  return rebuildConflicts(nextFields, nextConflicts)
}

function normalizeFieldDuplicates(nextFields: AnnotationFieldDefinition[]) {
  const fieldsByConflictKey = new Map<string, AnnotationFieldDefinition[]>()
  const sourceTargetSettingNames = new Map<string, Set<string>>()
  nextFields.forEach((field) => {
    const conflictKey = fieldConflictKey(field)
    fieldsByConflictKey.set(conflictKey, [...(fieldsByConflictKey.get(conflictKey) || []), field])
    const sourceTargetKey = fieldSourceTargetKey(field)
    const settingNames = sourceTargetSettingNames.get(sourceTargetKey) || new Set<string>()
    if (field.annotationSettingName) {
      settingNames.add(field.annotationSettingName)
    }
    sourceTargetSettingNames.set(sourceTargetKey, settingNames)
  })

  const crossSourceConflictKeys = new Set<string>()
  fieldsByConflictKey.forEach((items, key) => {
    const sourceIdentities = new Set(items.map(fieldSourceIdentity))
    if (items.length > 1 && sourceIdentities.size > 1) {
      crossSourceConflictKeys.add(key)
    }
  })

  return nextFields.map((field) => ({
    ...field,
    duplicate:
      crossSourceConflictKeys.has(fieldConflictKey(field))
      || (sourceTargetSettingNames.get(fieldSourceTargetKey(field))?.size || 0) > 1,
  }))
}

function rebuildConflicts(
  nextFields: AnnotationFieldDefinition[],
  fallbackConflicts: AnnotationConflictVo[] = [],
) {
  const grouped = new Map<string, AnnotationFieldDefinition[]>()
  nextFields
    .filter((field) => field.duplicate)
    .forEach((field) => {
      const key = fieldConflictKey(field)
      grouped.set(key, [...(grouped.get(key) || []), field])
    })
  if (!grouped.size) {
    return []
  }
  const fallbackByKey = new Map(
    fallbackConflicts.map((conflict) => [conflict.conflictKey || `${conflict.targetRef || ''}\n${conflict.annotationKey || ''}`, conflict]),
  )
  return Array.from(grouped.entries()).map(([key, definitions]) => {
    const fallback = fallbackByKey.get(key)
    const first = definitions[0]
    return {
      conflictKey: key,
      targetRef: fallback?.targetRef || first.targetRef,
      annotationKey: fallback?.annotationKey || first.annotationKey,
      definitions,
    }
  })
}

function fieldConflictKey(field: AnnotationFieldDefinition) {
  return `${field.targetRef || ''}\n${field.annotationKey || ''}`
}

function fieldSourceTargetKey(field: AnnotationFieldDefinition) {
  return `${field.targetRef || ''}\n${field.sourceType || 'unknown'}\n${field.sourceName || ''}`
}

function fieldSourceIdentity(field: AnnotationFieldDefinition) {
  return `${field.sourceType || 'unknown'}\n${field.sourceName || ''}`
}

function downloadDeleteBackups(previews: DeleteSettingPreviewVo[]) {
  const backups = previews
    .filter((preview) => preview.backup)
    .map((preview) => ({
      annotationSettingName: preview.annotationSettingName,
      backup: preview.backup,
    }))
  if (!backups.length) {
    Toast.warning('没有可下载的备份内容')
    return
  }
  const payload = {
    createdAt: new Date().toISOString(),
    operation: 'annotation-setting-delete',
    backups,
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `annotation-manager-delete-backup-${Date.now()}.json`
  link.click()
  URL.revokeObjectURL(url)
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

function isNotFoundError(error: unknown) {
  return (error as { response?: { status?: number } }).response?.status === 404
}

function clearModelFilters() {
  modelKeyword.value = ''
  modelSourceType.value = undefined
}

function handleFocusHandled() {
  focusSettingName.value = undefined
  focusAnnotationKey.value = undefined
}

function queryString(name: string) {
  return new URLSearchParams(window.location.search).get(name) || undefined
}

function replaceQuery(updates: Record<string, string | undefined>) {
  const url = new URL(window.location.href)
  Object.entries(updates).forEach(([key, value]) => {
    if (value === undefined || value === '') {
      url.searchParams.delete(key)
      return
    }
    url.searchParams.set(key, value)
  })
  window.history.replaceState(window.history.state, '', url)
}

watch([modelKeyword, modelSourceType, selectedTargetRef], () => {
  replaceQuery({
    keyword: modelKeyword.value.trim() || undefined,
    source: modelSourceType.value,
    model: selectedTargetRef.value,
  })
})

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
        :models="models"
        :loading="loading"
        :value-loading="valueLoading"
        :resource-loading="resourceLoading"
        :deleting-setting="deletingSetting"
        :cleaning-values="cleaningValues"
        :saving-resource="savingResource"
        :creating-custom-setting="creatingCustomSetting"
        :focus-setting-name="focusSettingName"
        :focus-annotation-key="focusAnnotationKey"
        @refresh-values="scanSelectedModelValues"
        @preview-delete="handlePreviewDeleteSettings"
        @preview-cleanup="handlePreviewCleanupValues"
        @save-resource="handleSaveResource"
        @create-custom-setting="handleCreateCustomSetting"
        @update-custom-setting="handleUpdateCustomSetting"
        @delete-custom-setting="handleDeleteCustomSetting"
        @focus-handled="handleFocusHandled"
      />
    </div>
  </VCard>

    <DeleteSettingPreviewModal
      v-if="deletePreviews.length"
      :previews="deletePreviews"
      :deleting="deletingSetting"
      @close="deletePreviews = []"
      @confirm="deleteSettings"
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
.metadata-manager :deep(.resource-list-panel__body),
.metadata-manager :deep(.resource-form-panel),
.metadata-manager :deep(.annotation-settings-governance),
.metadata-manager :deep(.metadata-source-tabs),
.metadata-manager :deep(.metadata-source-section-strip),
.metadata-manager :deep(.backup-json) {
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}

.metadata-manager :deep(.model-list::-webkit-scrollbar),
.metadata-manager :deep(.model-detail::-webkit-scrollbar),
.metadata-manager :deep(.resource-list-panel__body::-webkit-scrollbar),
.metadata-manager :deep(.resource-form-panel::-webkit-scrollbar),
.metadata-manager :deep(.annotation-settings-governance::-webkit-scrollbar),
.metadata-manager :deep(.metadata-source-tabs::-webkit-scrollbar),
.metadata-manager :deep(.metadata-source-section-strip::-webkit-scrollbar),
.metadata-manager :deep(.backup-json::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.metadata-manager :deep(.model-list::-webkit-scrollbar-track),
.metadata-manager :deep(.model-detail::-webkit-scrollbar-track),
.metadata-manager :deep(.resource-list-panel__body::-webkit-scrollbar-track),
.metadata-manager :deep(.resource-form-panel::-webkit-scrollbar-track),
.metadata-manager :deep(.annotation-settings-governance::-webkit-scrollbar-track),
.metadata-manager :deep(.metadata-source-tabs::-webkit-scrollbar-track),
.metadata-manager :deep(.metadata-source-section-strip::-webkit-scrollbar-track),
.metadata-manager :deep(.backup-json::-webkit-scrollbar-track) {
  background: transparent;
}

.metadata-manager :deep(.model-list::-webkit-scrollbar-thumb),
.metadata-manager :deep(.model-detail::-webkit-scrollbar-thumb),
.metadata-manager :deep(.resource-list-panel__body::-webkit-scrollbar-thumb),
.metadata-manager :deep(.resource-form-panel::-webkit-scrollbar-thumb),
.metadata-manager :deep(.annotation-settings-governance::-webkit-scrollbar-thumb),
.metadata-manager :deep(.metadata-source-tabs::-webkit-scrollbar-thumb),
.metadata-manager :deep(.metadata-source-section-strip::-webkit-scrollbar-thumb),
.metadata-manager :deep(.backup-json::-webkit-scrollbar-thumb) {
  border-radius: 999px;
  background: #cbd5e1;
}

.metadata-manager :deep(.model-list::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.model-detail::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.resource-list-panel__body::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.resource-form-panel::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.annotation-settings-governance::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.metadata-source-tabs::-webkit-scrollbar-thumb:hover),
.metadata-manager :deep(.metadata-source-section-strip::-webkit-scrollbar-thumb:hover),
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

.metadata-manager :deep(.model-governance-card__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.metadata-manager :deep(.model-header-main) {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.metadata-manager :deep(.model-header-title) {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.metadata-manager :deep(.model-header-title strong) {
  color: #111827;
  font-size: 16px;
  line-height: 22px;
}

.metadata-manager :deep(.model-header-title span) {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 14px 16px;
  background: #fff;
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
  max-width: 520px;
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

.metadata-manager :deep(.annotation-settings-governance) {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px;
}

.metadata-manager :deep(.annotation-settings-governance__shell) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 100%;
  min-width: 0;
}

.metadata-manager :deep(.annotation-settings-governance__toolbar) {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.metadata-manager :deep(.annotation-settings-governance__toolbar > button) {
  flex: 0 0 auto;
}

.metadata-manager :deep(.annotation-settings-governance__body) {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.metadata-manager :deep(.annotation-settings-governance__footer) {
  position: sticky;
  bottom: 0;
  z-index: 1;
  flex-shrink: 0;
  margin: 0 -10px -10px;
  margin-top: auto;
  border-top: 1px solid #f3f4f6;
  padding: 8px 10px 10px;
  background: #fff;
  box-shadow: 0 -10px 18px rgb(15 23 42 / 6%);
}

.metadata-manager :deep(.annotation-settings-governance__footer .pagination) {
  justify-content: center;
}

.metadata-manager :deep(.annotation-setting-source-type),
.metadata-manager :deep(.annotation-setting-source-group),
.metadata-manager :deep(.annotation-setting-group) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 12px;
}

.metadata-manager :deep(.annotation-setting-source-type) {
  background: #f8fafc;
}

.metadata-manager :deep(.annotation-setting-source-group) {
  background: #fff;
}

.metadata-manager :deep(.annotation-setting-source-type--conflict),
.metadata-manager :deep(.annotation-setting-source-group--conflict),
.metadata-manager :deep(.annotation-setting-group--conflict) {
  border-color: #fed7aa;
  background: #fffaf5;
}

.metadata-manager :deep(.annotation-setting-source-type__header),
.metadata-manager :deep(.annotation-setting-source-group__header),
.metadata-manager :deep(.annotation-setting-group__header) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: flex-start;
  gap: 10px;
}

.metadata-manager :deep(.annotation-setting-source-type__header strong) {
  color: #111827;
  font-size: 15px;
  line-height: 22px;
}

.metadata-manager :deep(.annotation-setting-group__title),
.metadata-manager :deep(.annotation-setting-row__main) {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.metadata-manager :deep(.annotation-setting-group__title strong) {
  overflow: hidden;
  color: #111827;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-setting-group__title span),
.metadata-manager :deep(.annotation-setting-row__meta) {
  overflow: hidden;
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-setting-list) {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #f3f4f6;
  border-radius: 8px;
  background: #fff;
}

.metadata-manager :deep(.annotation-setting-row) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid #f3f4f6;
  padding: 10px 12px;
}

.metadata-manager :deep(.annotation-setting-row:last-child) {
  border-bottom: 0;
}

.metadata-manager :deep(.annotation-setting-row--duplicate) {
  background: #fff7ed;
}

.metadata-manager :deep(.annotation-setting-row__title),
.metadata-manager :deep(.annotation-setting-row__fields) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.metadata-manager :deep(.annotation-setting-row__title strong) {
  overflow: hidden;
  max-width: 100%;
  color: #111827;
  font-size: 13px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-setting-row__fields > span) {
  overflow: hidden;
  color: #475569;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-setting-detail) {
  margin-top: 8px;
  border-top: 1px solid #f3f4f6;
  padding-top: 8px;
}

.metadata-manager :deep(.annotation-setting-detail summary) {
  display: inline-flex;
  cursor: pointer;
  color: #2563eb;
  font-size: 12px;
  line-height: 18px;
  list-style: none;
}

.metadata-manager :deep(.annotation-setting-detail summary::-webkit-details-marker) {
  display: none;
}

.metadata-manager :deep(.annotation-setting-detail__body) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 10px;
  border: 1px solid #eef2f7;
  border-radius: 8px;
  background: #fff;
  padding: 12px;
}

.metadata-manager :deep(.annotation-setting-detail__form) {
  opacity: 0.9;
}

.metadata-manager :deep(.annotation-setting-detail__form .formkit-outer) {
  margin-bottom: 12px;
}

.metadata-manager :deep(.annotation-setting-detail__fields) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  border-top: 1px solid #f3f4f6;
  padding-top: 10px;
}

.metadata-manager :deep(.annotation-setting-detail__field) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #f9fafb;
  padding: 3px 8px;
  color: #475569;
  font-size: 12px;
  line-height: 18px;
}

.metadata-manager :deep(.annotation-setting-detail__field--conflict) {
  border-color: #fdba74;
  background: #fff7ed;
  color: #9a3412;
}

.metadata-manager :deep(.annotation-setting-detail__field strong),
.metadata-manager :deep(.annotation-setting-detail__field code),
.metadata-manager :deep(.annotation-setting-detail__field small) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-setting-detail__field code),
.metadata-manager :deep(.annotation-setting-detail__field small) {
  color: inherit;
  font-size: 11px;
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
  overflow: hidden;
}

.metadata-manager :deep(.resource-form-panel) {
  height: 100%;
  overflow: auto;
}

.metadata-manager :deep(.resource-form-panel--empty) {
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  padding: 16px;
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

.metadata-manager :deep(.resource-list-panel__body) {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.metadata-manager :deep(.resource-list-panel__footer) {
  flex-shrink: 0;
  border-top: 1px solid #f3f4f6;
  padding: 8px 10px;
  background: #fff;
}

.metadata-manager :deep(.resource-list-panel__footer .pagination) {
  justify-content: center;
  gap: 8px;
}

.metadata-manager :deep(.resource-list-panel__footer .pagination__controller) {
  flex-wrap: wrap;
  justify-content: center;
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
  justify-content: flex-start;
  border-bottom: 1px solid #f3f4f6;
  padding: 8px 16px;
  background: #fff;
}

.metadata-manager :deep(.resource-annotation-form__actions) {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.metadata-manager :deep(.resource-save-button),
.metadata-manager :deep(.resource-save-button *),
.metadata-manager :deep(.custom-setting-create-button),
.metadata-manager :deep(.custom-setting-create-button *) {
  flex: 0 0 auto;
  color: #fff !important;
}

.metadata-manager :deep(.metadata-rendered-form) {
  display: block;
  flex: 1;
  min-height: 0;
  min-width: 0;
  padding: 16px 18px 40px;
}

.metadata-manager :deep(.metadata-source-tabs) {
  display: flex;
  flex: 1;
  flex-wrap: nowrap;
  gap: 6px;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 6px;
}

.metadata-manager :deep(.metadata-source-tab) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  min-height: 30px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
  padding: 5px 10px;
  color: #475569;
  font-size: 13px;
  line-height: 18px;
  cursor: pointer;
}

.metadata-manager :deep(.metadata-source-tab:hover),
.metadata-manager :deep(.metadata-source-tab--active) {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.metadata-manager :deep(.metadata-source-tab small) {
  min-width: 18px;
  border-radius: 999px;
  background: #f1f5f9;
  padding: 1px 6px;
  color: #64748b;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}

.metadata-manager :deep(.metadata-source-tab--active small) {
  background: #dbeafe;
  color: #1d4ed8;
}

.metadata-manager :deep(.annotation-setting-schema) {
  position: relative;
  border-bottom: 1px solid #f3f4f6;
  padding-bottom: 18px;
  margin-bottom: 18px;
}

.metadata-manager :deep(.annotation-setting-schema--conflict) {
  border-bottom-color: #fed7aa;
  box-shadow: inset 3px 0 0 #f97316;
  padding-left: 12px;
}

.metadata-manager :deep(.annotation-source-section) {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 18px;
}

.metadata-manager :deep(.annotation-source-section__header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #eef2f7;
  padding-bottom: 8px;
}

.metadata-manager :deep(.annotation-section-notice) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fff7ed;
  padding: 8px 10px;
}

.metadata-manager :deep(.annotation-section-notice span) {
  color: #9a3412;
  font-size: 12px;
  line-height: 18px;
}

.metadata-manager :deep(.metadata-source-section-strip) {
  display: flex;
  gap: 6px;
  margin-bottom: 14px;
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 6px;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}

.metadata-manager :deep(.metadata-source-name-tab) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  max-width: 220px;
  min-height: 28px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
  padding: 4px 9px;
  color: #475569;
  font-size: 12px;
  line-height: 18px;
  cursor: pointer;
}

.metadata-manager :deep(.metadata-source-name-tab:hover),
.metadata-manager :deep(.metadata-source-name-tab--active) {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.metadata-manager :deep(.metadata-source-name-tab span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.metadata-source-name-tab small) {
  flex-shrink: 0;
  min-width: 18px;
  border-radius: 999px;
  background: #f1f5f9;
  padding: 1px 6px;
  color: #64748b;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}

.metadata-manager :deep(.metadata-source-name-tab--active small) {
  background: #dbeafe;
  color: #1d4ed8;
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

.metadata-manager :deep(.annotation-field-overview) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.metadata-manager :deep(.annotation-field-overview__item) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #f9fafb;
  padding: 3px 8px;
  color: #475569;
  font-size: 12px;
  line-height: 18px;
}

.metadata-manager :deep(.annotation-field-overview__item--conflict) {
  border-color: #fdba74;
  background: #fff7ed;
  color: #9a3412;
}

.metadata-manager :deep(.annotation-field-overview__item span),
.metadata-manager :deep(.annotation-field-overview__item code) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-manager :deep(.annotation-field-overview__item code) {
  color: inherit;
  font-size: 11px;
}

.metadata-manager :deep(.annotation-conflict-list) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.metadata-manager :deep(.annotation-conflict-row) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  border: 1px solid #fdba74;
  border-radius: 8px;
  background: #fff7ed;
  padding: 8px 10px;
}

.metadata-manager :deep(.annotation-conflict-row__main) {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.metadata-manager :deep(.annotation-conflict-row__main strong),
.metadata-manager :deep(.annotation-conflict-row__main span) {
  overflow-wrap: anywhere;
}

.metadata-manager :deep(.annotation-conflict-row__main strong) {
  color: #7c2d12;
  font-size: 13px;
  line-height: 18px;
}

.metadata-manager :deep(.annotation-conflict-row__main span) {
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}

.metadata-manager :deep(.metadata-inline-action) {
  border: 0;
  background: transparent;
  padding: 0;
  color: #2563eb;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}

.metadata-manager :deep(.metadata-inline-action--danger) {
  color: #dc2626;
}

.metadata-manager :deep(.metadata-inline-action:disabled) {
  color: #9ca3af;
  cursor: not-allowed;
}

.metadata-manager :deep(.custom-annotations-form) {
  margin-top: 4px;
  padding: 0;
}

.metadata-manager :deep(.custom-annotations-form summary) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  border-top: 1px solid #f3f4f6;
  padding: 10px 0;
  color: #475569;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  list-style: none;
}

.metadata-manager :deep(.custom-annotations-form summary::-webkit-details-marker) {
  display: none;
}

.metadata-manager :deep(.custom-annotations-form__body) {
  padding: 14px 0 0;
}

.metadata-manager :deep(.custom-annotations-form .formkit-outer) {
  margin-bottom: 12px;
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
.metadata-manager :deep(.value-summary span) {
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

.metadata-manager :deep(.value-insight code) {
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

.cleanup-panel input {
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

:deep(.custom-setting-builder) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: min(72vh, 680px);
  min-height: 500px;
  overflow: hidden;
}

:deep(.custom-setting-builder__header),
:deep(.custom-field-card__grid) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  align-items: start;
}

:deep(.custom-setting-builder__header) {
  flex-shrink: 0;
}

:deep(.custom-setting-builder__header-cell) {
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  min-width: 0;
}

:deep(.custom-setting-builder__header-field) {
  margin-bottom: 0 !important;
}

:deep(.custom-setting-builder__header-field .formkit-wrapper) {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

:deep(.custom-setting-builder__hint) {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 18px;
}

:deep(.custom-setting-builder__grid) {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(280px, 0.9fr);
  gap: 10px;
  flex: 1;
  min-height: 0;
}

:deep(.custom-setting-builder__editor),
:deep(.custom-setting-builder__preview) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

:deep(.custom-setting-builder .formkit-outer) {
  margin-bottom: 0;
}

:deep(.custom-setting-builder .formkit-label) {
  color: #374151;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
}

:deep(.custom-fields-array) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 0 !important;
}

:deep(.custom-fields-array > .formkit-wrapper) {
  margin-bottom: 6px;
}

:deep(.custom-fields-array > .formkit-wrapper .formkit-label) {
  color: #374151;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
}

:deep(.custom-fields-array .formkit-items) {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

:deep(.custom-fields-array .formkit-item) {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 9px 10px;
}

:deep(.custom-fields-array .formkit-item[data-expanded="true"]),
:deep(.custom-fields-array .formkit-item:focus-within) {
  border-color: #bfdbfe;
  background: #f8fbff;
}

:deep(.custom-field-array-item) {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

:deep(.custom-fields-array .formkit-add) {
  min-height: 30px;
  width: fit-content;
  border-radius: 6px;
  font-size: 12px;
}

:deep(.custom-setting-builder__section-title),
:deep(.custom-field-card__title) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

:deep(.custom-field-card) {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  padding: 9px 10px;
}

:deep(.custom-field-card--editing) {
  border-color: #bfdbfe;
  background: #f8fbff;
}

:deep(.custom-field-card__body) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
  border-top: 1px solid #eef2f7;
  padding-top: 8px;
}

:deep(.custom-field-card__title strong) {
  overflow: hidden;
  color: #111827;
  font-size: 13px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.custom-field-card__summary) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  border: 0;
  background: transparent;
  padding: 0;
  text-align: left;
  cursor: pointer;
}

:deep(.custom-field-card__actions) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

:deep(.custom-field-card__actions button) {
  border: 0;
  background: transparent;
  padding: 0;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
}

:deep(.custom-field-card__actions button:last-child) {
  color: #dc2626;
}

:deep(.custom-field-card__check) {
  margin-bottom: 0 !important;
}

:deep(.custom-field-card__check .formkit-wrapper) {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
}

:deep(.custom-field-card__check input) {
  width: auto;
  min-height: 0;
}

:deep(.custom-field-options-array) {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border: 1px solid #eef2f7;
  border-radius: 8px;
  background: #f8fafc;
  padding: 8px;
}

:deep(.custom-field-options-array .formkit-label) {
  margin-bottom: 4px;
}

:deep(.custom-field-options-array__hint) {
  margin: 0 0 6px;
  color: #64748b;
  font-size: 12px;
  line-height: 18px;
}

:deep(.custom-field-option-array-row) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}

:deep(.custom-field-options-array .formkit-item) {
  border-radius: 6px;
  background: #fff;
  padding: 8px;
}

:deep(.custom-field-options-array .formkit-add) {
  min-height: 28px;
  border-radius: 6px;
  font-size: 12px;
}

:deep(.custom-field-options__header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

:deep(.custom-field-options__header span) {
  color: #374151;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
}

:deep(.custom-field-option-row) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  align-items: end;
  gap: 8px;
}

:deep(.custom-field-option-row button) {
  min-height: 32px;
  border: 0;
  background: transparent;
  color: #dc2626;
  font-size: 12px;
  cursor: pointer;
}

:deep(.custom-setting-builder__preview) {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  padding: 12px;
}

:deep(.custom-setting-builder__preview p),
:deep(.custom-setting-builder__error) {
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 18px;
}

:deep(.custom-setting-builder__error) {
  flex-shrink: 0;
  border: 1px solid #fecaca;
  border-radius: 6px;
  background: #fef2f2;
  padding: 8px 10px;
  color: #991b1b;
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

  .metadata-manager :deep(.insight-grid) {
    grid-template-columns: 1fr;
  }

  .metadata-manager :deep(.section-header) {
    flex-direction: column;
  }

  .metadata-manager :deep(.section-actions) {
    max-width: none;
    justify-content: flex-start;
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
  .metadata-manager :deep(.value-summary) {
    flex-direction: column;
    align-items: stretch;
  }

  .metadata-manager :deep(.source-chip-list),
  .metadata-manager :deep(.value-key-strip) {
    justify-content: flex-start;
  }

  .metadata-manager :deep(.resource-metadata-workbench),
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

  :deep(.custom-setting-builder__header),
  :deep(.custom-setting-builder__grid),
  :deep(.custom-field-card__grid),
  :deep(.custom-field-option-row) {
    grid-template-columns: 1fr;
  }

  .metadata-manager :deep(.annotation-key-group__header),
  .metadata-manager :deep(.annotation-field-source-list__item) {
    grid-template-columns: 1fr;
  }

  .metadata-manager :deep(.annotation-key-group__header) {
    flex-direction: column;
  }

  .metadata-manager :deep(.annotation-source-definition-group__header) {
    flex-direction: column;
    align-items: flex-start;
  }

  .metadata-manager :deep(.annotation-field-source-list__actions) {
    align-self: flex-start;
  }
}
</style>
