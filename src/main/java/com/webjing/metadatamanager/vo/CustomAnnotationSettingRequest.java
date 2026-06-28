package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Request for creating a custom AnnotationSetting form.")
public record CustomAnnotationSettingRequest(
    String name,
    String targetRef,
    List<Object> formSchema
) {
}
