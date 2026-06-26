import { axiosInstance } from '@halo-dev/api-client'

import { ApiAnnotationManagerWenjingComV1alpha1AnnotationMetadataApi } from './generated'

export * from './generated'

export const annotationManagerApi =
  new ApiAnnotationManagerWenjingComV1alpha1AnnotationMetadataApi(undefined, '', axiosInstance)
