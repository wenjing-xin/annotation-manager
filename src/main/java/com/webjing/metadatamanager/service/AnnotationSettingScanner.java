package com.webjing.metadatamanager.service;

import com.webjing.metadatamanager.vo.AnnotationConflictVo;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import com.webjing.metadatamanager.vo.AnnotationSettingFormVo;
import com.webjing.metadatamanager.vo.CustomAnnotationSettingRequest;
import java.util.List;
import reactor.core.publisher.Mono;

public interface AnnotationSettingScanner {
    Mono<List<AnnotationFieldDefinition>> scanFields();

    Mono<List<AnnotationConflictVo>> scanConflicts();

    Mono<List<AnnotationSettingFormVo>> scanSettingForms(String targetRef);

    Mono<AnnotationSettingFormVo> createCustomSetting(CustomAnnotationSettingRequest request);

    Mono<AnnotationSettingFormVo> updateCustomSetting(String name, CustomAnnotationSettingRequest request);

    Mono<com.webjing.metadatamanager.vo.CleanupResultVo> deleteCustomSetting(String name);
}
