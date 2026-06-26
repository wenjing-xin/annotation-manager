package com.webjing.metadatamanager.service;

import com.webjing.metadatamanager.vo.CleanupPreviewVo;
import com.webjing.metadatamanager.vo.CleanupRequest;
import com.webjing.metadatamanager.vo.CleanupResultVo;
import com.webjing.metadatamanager.vo.DeleteSettingPreviewVo;
import com.webjing.metadatamanager.vo.DeleteSettingRequest;
import reactor.core.publisher.Mono;

public interface AnnotationCleanupService {
    Mono<DeleteSettingPreviewVo> previewDeleteSetting(String name);

    Mono<CleanupResultVo> deleteSetting(String name, DeleteSettingRequest request);

    Mono<CleanupPreviewVo> previewCleanupValues(CleanupRequest request);

    Mono<CleanupResultVo> cleanupValues(CleanupRequest request);
}
