package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Parsed annotation metadata field definition.")
public record AnnotationFieldDefinition(
    String annotationSettingName,
    String targetRef,
    String annotationKey,
    String label,
    String inputType,
    String sourceType,
    String sourceName,
    boolean effective,
    boolean duplicate,
    String confidence,
    String help,
    String validation
) {
    public String conflictKey() {
        return targetRef + "\n" + annotationKey;
    }

    public AnnotationFieldDefinition withDuplicate(boolean duplicate) {
        return new AnnotationFieldDefinition(annotationSettingName, targetRef, annotationKey, label,
            inputType, sourceType, sourceName, effective, duplicate, confidence, help, validation);
    }
}
