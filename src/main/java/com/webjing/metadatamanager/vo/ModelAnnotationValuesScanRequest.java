package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request for scanning all stored annotation keys on one supported model.")
public record ModelAnnotationValuesScanRequest(
    String targetRef,
    Integer sampleSize
) {
    public int normalizedSampleSize() {
        if (sampleSize == null || sampleSize < 1) {
            return 5;
        }
        return Math.min(sampleSize, 20);
    }
}
