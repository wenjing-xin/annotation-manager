import { axiosInstance } from '@halo-dev/api-client'

import {
  ApiAnnotationManagerWenjingComV1alpha1AnnotationMetadataApi,
  type CleanupResultVo,
  type AnnotationSettingFormVo,
  type CustomAnnotationSettingRequest,
} from './generated'

export * from './generated'

const generatedAnnotationManagerApi =
  new ApiAnnotationManagerWenjingComV1alpha1AnnotationMetadataApi(undefined, '', axiosInstance)

export const annotationManagerApi = Object.assign(generatedAnnotationManagerApi, {
  updateCustomAnnotationSetting({
    name,
    customAnnotationSettingRequest,
  }: {
    name: string
    customAnnotationSettingRequest?: CustomAnnotationSettingRequest
  }) {
    return axiosInstance.put<AnnotationSettingFormVo>(
      `/apis/api.annotation-manager.wenjing.com/v1alpha1/annotation-settings/${encodeURIComponent(name)}/custom`,
      customAnnotationSettingRequest,
    )
  },
  deleteCustomAnnotationSetting({ name }: { name: string }) {
    return axiosInstance.delete<CleanupResultVo>(
      `/apis/api.annotation-manager.wenjing.com/v1alpha1/annotation-settings/${encodeURIComponent(name)}/custom`,
    )
  },
})

export * from './generated'
