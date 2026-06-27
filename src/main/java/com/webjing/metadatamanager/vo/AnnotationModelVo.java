package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Registered Halo extension model metadata.")
public record AnnotationModelVo(
    String targetRef,
    String apiVersion,
    String group,
    String version,
    String kind,
    String plural,
    String singular,
    String sourceType,
    String sourceName,
    String sourceDisplayName,
    String confidence,
    String className,
    String description,
    String descriptionSource,
    boolean supportsValueScan
) {
}
