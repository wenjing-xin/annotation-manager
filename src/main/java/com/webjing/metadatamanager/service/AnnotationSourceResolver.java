package com.webjing.metadatamanager.service;

import com.webjing.metadatamanager.vo.RuntimeState;
import com.webjing.metadatamanager.vo.SourceInfo;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AnnotationSetting;

public interface AnnotationSourceResolver {
    SourceInfo resolve(AnnotationSetting setting);

    Mono<RuntimeState> runtimeState();

    boolean isEffective(SourceInfo sourceInfo, RuntimeState runtimeState);
}
