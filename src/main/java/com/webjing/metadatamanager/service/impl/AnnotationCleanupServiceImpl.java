package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationCleanupService;
import com.webjing.metadatamanager.service.AnnotationSettingScanner;
import com.webjing.metadatamanager.service.AnnotationValueScanner;
import com.webjing.metadatamanager.vo.BackupVo;
import com.webjing.metadatamanager.vo.CleanupPreviewVo;
import com.webjing.metadatamanager.vo.CleanupRequest;
import com.webjing.metadatamanager.vo.CleanupResultVo;
import com.webjing.metadatamanager.vo.DeleteSettingPreviewVo;
import com.webjing.metadatamanager.vo.DeleteSettingRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
@RequiredArgsConstructor
public class AnnotationCleanupServiceImpl implements AnnotationCleanupService {

    private final ReactiveExtensionClient client;
    private final AnnotationSettingScanner settingScanner;
    private final AnnotationValueScanner valueScanner;

    @Override
    public Mono<DeleteSettingPreviewVo> previewDeleteSetting(String name) {
        return Mono.zip(client.fetch(AnnotationSetting.class, name), settingScanner.scanFields())
            .map(tuple -> {
                var setting = tuple.getT1();
                if (setting.getMetadata().getDeletionTimestamp() != null) {
                    throw new IllegalArgumentException("AnnotationSetting is already deleting: " + name);
                }
                var fields = tuple.getT2().stream()
                    .filter(field -> name.equals(field.annotationSettingName()))
                    .toList();
                var duplicate = fields.stream().anyMatch(field -> field.duplicate());
                if (!duplicate) {
                    throw new IllegalArgumentException("AnnotationSetting is not duplicated: " + name);
                }
                return new DeleteSettingPreviewVo(name, true, fields, settingBackup(setting),
                    List.of("Deleting a definition does not delete stored metadata.annotations values.",
                        "A plugin or theme restart may recreate this AnnotationSetting.",
                        "MVP deletes the whole AnnotationSetting, not one field from a multi-field setting."));
            });
    }

    @Override
    public Mono<CleanupResultVo> deleteSetting(String name, DeleteSettingRequest request) {
        if (request == null || !name.equals(request.confirmedName())) {
            return Mono.error(new IllegalArgumentException("confirmedName must equal path name."));
        }
        return previewDeleteSetting(name)
            .flatMap(preview -> client.fetch(AnnotationSetting.class, name)
                .flatMap(client::delete)
                .thenReturn(new CleanupResultVo(null, null, 1, preview.backup())));
    }

    @Override
    public Mono<CleanupPreviewVo> previewCleanupValues(CleanupRequest request) {
        validateCleanupRequest(request, false);
        return Mono.zip(valueScanner.scan(new com.webjing.metadatamanager.vo.AnnotationValueScanRequest(
                    request.targetRef(), request.annotationKey(), 10)),
                valueScanner.backup(request.targetRef(), request.annotationKey()))
            .map(tuple -> CleanupPreviewVo.fromUsage(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<CleanupResultVo> cleanupValues(CleanupRequest request) {
        validateCleanupRequest(request, true);
        return previewCleanupValues(request)
            .flatMap(preview -> valueScanner.resourcesWithKey(request.targetRef(), request.annotationKey())
                .cast(AbstractExtension.class)
                .flatMap(resource -> {
                    removeAnnotationKey(resource, request.annotationKey());
                    return client.update(resource);
                })
                .count()
                .map(count -> new CleanupResultVo(request.targetRef(), request.annotationKey(),
                    count.intValue(), preview.backup())));
    }

    private void validateCleanupRequest(CleanupRequest request, boolean requireConfirmation) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (request.targetRef() == null || request.targetRef().isBlank()) {
            throw new IllegalArgumentException("targetRef is required.");
        }
        if (request.annotationKey() == null || request.annotationKey().isBlank()) {
            throw new IllegalArgumentException("annotationKey is required.");
        }
        if (requireConfirmation && !request.annotationKey().equals(request.confirmedAnnotationKey())) {
            throw new IllegalArgumentException("confirmedAnnotationKey must equal annotationKey.");
        }
    }

    private BackupVo settingBackup(AnnotationSetting setting) {
        var item = new LinkedHashMap<String, Object>();
        item.put("apiVersion", setting.getApiVersion());
        item.put("kind", setting.getKind());
        item.put("name", setting.getMetadata().getName());
        item.put("labels", setting.getMetadata().getLabels());
        item.put("annotations", setting.getMetadata().getAnnotations());
        item.put("spec", setting.getSpec());
        return new BackupVo(Instant.now().toString(), "annotation-setting-delete",
            null, null, List.of(item));
    }

    static boolean removeAnnotationKey(AbstractExtension resource, String annotationKey) {
        var annotations = resource.getMetadata().getAnnotations();
        if (annotations == null || !annotations.containsKey(annotationKey)) {
            return false;
        }
        var copied = new LinkedHashMap<>(annotations);
        copied.remove(annotationKey);
        resource.getMetadata().setAnnotations(copied);
        return true;
    }
}
