package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "Request for replacing metadata.annotations of one resource.")
public record AnnotationResourceMetadataUpdateRequest(
    String targetRef,
    String name,
    Map<String, String> annotations
) {
}
