package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationConflictDetector;
import com.webjing.metadatamanager.service.AnnotationFieldParser;
import com.webjing.metadatamanager.service.AnnotationSettingScanner;
import com.webjing.metadatamanager.service.AnnotationSourceResolver;
import com.webjing.metadatamanager.vo.AnnotationConflictVo;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import com.webjing.metadatamanager.vo.AnnotationSettingFormVo;
import com.webjing.metadatamanager.vo.BackupVo;
import com.webjing.metadatamanager.vo.CleanupResultVo;
import com.webjing.metadatamanager.vo.CustomAnnotationSettingRequest;
import com.webjing.metadatamanager.vo.RuntimeState;
import com.webjing.metadatamanager.vo.SourceInfo;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.GroupKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
@RequiredArgsConstructor
public class AnnotationSettingScannerImpl implements AnnotationSettingScanner {

    private static final String PLUGIN_NAME_LABEL = "plugin.halo.run/plugin-name";
    private static final String PLUGIN_NAME = "annotation-manager";
    private static final Set<String> STRING_SAFE_FORMKITS = Set.of(
        "text", "textarea", "select", "radio", "url", "email", "password", "color", "date"
    );

    private final ReactiveExtensionClient client;
    private final AnnotationFieldParser parser;
    private final AnnotationSourceResolver sourceResolver;
    private final AnnotationConflictDetector conflictDetector;

    @Override
    public Mono<List<AnnotationFieldDefinition>> scanFields() {
        return sourceResolver.runtimeState()
                .flatMap(runtimeState -> client.listAll(AnnotationSetting.class,
                    ListOptions.builder().build(), Sort.by("metadata.name").ascending())
                .filter(setting -> setting.getMetadata().getDeletionTimestamp() == null)
                .collectList()
                .map(settings -> {
                    var contexts = settingContexts(settings, runtimeState);
                    var latestSettingNames = latestSettingNamesBySourceTarget(contexts);
                    var fields = contexts.stream()
                        .flatMap(context -> parser.parse(context.setting()).stream()
                            .map(field -> new AnnotationFieldDefinition(
                                field.annotationSettingName(),
                                field.targetRef(),
                                field.annotationKey(),
                                field.label(),
                                field.inputType(),
                                context.sourceInfo().sourceType(),
                                context.sourceInfo().sourceName(),
                                context.effective(),
                                false,
                                context.sourceInfo().confidence(),
                                field.help(),
                                field.validation()
                            )))
                        .toList();
                    return markDuplicates(deduplicateDefinitions(fields), latestSettingNames);
                }));
    }

    @Override
    public Mono<List<AnnotationConflictVo>> scanConflicts() {
        return scanFields().map(conflictDetector::detect);
    }

    @Override
    public Mono<List<AnnotationSettingFormVo>> scanSettingForms(String targetRef) {
        if (targetRef == null || targetRef.isBlank()) {
            return Mono.error(new IllegalArgumentException("targetRef is required."));
        }
        return Mono.zip(
                sourceResolver.runtimeState(),
                pluginDisplayNames(),
                themeDisplayNames()
            )
            .flatMap(tuple -> client.listAll(AnnotationSetting.class,
                    ListOptions.builder().build(), Sort.by("metadata.name").ascending())
                .filter(setting -> setting.getMetadata().getDeletionTimestamp() == null)
                .filter(setting -> targetRef.equals(targetRef(setting)))
                .map(setting -> {
                    var sourceInfo = sourceResolver.resolve(setting);
                    var runtimeState = tuple.getT1();
                    return new AnnotationSettingFormVo(
                        setting.getMetadata().getName(),
                        targetRef,
                        sourceInfo.sourceType(),
                        sourceInfo.sourceName(),
                        sourceDisplayName(sourceInfo.sourceType(), sourceInfo.sourceName(),
                            tuple.getT2(), tuple.getT3()),
                        creationTimestamp(setting),
                        sourceResolver.isEffective(sourceInfo, runtimeState),
                        sourceInfo.confidence(),
                        sourceSubtype(sourceInfo),
                        setting.getSpec() == null || setting.getSpec().getFormSchema() == null
                            ? List.of()
                            : setting.getSpec().getFormSchema()
                    );
                })
                .collectSortedList(Comparator
                    .comparing((AnnotationSettingFormVo form) -> sourceTargetKey(form.targetRef(),
                        form.sourceType(), form.sourceName()))
                    .thenComparing(AnnotationSettingFormVo::creationTimestamp,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(AnnotationSettingFormVo::name, Comparator.nullsLast(String::compareTo))));
    }

    @Override
    public Mono<AnnotationSettingFormVo> createCustomSetting(CustomAnnotationSettingRequest request) {
        return Mono.fromSupplier(() -> {
            validateCustomSettingRequest(request);
            var target = parseTargetRef(request.targetRef());
            var setting = new AnnotationSetting();
            var metadata = new Metadata();
            metadata.setName(settingName(request.name(), request.targetRef(), request.formSchema()));
            metadata.setLabels(Map.of(
                AnnotationSourceResolverImpl.CUSTOM_FORM_LABEL, "true",
                PLUGIN_NAME_LABEL, PLUGIN_NAME
            ));
            setting.setMetadata(metadata);

            var spec = new AnnotationSetting.AnnotationSettingSpec();
            spec.setTargetRef(target);
            spec.setFormSchema(request.formSchema());
            setting.setSpec(spec);
            return setting;
        }).flatMap(client::create)
            .map(setting -> new AnnotationSettingFormVo(
                setting.getMetadata().getName(),
                targetRef(setting),
                "system",
                null,
                null,
                creationTimestamp(setting),
                true,
                "custom",
                "custom",
                setting.getSpec() == null || setting.getSpec().getFormSchema() == null
                    ? List.of()
                    : setting.getSpec().getFormSchema()
            ));
    }

    @Override
    public Mono<AnnotationSettingFormVo> updateCustomSetting(String name,
        CustomAnnotationSettingRequest request) {
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("name is required."));
        }
        return client.fetch(AnnotationSetting.class, name)
            .map(setting -> {
                assertMutableCustomSetting(setting);
                validateCustomSettingRequest(request);
                var spec = Optional.ofNullable(setting.getSpec())
                    .orElseGet(AnnotationSetting.AnnotationSettingSpec::new);
                spec.setTargetRef(parseTargetRef(request.targetRef()));
                spec.setFormSchema(request.formSchema());
                setting.setSpec(spec);
                ensureCustomLabels(setting);
                return setting;
            })
            .flatMap(client::update)
            .map(this::customSettingFormVo);
    }

    @Override
    public Mono<CleanupResultVo> deleteCustomSetting(String name) {
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("name is required."));
        }
        return client.fetch(AnnotationSetting.class, name)
            .map(setting -> {
                assertMutableCustomSetting(setting);
                return setting;
            })
            .flatMap(setting -> {
                var backup = customSettingBackup(setting);
                return client.delete(setting)
                    .thenReturn(new CleanupResultVo(targetRef(setting), null, 1, backup));
            });
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

    private Mono<Map<String, String>> pluginDisplayNames() {
        return client.listAll(Plugin.class, ListOptions.builder().build(),
                Sort.by("metadata.name").ascending())
            .collectMap(plugin -> plugin.getMetadata().getName(), this::pluginDisplayName)
            .onErrorReturn(Map.of());
    }

    private String pluginDisplayName(Plugin plugin) {
        var metadataName = plugin.getMetadata().getName();
        return Optional.ofNullable(plugin.getSpec())
            .map(Plugin.PluginSpec::getDisplayName)
            .filter(value -> !value.isBlank())
            .orElse(metadataName);
    }

    private Mono<Map<String, String>> themeDisplayNames() {
        return client.listAll(Theme.class, ListOptions.builder().build(),
                Sort.by("metadata.name").ascending())
            .collectMap(theme -> theme.getMetadata().getName(), this::themeDisplayName)
            .onErrorReturn(Map.of());
    }

    private String themeDisplayName(Theme theme) {
        var metadataName = theme.getMetadata().getName();
        return Optional.ofNullable(theme.getSpec())
            .map(Theme.ThemeSpec::getDisplayName)
            .filter(value -> !value.isBlank())
            .orElse(metadataName);
    }

    private String sourceDisplayName(String sourceType, String sourceName,
        Map<String, String> pluginDisplayNames, Map<String, String> themeDisplayNames) {
        if (sourceName == null || sourceName.isBlank()) {
            return null;
        }
        if ("plugin".equals(sourceType)) {
            return pluginDisplayNames.getOrDefault(sourceName, sourceName);
        }
        if ("theme".equals(sourceType)) {
            return themeDisplayNames.getOrDefault(sourceName, sourceName);
        }
        return sourceName;
    }

    private String sourceSubtype(SourceInfo sourceInfo) {
        return "custom".equals(sourceInfo.confidence()) ? "custom" : "system";
    }

    private AnnotationSettingFormVo customSettingFormVo(AnnotationSetting setting) {
        return new AnnotationSettingFormVo(
            setting.getMetadata().getName(),
            targetRef(setting),
            "system",
            null,
            null,
            creationTimestamp(setting),
            true,
            "custom",
            "custom",
            setting.getSpec() == null || setting.getSpec().getFormSchema() == null
                ? List.of()
                : setting.getSpec().getFormSchema()
        );
    }

    private void assertMutableCustomSetting(AnnotationSetting setting) {
        if (setting == null || setting.getMetadata() == null) {
            throw new IllegalArgumentException("AnnotationSetting not found.");
        }
        if (setting.getMetadata().getDeletionTimestamp() != null) {
            throw new IllegalArgumentException("AnnotationSetting is already deleting.");
        }
        var labels = Optional.ofNullable(setting.getMetadata().getLabels()).orElse(Map.of());
        if (!"true".equals(labels.get(AnnotationSourceResolverImpl.CUSTOM_FORM_LABEL))
            || !PLUGIN_NAME.equals(labels.get(PLUGIN_NAME_LABEL))) {
            throw new IllegalArgumentException(
                "Only custom AnnotationSettings created by annotation-manager can be modified.");
        }
    }

    private void ensureCustomLabels(AnnotationSetting setting) {
        var metadata = setting.getMetadata();
        var labels = new LinkedHashMap<>(Optional.ofNullable(metadata.getLabels()).orElse(Map.of()));
        labels.put(AnnotationSourceResolverImpl.CUSTOM_FORM_LABEL, "true");
        labels.put(PLUGIN_NAME_LABEL, PLUGIN_NAME);
        metadata.setLabels(labels);
    }

    private BackupVo customSettingBackup(AnnotationSetting setting) {
        var item = new LinkedHashMap<String, Object>();
        item.put("apiVersion", setting.getApiVersion());
        item.put("kind", setting.getKind());
        item.put("name", setting.getMetadata().getName());
        item.put("labels", setting.getMetadata().getLabels());
        item.put("targetRef", targetRef(setting));
        item.put("formSchema", setting.getSpec() == null ? List.of() : setting.getSpec().getFormSchema());
        return new BackupVo(
            Instant.now().toString(),
            "custom-annotation-setting-delete",
            targetRef(setting),
            null,
            List.of(item)
        );
    }

    private void validateCustomSettingRequest(CustomAnnotationSettingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        parseTargetRef(request.targetRef());
        if (request.formSchema() == null || request.formSchema().isEmpty()) {
            throw new IllegalArgumentException("formSchema must contain at least one field.");
        }
        var fieldNames = new HashSet<String>();
        collectAndValidateSchemaFields(request.formSchema(), fieldNames);
        if (fieldNames.isEmpty()) {
            throw new IllegalArgumentException("formSchema must contain fields with $formkit and name.");
        }
        if (request.name() != null && !request.name().isBlank()
            && !request.name().matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException("AnnotationSetting name must use lowercase letters, numbers, and hyphens.");
        }
    }

    @SuppressWarnings("unchecked")
    private void collectAndValidateSchemaFields(Object node, Set<String> fieldNames) {
        if (node instanceof List<?> list) {
            list.forEach(item -> collectAndValidateSchemaFields(item, fieldNames));
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        if (map.containsKey("$formkit") || map.containsKey("name")) {
            var formkit = Optional.ofNullable(map.get("$formkit")).map(Object::toString).orElse("");
            var name = Optional.ofNullable(map.get("name")).map(Object::toString).orElse("");
            validateFieldNode(formkit, name, fieldNames);
        }
        map.values().forEach(value -> collectAndValidateSchemaFields(value, fieldNames));
    }

    private void validateFieldNode(String formkit, String name, Set<String> fieldNames) {
        if (formkit.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("Every custom field must include both $formkit and name.");
        }
        if (!STRING_SAFE_FORMKITS.contains(formkit)) {
            throw new IllegalArgumentException("$formkit " + formkit
                + " is not supported for metadata annotations. Use string-safe inputs only.");
        }
        if (!name.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")) {
            throw new IllegalArgumentException("Field name " + name
                + " contains unsupported characters.");
        }
        if (!fieldNames.add(name)) {
            throw new IllegalArgumentException("Duplicate field name: " + name);
        }
    }

    private GroupKind parseTargetRef(String targetRef) {
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef is required.");
        }
        var normalizedTargetRef = normalizeTargetRef(targetRef);
        var parts = normalizedTargetRef.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("targetRef must use <group>/<kind> format.");
        }
        return new GroupKind(parts[0], parts[1]);
    }

    private String normalizeTargetRef(String targetRef) {
        var trimmed = targetRef.trim();
        if (trimmed.contains("/") && !trimmed.contains("(")) {
            return trimmed;
        }
        var start = trimmed.lastIndexOf('(');
        var end = trimmed.lastIndexOf(')');
        if (start >= 0 && end > start) {
            var candidate = trimmed.substring(start + 1, end).trim();
            if (candidate.contains("/")) {
                return candidate;
            }
        }
        return trimmed;
    }

    private String settingName(String requestedName, String targetRef, List<Object> formSchema) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName;
        }
        var target = parseTargetRef(targetRef);
        var firstField = firstFieldName(formSchema).orElse("field");
        var base = ("annotation-manager-custom-" + target.kind() + "-" + firstField)
            .toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-+", "-")
            .replaceAll("(^-|-$)", "");
        var suffix = Long.toString(System.currentTimeMillis(), 36);
        var maxBaseLength = Math.max(1, 62 - suffix.length());
        if (base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength).replaceAll("-$", "");
        }
        return base + "-" + suffix;
    }

    private Optional<String> firstFieldName(Object node) {
        if (node instanceof List<?> list) {
            return list.stream().map(this::firstFieldName).filter(Optional::isPresent)
                .map(Optional::get).findFirst();
        }
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("$formkit") && map.containsKey("name")) {
                return Optional.ofNullable(map.get("name")).map(Object::toString);
            }
            return map.values().stream().map(this::firstFieldName).filter(Optional::isPresent)
                .map(Optional::get).findFirst();
        }
        return Optional.empty();
    }

    private List<AnnotationFieldDefinition> markDuplicates(List<AnnotationFieldDefinition> fields,
        Set<String> latestSettingNames) {
        Map<String, Long> globalFieldCounts = fields.stream()
            .collect(Collectors.groupingBy(AnnotationFieldDefinition::conflictKey, Collectors.counting()));
        Map<String, Long> globalSourceCounts = fields.stream()
            .collect(Collectors.groupingBy(AnnotationFieldDefinition::conflictKey,
                Collectors.mapping(this::sourceIdentity, Collectors.collectingAndThen(Collectors.toSet(),
                    set -> (long) set.size()))));
        return fields.stream()
            .map(field -> field.withDuplicate(
                isGlobalCrossSourceConflict(field, globalFieldCounts, globalSourceCounts)
                    || isSupersededSourceDefinition(field, latestSettingNames)))
            .toList();
    }

    private boolean isGlobalCrossSourceConflict(AnnotationFieldDefinition field,
        Map<String, Long> globalFieldCounts, Map<String, Long> globalSourceCounts) {
        return globalFieldCounts.getOrDefault(field.conflictKey(), 0L) > 1
            && globalSourceCounts.getOrDefault(field.conflictKey(), 0L) > 1;
    }

    private boolean isSupersededSourceDefinition(AnnotationFieldDefinition field,
        Set<String> latestSettingNames) {
        return !latestSettingNames.contains(field.annotationSettingName());
    }

    private String sourceIdentity(AnnotationFieldDefinition field) {
        return field.sourceType() + "\n" + field.sourceName();
    }

    private List<SettingContext> settingContexts(List<AnnotationSetting> settings,
        RuntimeState runtimeState) {
        return settings.stream()
            .map(setting -> {
                var sourceInfo = sourceResolver.resolve(setting);
                return new SettingContext(setting, targetRef(setting), sourceInfo,
                    sourceResolver.isEffective(sourceInfo, runtimeState));
            })
            .toList();
    }

    private Set<String> latestSettingNamesBySourceTarget(List<SettingContext> contexts) {
        return contexts.stream()
            .collect(Collectors.groupingBy(context -> sourceTargetKey(context.targetRef(),
                context.sourceInfo().sourceType(), context.sourceInfo().sourceName())))
            .values()
            .stream()
            .map(this::latestSetting)
            .map(context -> context.setting().getMetadata().getName())
            .collect(Collectors.toUnmodifiableSet());
    }

    private SettingContext latestSetting(List<SettingContext> contexts) {
        return contexts.stream()
            .max(Comparator.comparing((SettingContext context) -> creationInstant(context.setting()),
                    Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(context -> context.setting().getMetadata().getName(),
                    Comparator.nullsFirst(String::compareTo)))
            .orElseThrow();
    }

    private String sourceTargetKey(String targetRef, String sourceType, String sourceName) {
        return targetRef + "\n" + sourceType + "\n" + sourceName;
    }

    private Instant creationInstant(AnnotationSetting setting) {
        return setting.getMetadata() == null ? null : setting.getMetadata().getCreationTimestamp();
    }

    private String creationTimestamp(AnnotationSetting setting) {
        var creationTimestamp = creationInstant(setting);
        return creationTimestamp == null ? null : creationTimestamp.toString();
    }

    private String targetRef(AnnotationSetting setting) {
        if (setting.getSpec() == null || setting.getSpec().getTargetRef() == null) {
            return "/";
        }
        var targetRef = setting.getSpec().getTargetRef();
        return targetRef.group() + "/" + targetRef.kind();
    }

    private record SettingContext(AnnotationSetting setting, String targetRef, SourceInfo sourceInfo,
                                  boolean effective) {
    }
}
