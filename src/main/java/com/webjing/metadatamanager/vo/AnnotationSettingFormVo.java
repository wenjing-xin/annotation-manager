package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "AnnotationSetting form schema with source attribution.")
public record AnnotationSettingFormVo(
    String name,
    String targetRef,
    String sourceType,
    String sourceName,
    boolean effective,
    String confidence,
    List<Object> formSchema
) {
}
