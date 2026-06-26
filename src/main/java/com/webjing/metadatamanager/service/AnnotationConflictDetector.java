package com.webjing.metadatamanager.service;

import com.webjing.metadatamanager.vo.AnnotationConflictVo;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import java.util.List;

public interface AnnotationConflictDetector {
    List<AnnotationConflictVo> detect(List<AnnotationFieldDefinition> fields);
}
