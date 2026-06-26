package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Cleanup preview including usage statistics and JSON backup.")
public record CleanupPreviewVo(
    String targetRef,
    String annotationKey,
    int totalResources,
    int resourcesWithKey,
    int resourcesWithNonEmptyValue,
    java.util.List<String> sampleResourceNames,
    java.util.Map<String, String> sampleValues,
    BackupVo backup
) {
    public static CleanupPreviewVo fromUsage(AnnotationValueUsageVo usage, BackupVo backup) {
        return new CleanupPreviewVo(usage.targetRef(), usage.annotationKey(), usage.totalResources(),
            usage.resourcesWithKey(), usage.resourcesWithNonEmptyValue(), usage.sampleResourceNames(),
            usage.sampleValues(), backup);
    }
}
