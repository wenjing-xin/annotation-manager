package com.webjing.metadatamanager.service;

import com.webjing.metadatamanager.vo.AnnotationValueScanRequest;
import com.webjing.metadatamanager.vo.AnnotationValueUsageVo;
import com.webjing.metadatamanager.vo.AnnotationResourceMetadataUpdateRequest;
import com.webjing.metadatamanager.vo.AnnotationResourceUpdateResultVo;
import com.webjing.metadatamanager.vo.AnnotationResourceVo;
import com.webjing.metadatamanager.vo.BackupVo;
import com.webjing.metadatamanager.vo.ModelAnnotationValuesScanRequest;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;

public interface AnnotationValueScanner {
    Mono<AnnotationValueUsageVo> scan(AnnotationValueScanRequest request);

    Mono<List<AnnotationValueUsageVo>> scanModel(ModelAnnotationValuesScanRequest request);

    Mono<List<AnnotationResourceVo>> listResources(String targetRef);

    Mono<AnnotationResourceUpdateResultVo> updateResourceAnnotations(
        AnnotationResourceMetadataUpdateRequest request);

    Mono<BackupVo> backup(String targetRef, String annotationKey);

    Flux<? extends Extension> resourcesWithKey(String targetRef, String annotationKey);
}
