import type { AnnotationFieldDefinition } from '@/api'

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
