package com.webjing.metadatamanager.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.core.extension.Theme;
import run.halo.app.extension.Metadata;

class AnnotationSourceResolverImplTest {

    @Test
    void resolvesPluginThemeAndUnknownSourcesFromLabels() {
        var resolver = new AnnotationSourceResolverImpl(null);

        var pluginSetting = settingWithLabels(Map.of("plugin.halo.run/plugin-name", "plugin-a"));
        var themeSetting = settingWithLabels(Map.of(Theme.THEME_NAME_LABEL, "theme-a"));
        var unknownSetting = settingWithLabels(Map.of());

        assertThat(resolver.resolve(pluginSetting).sourceType()).isEqualTo("plugin");
        assertThat(resolver.resolve(pluginSetting).sourceName()).isEqualTo("plugin-a");
        assertThat(resolver.resolve(themeSetting).sourceType()).isEqualTo("theme");
        assertThat(resolver.resolve(themeSetting).sourceName()).isEqualTo("theme-a");
        assertThat(resolver.resolve(unknownSetting).sourceType()).isEqualTo("unknown");
        assertThat(resolver.resolve(unknownSetting).sourceName()).isNull();
    }

    private AnnotationSetting settingWithLabels(Map<String, String> labels) {
        var setting = new AnnotationSetting();
        setting.setMetadata(new Metadata());
        setting.getMetadata().setLabels(labels);
        return setting;
    }
}
