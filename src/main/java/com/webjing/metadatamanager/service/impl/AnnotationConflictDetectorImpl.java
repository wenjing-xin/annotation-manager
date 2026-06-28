package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationConflictDetector;
import com.webjing.metadatamanager.vo.AnnotationConflictVo;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AnnotationConflictDetectorImpl implements AnnotationConflictDetector {

    @Override
    public List<AnnotationConflictVo> detect(List<AnnotationFieldDefinition> fields) {
        Map<String, List<AnnotationFieldDefinition>> grouped = deduplicateDefinitions(fields).stream()
            .collect(Collectors.groupingBy(AnnotationFieldDefinition::conflictKey));
        return grouped.entrySet().stream()
            .map(entry -> {
                var definitions = entry.getValue().stream()
                    .filter(AnnotationFieldDefinition::duplicate)
                    .sorted(Comparator.comparing(AnnotationFieldDefinition::effective).reversed()
                        .thenComparing(AnnotationFieldDefinition::sourceType)
                        .thenComparing(AnnotationFieldDefinition::annotationSettingName))
                    .toList();
                return Map.entry(entry.getKey(), definitions);
            })
            .filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> {
                var first = entry.getValue().get(0);
                return new AnnotationConflictVo(entry.getKey(), first.targetRef(),
                    first.annotationKey(), entry.getValue());
            })
            .sorted(Comparator.comparing(AnnotationConflictVo::targetRef)
                .thenComparing(AnnotationConflictVo::annotationKey))
            .toList();
    }

    private List<AnnotationFieldDefinition> deduplicateDefinitions(
        List<AnnotationFieldDefinition> fields) {
        var deduplicated = new LinkedHashMap<String, AnnotationFieldDefinition>();
        fields.forEach(field -> deduplicated.putIfAbsent(definitionKey(field), field));
        return List.copyOf(deduplicated.values());
    }

    private String definitionKey(AnnotationFieldDefinition field) {
        return field.annotationSettingName() + "\n" + field.targetRef() + "\n"
            + field.annotationKey();
    }
}
