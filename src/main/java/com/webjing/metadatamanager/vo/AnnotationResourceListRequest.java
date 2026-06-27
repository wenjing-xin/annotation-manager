package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request for listing resources and their metadata.annotations.")
public record AnnotationResourceListRequest(
    String targetRef
) {
}
