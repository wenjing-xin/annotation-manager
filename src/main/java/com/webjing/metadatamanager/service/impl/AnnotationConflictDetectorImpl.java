package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationConflictDetector;
import com.webjing.metadatamanager.vo.AnnotationConflictVo;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AnnotationConflictDetectorImpl implements AnnotationConflictDetector {

    @Override
    public List<AnnotationConflictVo> detect(List<AnnotationFieldDefinition> fields) {
        Map<String, List<AnnotationFieldDefinition>> grouped = fields.stream()
            .collect(Collectors.groupingBy(AnnotationFieldDefinition::conflictKey));
        return grouped.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> {
                var first = entry.getValue().get(0);
                var definitions = entry.getValue().stream()
                    .sorted(Comparator.comparing(AnnotationFieldDefinition::effective).reversed()
                        .thenComparing(AnnotationFieldDefinition::sourceType)
                        .thenComparing(AnnotationFieldDefinition::annotationSettingName))
                    .toList();
                return new AnnotationConflictVo(entry.getKey(), first.targetRef(),
                    first.annotationKey(), definitions);
            })
            .sorted(Comparator.comparing(AnnotationConflictVo::targetRef)
                .thenComparing(AnnotationConflictVo::annotationKey))
            .toList();
    }
}
