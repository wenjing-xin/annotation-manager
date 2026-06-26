package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request for scanning stored annotation values.")
public record AnnotationValueScanRequest(
    String targetRef,
    String annotationKey,
    Integer sampleSize
) {
    public int normalizedSampleSize() {
        if (sampleSize == null || sampleSize < 1) {
            return 10;
        }
        return Math.min(sampleSize, 50);
    }
}
