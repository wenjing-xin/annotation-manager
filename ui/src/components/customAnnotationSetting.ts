import type { CustomAnnotationSettingRequest } from '@/api'
import type { MetadataModelSummary } from './metadata'

export type { CustomAnnotationSettingRequest }

export type CustomFieldType = 'text' | 'textarea' | 'select' | 'radio' | 'url' | 'email' | 'password' | 'color' | 'date'

export interface CustomAnnotationOptionDraft {
  id: string
  label: string
  value: string
}

export interface CustomAnnotationFieldDraft {
  id?: string
  name: string
  label: string
  type: CustomFieldType
  help: string
  placeholder: string
  required: boolean
  options?: CustomAnnotationOptionDraft[]
}

export function modelOptions(models: MetadataModelSummary[]) {
  return models
    .filter((model) => !model.special)
    .map((model) => {
      const targetRef = validTargetRef(model)
      return targetRef
        ? {
            label: `${model.displayName} (${targetRef})`,
            value: targetRef,
          }
        : undefined
    })
    .filter(Boolean) as Array<{ label: string; value: string }>
}

function validTargetRef(model: MetadataModelSummary) {
  if (/^[^/]+\/[^/]+$/.test(model.targetRef || '')) {
    return model.targetRef
  }
  if (model.group && model.kind) {
    return `${model.group}/${model.kind}`
  }
  return undefined
}

export function fieldDraftToSchema(field: CustomAnnotationFieldDraft) {
  const schema: Record<string, unknown> = {
    $formkit: field.type || 'text',
    name: (field.name || '').trim(),
    label: (field.label || '').trim() || (field.name || '').trim(),
  }
  if ((field.help || '').trim()) {
    schema.help = field.help.trim()
  }
  if ((field.placeholder || '').trim() && !['select', 'radio'].includes(field.type)) {
    schema.placeholder = field.placeholder.trim()
  }
  if (field.required) {
    schema.validation = 'required'
  }
  if (['select', 'radio'].includes(field.type)) {
    schema.options = (field.options || [])
      .map((option) => ({
        value: (option.value || '').trim(),
        label: (option.label || '').trim() || (option.value || '').trim(),
      }))
      .filter((option) => option.value)
  }
  return schema
}

export function schemaToFieldDrafts(schema: unknown): CustomAnnotationFieldDraft[] {
  const fields: CustomAnnotationFieldDraft[] = []
  collectSchemaFields(schema, fields)
  return fields
}

function collectSchemaFields(node: unknown, fields: CustomAnnotationFieldDraft[]) {
  if (Array.isArray(node)) {
    node.forEach((item) => collectSchemaFields(item, fields))
    return
  }
  if (!node || typeof node !== 'object') {
    return
  }

  const item = node as Record<string, unknown>
  if (item.$formkit && item.name) {
    fields.push(schemaNodeToFieldDraft(item))
  }
  Object.values(item).forEach((value) => collectSchemaFields(value, fields))
}

function schemaNodeToFieldDraft(node: Record<string, unknown>): CustomAnnotationFieldDraft {
  const type = normalizeFieldType(node.$formkit)
  const name = stringValue(node.name)
  return {
    id: randomDraftId(),
    name,
    label: stringValue(node.label) || name,
    type,
    help: stringValue(node.help),
    placeholder: stringValue(node.placeholder),
    required: stringValue(node.validation).split('|').includes('required'),
    options: normalizeOptions(node.options),
  }
}

function normalizeFieldType(value: unknown): CustomFieldType {
  const type = stringValue(value) as CustomFieldType
  return ['text', 'textarea', 'select', 'radio', 'url', 'email', 'password', 'color', 'date'].includes(type)
    ? type
    : 'text'
}

function normalizeOptions(options: unknown): CustomAnnotationOptionDraft[] {
  if (!Array.isArray(options)) {
    return []
  }
  return options
    .map((option) => {
      if (option && typeof option === 'object') {
        const item = option as Record<string, unknown>
        const value = stringValue(item.value)
        return {
          id: randomDraftId(),
          value,
          label: stringValue(item.label) || value,
        }
      }
      const value = stringValue(option)
      return {
        id: randomDraftId(),
        value,
        label: value,
      }
    })
    .filter((option) => option.value)
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : ''
}

function randomDraftId() {
  return Math.random().toString(36).slice(2)
}
