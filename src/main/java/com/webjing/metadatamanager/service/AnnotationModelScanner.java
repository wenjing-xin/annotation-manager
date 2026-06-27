package com.webjing.metadatamanager.service;

import com.webjing.metadatamanager.vo.AnnotationModelVo;
import java.util.List;
import reactor.core.publisher.Mono;

public interface AnnotationModelScanner {
    Mono<List<AnnotationModelVo>> scanModels();
}
