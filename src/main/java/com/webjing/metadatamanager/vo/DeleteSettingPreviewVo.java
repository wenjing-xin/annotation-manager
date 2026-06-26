package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Preview for deleting a duplicate AnnotationSetting.")
public record DeleteSettingPreviewVo(
    String annotationSettingName,
    boolean duplicate,
    List<AnnotationFieldDefinition> definitions,
    BackupVo backup,
    List<String> warnings
) {
}
