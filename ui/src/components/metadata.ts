import type {
  AnnotationConflictVo,
  AnnotationFieldDefinition,
  AnnotationModelVo,
  AnnotationValueUsageVo,
} from '@/api'

export type TabKey = 'definitions' | 'conflicts' | 'values'

export const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'definitions', label: '字段定义' },
  { key: 'conflicts', label: '重复冲突' },
  { key: 'values', label: '存量值' },
]

export const targetItems = [
  { label: '全部模型' },
  { label: 'Post', value: 'content.halo.run/Post' },
  { label: 'SinglePage', value: 'content.halo.run/SinglePage' },
  { label: 'Category', value: 'content.halo.run/Category' },
  { label: 'Tag', value: 'content.halo.run/Tag' },
]

export const valueTargetItems = targetItems.slice(1)

export const builtInModelItems = valueTargetItems

export const sourceTypeItems = [
  { label: '全部来源' },
  { label: '插件', value: 'plugin' },
  { label: '主题', value: 'theme' },
  { label: '系统', value: 'system' },
  { label: '未知', value: 'unknown' },
]

export const booleanItems = [
  { label: '全部' },
  { label: '是', value: 'true' },
  { label: '否', value: 'false' },
]

export function sourceLabel(field: AnnotationFieldDefinition) {
  if (field.sourceName) {
    return `${field.sourceType || 'unknown'}/${field.sourceName}`
  }
  return field.sourceType || 'unknown'
}

export function modelSourceLabel(model: Pick<MetadataModelSummary, 'sourceType' | 'sourceName' | 'sourceDisplayName'>) {
  const sourceName = model.sourceDisplayName || model.sourceName
  if (sourceName) {
    return `${sourceTypeLabel(model.sourceType)}/${sourceName}`
  }
  return sourceTypeLabel(model.sourceType)
}

export function modelDisplayName(targetRef?: string) {
  if (!targetRef) {
    return '-'
  }
  const parts = targetRef.split('/')
  return parts[parts.length - 1] || targetRef
}

export function supportsValueScan(targetRef?: string) {
  return builtInModelItems.some((item) => item.value === targetRef)
}

export interface MetadataModelSummary {
  targetRef: string
  displayName: string
  group?: string
  version?: string
  kind?: string
  plural?: string
  singular?: string
  className?: string
  sourceType: string
  sourceName?: string
  sourceDisplayName?: string
  confidence?: string
  description?: string
  descriptionSource?: string
  fieldCount: number
  conflictCount: number
  valueKeyCount: number
  sourceCount: number
  effectiveFieldCount: number
  supportsValueScan: boolean
}

export function buildModelSummaries(
  models: AnnotationModelVo[],
  fields: AnnotationFieldDefinition[],
  conflicts: AnnotationConflictVo[],
  valuesByModel: Record<string, AnnotationValueUsageVo[]>,
): MetadataModelSummary[] {
  const modelsByTargetRef = new Map<string, AnnotationModelVo>()
  models.forEach((model) => {
    if (model.targetRef) {
      modelsByTargetRef.set(model.targetRef, model)
    }
  })
  builtInModelItems.forEach((item) => {
    if (item.value && !modelsByTargetRef.has(item.value)) {
      const [group, kind] = item.value.split('/')
      modelsByTargetRef.set(item.value, {
        targetRef: item.value,
        group,
        kind,
        sourceType: 'system',
        sourceName: 'Halo',
        sourceDisplayName: 'Halo',
        description: builtInModelDescription(item.value),
        descriptionSource: 'fallback',
        confidence: 'fallback',
        supportsValueScan: true,
      })
    }
  })
  fields.forEach((field) => {
    if (field.targetRef && !modelsByTargetRef.has(field.targetRef)) {
      const [group, kind] = field.targetRef.split('/')
      modelsByTargetRef.set(field.targetRef, {
        targetRef: field.targetRef,
        group,
        kind,
        sourceType: 'unknown',
        description: builtInModelDescription(field.targetRef),
        descriptionSource: builtInModelDescription(field.targetRef) ? 'fallback' : undefined,
        confidence: 'annotation-setting-target',
      })
    }
  })

  return Array.from(modelsByTargetRef.values())
    .sort((left, right) => {
      const sourceCompare = (left.sourceType || '').localeCompare(right.sourceType || '')
      if (sourceCompare) {
        return sourceCompare
      }
      return modelDisplayName(left.targetRef).localeCompare(modelDisplayName(right.targetRef))
    })
    .map((model) => {
      const targetRef = model.targetRef || ''
      const modelFields = fields.filter((field) => field.targetRef === targetRef)
      const modelConflicts = conflicts.filter((conflict) => conflict.targetRef === targetRef)
      const sources = new Set(
        modelFields.map((field) => `${field.sourceType || 'unknown'}:${field.sourceName || ''}`),
      )
      return {
        targetRef,
        displayName: model.kind || modelDisplayName(targetRef),
        group: model.group,
        version: model.version,
        kind: model.kind,
        plural: model.plural,
        singular: model.singular,
        className: model.className,
        sourceType: model.sourceType || 'unknown',
        sourceName: model.sourceName,
        sourceDisplayName: model.sourceDisplayName || model.sourceName,
        confidence: model.confidence,
        description: model.description || builtInModelDescription(targetRef),
        descriptionSource: model.descriptionSource || (builtInModelDescription(targetRef) ? 'fallback' : undefined),
        fieldCount: modelFields.length,
        conflictCount: modelConflicts.length,
        valueKeyCount: valuesByModel[targetRef]?.length || 0,
        sourceCount: sources.size,
        effectiveFieldCount: modelFields.filter((field) => field.effective).length,
        supportsValueScan: Boolean(model.supportsValueScan || supportsValueScan(targetRef)),
      }
    })
}

export function sourceTypeLabel(sourceType?: string) {
  switch (sourceType) {
    case 'system':
      return '系统'
    case 'plugin':
      return '插件'
    case 'theme':
      return '主题'
    default:
      return '未知'
  }
}

function builtInModelDescription(targetRef?: string) {
  switch (targetRef) {
    case 'content.halo.run/Post':
      return '文章模型，存储文章元数据、发布设置、分类标签关系和运行状态。'
    case 'content.halo.run/SinglePage':
      return '独立页面模型，存储页面元数据、发布设置和运行状态。'
    case 'content.halo.run/Category':
      return '分类模型，用于组织文章并生成分类归档。'
    case 'content.halo.run/Tag':
      return '标签模型，用于按自由标签组织文章。'
    default:
      return undefined
  }
}
