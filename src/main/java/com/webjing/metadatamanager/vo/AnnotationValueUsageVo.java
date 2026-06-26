package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "Stored annotation value usage statistics.")
public record AnnotationValueUsageVo(
    String targetRef,
    String annotationKey,
    int totalResources,
    int resourcesWithKey,
    int resourcesWithNonEmptyValue,
    List<String> sampleResourceNames,
    Map<String, String> sampleValues
) {
}
