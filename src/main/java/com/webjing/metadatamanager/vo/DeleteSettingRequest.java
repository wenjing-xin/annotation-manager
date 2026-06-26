package com.webjing.metadatamanager.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request for deleting a duplicate AnnotationSetting.")
public record DeleteSettingRequest(String confirmedName) {
}
