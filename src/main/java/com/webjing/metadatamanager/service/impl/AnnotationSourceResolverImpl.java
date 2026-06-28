package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationSourceResolver;
import com.webjing.metadatamanager.vo.RuntimeState;
import com.webjing.metadatamanager.vo.SourceInfo;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;

@Component
@RequiredArgsConstructor
public class AnnotationSourceResolverImpl implements AnnotationSourceResolver {

    private static final String PLUGIN_NAME_LABEL = "plugin.halo.run/plugin-name";
    public static final String CUSTOM_FORM_LABEL = "metadata-manager.webjing.com/custom-form";

    private final ReactiveExtensionClient client;

    @Override
    public SourceInfo resolve(AnnotationSetting setting) {
        Map<String, String> labels = Optional.ofNullable(setting.getMetadata().getLabels())
            .orElse(Map.of());
        if ("true".equals(labels.get(CUSTOM_FORM_LABEL))) {
            return new SourceInfo("system", null, "custom");
        }
        var pluginName = labels.get(PLUGIN_NAME_LABEL);
        if (pluginName != null && !pluginName.isBlank()) {
            return new SourceInfo("plugin", pluginName, "label");
        }
        var themeName = labels.get(Theme.THEME_NAME_LABEL);
        if (themeName != null && !themeName.isBlank()) {
            return new SourceInfo("theme", themeName, "label");
        }
        return new SourceInfo("unknown", null, "none");
    }

    @Override
    public Mono<RuntimeState> runtimeState() {
        return Mono.zip(activeThemeName().defaultIfEmpty(""),
                startedPluginNames().defaultIfEmpty(Set.of()))
            .map(tuple -> new RuntimeState(
                tuple.getT1().isBlank() ? null : tuple.getT1(),
                tuple.getT2()
            ));
    }

    @Override
    public boolean isEffective(SourceInfo sourceInfo, RuntimeState runtimeState) {
        if ("theme".equals(sourceInfo.sourceType())) {
            return sourceInfo.sourceName() != null
                && sourceInfo.sourceName().equals(runtimeState.activeThemeName());
        }
        if ("plugin".equals(sourceInfo.sourceType())) {
            return sourceInfo.sourceName() != null
                && runtimeState.startedPluginNames().contains(sourceInfo.sourceName());
        }
        if ("system".equals(sourceInfo.sourceType())) {
            return true;
        }
        return false;
    }

    private Mono<String> activeThemeName() {
        return client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
            .map(ConfigMap::getData)
            .map(data -> SystemSetting.get(data, SystemSetting.Theme.GROUP, SystemSetting.Theme.class))
            .map(SystemSetting.Theme::getActive)
            .onErrorResume(ignored -> Mono.empty());
    }

    private Mono<Set<String>> startedPluginNames() {
        return client.listAll(Plugin.class, ListOptions.builder().build(),
                Sort.by("metadata.name").ascending())
            .filter(plugin -> plugin.getStatus() != null)
            .filter(plugin -> Plugin.Phase.STARTED.equals(plugin.getStatus().getPhase()))
            .map(plugin -> plugin.getMetadata().getName())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
