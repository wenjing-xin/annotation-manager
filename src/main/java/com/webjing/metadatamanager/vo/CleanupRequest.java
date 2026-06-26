package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request for previewing or executing stored annotation value cleanup.")
public record CleanupRequest(
    String targetRef,
    String annotationKey,
    String confirmedAnnotationKey
) {
}
