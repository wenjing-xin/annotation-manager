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
const allModelOptions = computed(() =>
  buildModelSummaries(annotationModels.value, fields.value, conflicts.value, valuesByModel.value)
    .filter((model) => !model.special),
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
    confirmText: '确认删除',
    cancelText: '取消',
    onConfirm: () => deleteCustomSetting(name),
  })
}

async function deleteCustomSetting(name: string) {
  deletingSetting.value = true
  try {
    await annotationManagerApi.deleteCustomAnnotationSetting({ name })
    Toast.success('自定义表单定义已删除')
    rememberDeletedSettings([name])
    pruneDeletedSettings([name])
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
        :models="allModelOptions"
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

<style scoped src="./HomeView.css"></style>
