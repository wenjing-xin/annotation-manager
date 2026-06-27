package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "Result for metadata.annotations update of one resource.")
public record AnnotationResourceUpdateResultVo(
    String targetRef,
    String name,
    Map<String, String> annotations,
    BackupVo backup
) {
}
