package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "One resource with folded model data and visible metadata.annotations.")
public record AnnotationResourceVo(
    String targetRef,
    String apiVersion,
    String kind,
    String name,
    String displayName,
    Map<String, String> annotations
) {
}
