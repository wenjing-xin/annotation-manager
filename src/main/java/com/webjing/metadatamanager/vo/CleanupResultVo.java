package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result returned after destructive cleanup.")
public record CleanupResultVo(
    String targetRef,
    String annotationKey,
    int updatedResources,
    BackupVo backup
) {
}
