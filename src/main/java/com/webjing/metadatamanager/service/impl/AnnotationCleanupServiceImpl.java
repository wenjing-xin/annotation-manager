package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationCleanupService;
import com.webjing.metadatamanager.service.AnnotationSettingScanner;
import com.webjing.metadatamanager.service.AnnotationValueScanner;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import com.webjing.metadatamanager.vo.BackupVo;
import com.webjing.metadatamanager.vo.CleanupPreviewVo;
import com.webjing.metadatamanager.vo.CleanupRequest;
import com.webjing.metadatamanager.vo.CleanupResultVo;
import com.webjing.metadatamanager.vo.DeleteSettingPreviewVo;
import com.webjing.metadatamanager.vo.DeleteSettingRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
@RequiredArgsConstructor
public class AnnotationCleanupServiceImpl implements AnnotationCleanupService {

    private static final String PLUGIN_NAME_LABEL = "plugin.halo.run/plugin-name";

    private final ReactiveExtensionClient client;
    private final AnnotationSettingScanner settingScanner;
    private final AnnotationValueScanner valueScanner;

    @Override
    public Mono<DeleteSettingPreviewVo> previewDeleteSetting(String name) {
        return client.fetch(AnnotationSetting.class, name)
            .flatMap(setting -> Mono.zip(settingScanner.scanFields(),
                    isSupersededByNewerSourceTargetSetting(setting))
                .map(tuple -> {
                    if (setting.getMetadata().getDeletionTimestamp() != null) {
                        throw new IllegalArgumentException("AnnotationSetting is already deleting: " + name);
                    }
                    var fields = tuple.getT1().stream()
                        .filter(field -> name.equals(field.annotationSettingName()))
                        .toList();
                    var sourceTargetDuplicate = tuple.getT2();
                    var duplicate = fields.stream().anyMatch(field -> field.duplicate())
                        || sourceTargetDuplicate;
                    if (!duplicate) {
                        throw new IllegalArgumentException("AnnotationSetting is not duplicated: " + name);
                    }
                    var previewFields = sourceTargetDuplicate
                        ? fields.stream().map(field -> field.withDuplicate(true)).toList()
                        : fields;
                    return new DeleteSettingPreviewVo(name, true, previewFields,
                        settingBackup(setting),
                        List.of(
                            "Deleting a definition does not delete stored metadata.annotations values.",
                            "A plugin or theme restart may recreate this AnnotationSetting.",
                            "Only the latest AnnotationSetting is kept for the same source and target model.",
                            "MVP deletes the whole AnnotationSetting, not one field from a multi-field setting."));
                }));
    }

    private Mono<Boolean> isSupersededByNewerSourceTargetSetting(AnnotationSetting setting) {
        var sourceTargetKey = sourceTargetKey(setting);
        var currentName = setting.getMetadata().getName();
        return client.listAll(AnnotationSetting.class, ListOptions.builder().build(),
                Sort.by("metadata.name").ascending())
            .filter(candidate -> candidate.getMetadata().getDeletionTimestamp() == null)
            .filter(candidate -> !Objects.equals(currentName, candidate.getMetadata().getName()))
            .filter(candidate -> Objects.equals(sourceTargetKey, sourceTargetKey(candidate)))
            .any(candidate -> compareRecency(candidate, setting) > 0);
    }

    private int compareRecency(AnnotationSetting left, AnnotationSetting right) {
        return Comparator.comparing(this::creationInstant,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(setting -> setting.getMetadata().getName(),
                Comparator.nullsFirst(String::compareTo))
            .compare(left, right);
    }

    private Instant creationInstant(AnnotationSetting setting) {
        return setting.getMetadata() == null ? null : setting.getMetadata().getCreationTimestamp();
    }

    private String sourceTargetKey(AnnotationSetting setting) {
        return targetRef(setting) + "\n" + sourceKey(setting);
    }

    private String sourceKey(AnnotationSetting setting) {
        var labels = setting.getMetadata().getLabels();
        if (labels == null) {
            return "unknown\n";
        }
        var pluginName = labels.get(PLUGIN_NAME_LABEL);
        if (pluginName != null && !pluginName.isBlank()) {
            return "plugin\n" + pluginName;
        }
        var themeName = labels.get(Theme.THEME_NAME_LABEL);
        if (themeName != null && !themeName.isBlank()) {
            return "theme\n" + themeName;
        }
        return "unknown\n";
    }

    private String targetRef(AnnotationSetting setting) {
        if (setting.getSpec() == null || setting.getSpec().getTargetRef() == null) {
            return "/";
        }
        var targetRef = setting.getSpec().getTargetRef();
        return targetRef.group() + "/" + targetRef.kind();
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
