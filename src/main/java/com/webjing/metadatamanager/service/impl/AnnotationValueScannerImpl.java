package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationValueScanner;
import com.webjing.metadatamanager.vo.AnnotationValueScanRequest;
import com.webjing.metadatamanager.vo.AnnotationValueUsageVo;
import com.webjing.metadatamanager.vo.BackupVo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
@RequiredArgsConstructor
public class AnnotationValueScannerImpl implements AnnotationValueScanner {

    private final ReactiveExtensionClient client;

    @Override
    public Mono<AnnotationValueUsageVo> scan(AnnotationValueScanRequest request) {
        validateRequest(request.targetRef(), request.annotationKey());
        return listAll(resolveTargetClass(request.targetRef()))
            .collectList()
            .map(resources -> usage(resources, request.targetRef(), request.annotationKey(),
                request.normalizedSampleSize()));
    }

    @Override
    public Mono<BackupVo> backup(String targetRef, String annotationKey) {
        validateRequest(targetRef, annotationKey);
        return listAll(resolveTargetClass(targetRef))
            .filter(resource -> annotations(resource).containsKey(annotationKey))
            .map(resource -> backupItem(resource, annotationKey))
            .collectList()
            .map(items -> new BackupVo(Instant.now().toString(), "annotation-value-cleanup",
                targetRef, annotationKey, items));
    }

    public Flux<? extends AbstractExtension> resourcesWithKey(String targetRef, String annotationKey) {
        validateRequest(targetRef, annotationKey);
        return listAll(resolveTargetClass(targetRef))
            .filter(resource -> annotations(resource).containsKey(annotationKey));
    }

    public Map<String, String> annotations(AbstractExtension resource) {
        var metadata = resource.getMetadata();
        if (metadata == null || metadata.getAnnotations() == null) {
            return Map.of();
        }
        return metadata.getAnnotations();
    }

    private <T extends AbstractExtension> Flux<T> listAll(Class<T> clazz) {
        return client.listAll(clazz, ListOptions.builder().build(), Sort.by("metadata.name").ascending());
    }

    private AnnotationValueUsageVo usage(List<? extends AbstractExtension> resources, String targetRef,
        String annotationKey, int sampleSize) {
        var sampleResourceNames = new ArrayList<String>();
        var sampleValues = new LinkedHashMap<String, String>();
        var withKey = 0;
        var nonEmpty = 0;
        for (var resource : resources) {
            var annotations = annotations(resource);
            if (!annotations.containsKey(annotationKey)) {
                continue;
            }
            withKey++;
            var value = annotations.get(annotationKey);
            if (value != null && !value.isBlank()) {
                nonEmpty++;
            }
            if (sampleResourceNames.size() < sampleSize) {
                var name = resource.getMetadata().getName();
                sampleResourceNames.add(name);
                sampleValues.put(name, value);
            }
        }
        return new AnnotationValueUsageVo(targetRef, annotationKey, resources.size(), withKey,
            nonEmpty, sampleResourceNames, sampleValues);
    }

    private Map<String, Object> backupItem(AbstractExtension resource, String annotationKey) {
        var item = new LinkedHashMap<String, Object>();
        item.put("apiVersion", resource.getApiVersion());
        item.put("kind", resource.getKind());
        item.put("name", resource.getMetadata().getName());
        item.put("annotationKey", annotationKey);
        item.put("annotationValue", annotations(resource).get(annotationKey));
        item.put("annotations", new LinkedHashMap<>(annotations(resource)));
        return item;
    }

    private void validateRequest(String targetRef, String annotationKey) {
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef is required.");
        }
        if (annotationKey == null || annotationKey.isBlank()) {
            throw new IllegalArgumentException("annotationKey is required.");
        }
        resolveTargetClass(targetRef);
    }

    private Class<? extends AbstractExtension> resolveTargetClass(String targetRef) {
        return switch (targetRef) {
            case "content.halo.run/Post" -> Post.class;
            case "content.halo.run/SinglePage" -> SinglePage.class;
            case "content.halo.run/Category" -> Category.class;
            case "content.halo.run/Tag" -> Tag.class;
            default -> throw new IllegalArgumentException("Unsupported targetRef: " + targetRef);
        };
    }
}
