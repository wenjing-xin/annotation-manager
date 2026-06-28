package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationValueScanner;
import com.webjing.metadatamanager.vo.AnnotationResourceMetadataUpdateRequest;
import com.webjing.metadatamanager.vo.AnnotationResourceUpdateResultVo;
import com.webjing.metadatamanager.vo.AnnotationResourceVo;
import com.webjing.metadatamanager.vo.AnnotationValueScanRequest;
import com.webjing.metadatamanager.vo.AnnotationValueUsageVo;
import com.webjing.metadatamanager.vo.BackupVo;
import com.webjing.metadatamanager.vo.ModelAnnotationValuesScanRequest;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;

@Component
@RequiredArgsConstructor
public class AnnotationValueScannerImpl implements AnnotationValueScanner {

    private final ReactiveExtensionClient client;
    private final SchemeManager schemeManager;

    @Override
    public Mono<AnnotationValueUsageVo> scan(AnnotationValueScanRequest request) {
        validateScanRequest(request.targetRef(), request.annotationKey());
        var clazz = resolveSupportedTargetClass(request.targetRef());
        if (clazz == null) {
            return Mono.just(usage(List.of(), request.targetRef(), request.annotationKey(),
                request.normalizedSampleSize()));
        }
        return listAllIfAvailable(clazz)
            .collectList()
            .map(resources -> usage(resources, request.targetRef(), request.annotationKey(),
                request.normalizedSampleSize()));
    }

    @Override
    public Mono<List<AnnotationValueUsageVo>> scanModel(ModelAnnotationValuesScanRequest request) {
        validateTargetRefValue(request.targetRef());
        var clazz = resolveSupportedTargetClass(request.targetRef());
        if (clazz == null) {
            return Mono.just(List.of());
        }
        return listAllIfAvailable(clazz)
            .collectList()
            .map(resources -> {
                var annotationKeys = new LinkedHashSet<String>();
                resources.forEach(resource -> annotationKeys.addAll(annotations(resource).keySet()));
                return annotationKeys.stream()
                    .sorted(Comparator.naturalOrder())
                    .map(key -> usage(resources, request.targetRef(), key,
                        request.normalizedSampleSize()))
                    .toList();
            });
    }

    @Override
    public Mono<List<AnnotationResourceVo>> listResources(String targetRef) {
        validateTargetRefValue(targetRef);
        var clazz = resolveSupportedTargetClass(targetRef);
        if (clazz == null) {
            return Mono.just(List.of());
        }
        return listAllIfAvailable(clazz)
            .map(resource -> new AnnotationResourceVo(
                targetRef,
                resource.getApiVersion(),
                resource.getKind(),
                resource.getMetadata().getName(),
                displayName(resource),
                new LinkedHashMap<>(annotations(resource))
            ))
            .collectList();
    }

    @Override
    public Mono<AnnotationResourceUpdateResultVo> updateResourceAnnotations(
        AnnotationResourceMetadataUpdateRequest request) {
        validateResourceUpdateRequest(request);
        var clazz = resolveTargetClass(request.targetRef());
        return client.fetch(clazz, request.name())
            .flatMap(resource -> {
                var backup = resourceBackup(request.targetRef(), resource);
                var annotations = replaceAnnotationsOnly(resource, request.annotations());
                return client.update(resource)
                    .map(updated -> new AnnotationResourceUpdateResultVo(
                        request.targetRef(),
                        updated.getMetadata().getName(),
                        new LinkedHashMap<>(annotations(updated)),
                        backup
                    ));
            });
    }

    static LinkedHashMap<String, String> replaceAnnotationsOnly(AbstractExtension resource,
        Map<String, String> nextAnnotations) {
        var annotations = new LinkedHashMap<String, String>();
        if (nextAnnotations != null) {
            nextAnnotations.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    annotations.put(key, value == null ? "" : value);
                }
            });
        }
        resource.getMetadata().setAnnotations(annotations);
        return annotations;
    }

    @Override
    public Mono<BackupVo> backup(String targetRef, String annotationKey) {
        validateScanRequest(targetRef, annotationKey);
        var clazz = resolveSupportedTargetClass(targetRef);
        if (clazz == null) {
            return Mono.just(new BackupVo(Instant.now().toString(), "annotation-value-cleanup",
                targetRef, annotationKey, List.of()));
        }
        return listAllIfAvailable(clazz)
            .filter(resource -> annotations(resource).containsKey(annotationKey))
            .map(resource -> backupItem(resource, annotationKey))
            .collectList()
            .map(items -> new BackupVo(Instant.now().toString(), "annotation-value-cleanup",
                targetRef, annotationKey, items));
    }

    public Flux<? extends AbstractExtension> resourcesWithKey(String targetRef, String annotationKey) {
        validateScanRequest(targetRef, annotationKey);
        var clazz = resolveSupportedTargetClass(targetRef);
        if (clazz == null) {
            return Flux.empty();
        }
        return listAllIfAvailable(clazz)
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

    private <T extends AbstractExtension> Flux<T> listAllIfAvailable(Class<T> clazz) {
        return listAll(clazz)
            .onErrorResume(error -> isMissingResourceRoute(error) ? Flux.empty() : Flux.error(error));
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

    private BackupVo resourceBackup(String targetRef, AbstractExtension resource) {
        return new BackupVo(Instant.now().toString(), "annotation-resource-update",
            targetRef, null, List.of(resourceBackupItem(resource)));
    }

    private Map<String, Object> resourceBackupItem(AbstractExtension resource) {
        var item = new LinkedHashMap<String, Object>();
        item.put("apiVersion", resource.getApiVersion());
        item.put("kind", resource.getKind());
        item.put("name", resource.getMetadata().getName());
        item.put("annotations", new LinkedHashMap<>(annotations(resource)));
        return item;
    }

    private void validateScanRequest(String targetRef, String annotationKey) {
        validateTargetRefValue(targetRef);
        if (annotationKey == null || annotationKey.isBlank()) {
            throw new IllegalArgumentException("annotationKey is required.");
        }
    }

    private void validateTargetRef(String targetRef) {
        validateTargetRefValue(targetRef);
        resolveTargetClass(targetRef);
    }

    private void validateTargetRefValue(String targetRef) {
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef is required.");
        }
    }

    private void validateResourceUpdateRequest(AnnotationResourceMetadataUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required.");
        }
        validateTargetRef(request.targetRef());
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        if (request.annotations() == null) {
            throw new IllegalArgumentException("annotations is required.");
        }
    }

    private String displayName(AbstractExtension resource) {
        for (var methodName : List.of("getTitle", "getDisplayName", "getSlug")) {
            var value = specStringValue(resource, methodName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return resource.getMetadata().getName();
    }

    private String specStringValue(AbstractExtension resource, String methodName) {
        try {
            Method getSpec = resource.getClass().getMethod("getSpec");
            var spec = getSpec.invoke(resource);
            if (spec == null) {
                return null;
            }
            Method method = spec.getClass().getMethod(methodName);
            var value = method.invoke(spec);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Class<? extends AbstractExtension> resolveTargetClass(String targetRef) {
        return resolveTargetClassIfAvailable(targetRef)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported targetRef: " + targetRef));
    }

    private Class<? extends AbstractExtension> resolveSupportedTargetClass(String targetRef) {
        return resolveTargetClassIfAvailable(targetRef).orElse(null);
    }

    private java.util.Optional<Class<? extends AbstractExtension>> resolveTargetClassIfAvailable(
        String targetRef) {
        return schemeManager.schemes().stream()
            .filter(scheme -> targetRef(scheme).equals(targetRef))
            .map(Scheme::type)
            .filter(AbstractExtension.class::isAssignableFrom)
            .map(this::asExtensionClass)
            .findFirst();
    }

    private String targetRef(Scheme scheme) {
        var gvk = scheme.groupVersionKind();
        return gvk.group() + "/" + gvk.kind();
    }

    @SuppressWarnings("unchecked")
    private Class<? extends AbstractExtension> asExtensionClass(Class<?> type) {
        return (Class<? extends AbstractExtension>) type;
    }

    private boolean isMissingResourceRoute(Throwable error) {
        var message = error.getMessage();
        if (message == null) {
            message = "";
        }
        return error.getClass().getName().contains("NotFound")
            || message.contains("404")
            || message.contains("No static resource");
    }
}
