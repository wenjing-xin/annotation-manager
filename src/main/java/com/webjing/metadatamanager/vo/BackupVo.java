package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "JSON backup payload generated before destructive cleanup.")
public record BackupVo(
    String generatedAt,
    String operation,
    String targetRef,
    String annotationKey,
    List<Map<String, Object>> items
) {
}
