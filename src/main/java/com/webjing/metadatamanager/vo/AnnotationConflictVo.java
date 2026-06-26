package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Annotation field definition conflict grouped by targetRef and annotation key.")
public record AnnotationConflictVo(
    String conflictKey,
    String targetRef,
    String annotationKey,
    List<AnnotationFieldDefinition> definitions
) {
}
