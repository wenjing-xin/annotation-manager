package com.webjing.metadatamanager.service;

import com.webjing.metadatamanager.vo.AnnotationValueScanRequest;
import com.webjing.metadatamanager.vo.AnnotationValueUsageVo;
import com.webjing.metadatamanager.vo.BackupVo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.AbstractExtension;

public interface AnnotationValueScanner {
    Mono<AnnotationValueUsageVo> scan(AnnotationValueScanRequest request);

    Mono<BackupVo> backup(String targetRef, String annotationKey);

    Flux<? extends AbstractExtension> resourcesWithKey(String targetRef, String annotationKey);
}
