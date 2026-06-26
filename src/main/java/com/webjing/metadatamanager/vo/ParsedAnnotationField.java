package com.webjing.metadatamanager.vo;

public record ParsedAnnotationField(
    String annotationSettingName,
    String targetRef,
    String annotationKey,
    String label,
    String inputType,
    String help,
    String validation
) {
}
