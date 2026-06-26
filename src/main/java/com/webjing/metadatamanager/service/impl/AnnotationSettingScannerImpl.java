package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationConflictDetector;
import com.webjing.metadatamanager.service.AnnotationFieldParser;
import com.webjing.metadatamanager.service.AnnotationSettingScanner;
import com.webjing.metadatamanager.service.AnnotationSourceResolver;
import com.webjing.metadatamanager.vo.AnnotationConflictVo;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
@RequiredArgsConstructor
public class AnnotationSettingScannerImpl implements AnnotationSettingScanner {

    private final ReactiveExtensionClient client;
    private final AnnotationFieldParser parser;
    private final AnnotationSourceResolver sourceResolver;
    private final AnnotationConflictDetector conflictDetector;

    @Override
    public Mono<List<AnnotationFieldDefinition>> scanFields() {
        return sourceResolver.runtimeState()
            .flatMap(runtimeState -> client.listAll(AnnotationSetting.class,
                    ListOptions.builder().build(), Sort.by("metadata.name").ascending())
                .flatMapIterable(setting -> {
                    var sourceInfo = sourceResolver.resolve(setting);
                    var effective = sourceResolver.isEffective(sourceInfo, runtimeState);
                    return parser.parse(setting).stream()
                        .map(field -> new AnnotationFieldDefinition(
                            field.annotationSettingName(),
                            field.targetRef(),
                            field.annotationKey(),
                            field.label(),
                            field.inputType(),
                            sourceInfo.sourceType(),
                            sourceInfo.sourceName(),
                            effective,
                            false,
                            sourceInfo.confidence(),
                            field.help(),
                            field.validation()
                        ))
                        .toList();
                })
                .collectList()
                .map(this::markDuplicates));
    }

    @Override
    public Mono<List<AnnotationConflictVo>> scanConflicts() {
        return scanFields().map(conflictDetector::detect);
    }

    private List<AnnotationFieldDefinition> markDuplicates(List<AnnotationFieldDefinition> fields) {
        Map<String, Long> counts = fields.stream()
            .collect(Collectors.groupingBy(AnnotationFieldDefinition::conflictKey, Collectors.counting()));
        return fields.stream()
            .map(field -> field.withDuplicate(counts.getOrDefault(field.conflictKey(), 0L) > 1))
            .toList();
    }
}
