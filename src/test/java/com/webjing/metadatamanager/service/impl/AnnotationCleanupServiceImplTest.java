package com.webjing.metadatamanager.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.webjing.metadatamanager.service.AnnotationSettingScanner;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.GroupKind;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class AnnotationCleanupServiceImplTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    AnnotationSettingScanner scanner;

    @Test
    void previewDeleteRejectsNonDuplicateSetting() {
        var setting = annotationSetting("setting-a", "plugin-a", "2026-01-01T00:00:00Z");
        when(client.fetch(eq(AnnotationSetting.class), eq("setting-a"))).thenReturn(Mono.just(setting));
        when(client.listAll(eq(AnnotationSetting.class), any(), any()))
            .thenReturn(Flux.just(setting));
        when(scanner.scanFields()).thenReturn(Mono.just(List.of(
            new AnnotationFieldDefinition("setting-a", "content.halo.run/Post", "copyright",
                "Copyright", "text", "plugin", "plugin-a", true, false, "label", null, null)
        )));

        var service = new AnnotationCleanupServiceImpl(client, scanner, null);

        assertThatThrownBy(() -> service.previewDeleteSetting("setting-a").block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not duplicated");
    }

    @Test
    void previewDeleteAllowsExtraSettingFromSameSourceAndTarget() {
        var olderSetting = annotationSetting("setting-b", "plugin-a", "2026-01-01T00:00:00Z");
        var newerSetting = annotationSetting("setting-a", "plugin-a", "2026-01-02T00:00:00Z");
        when(client.fetch(eq(AnnotationSetting.class), eq("setting-b"))).thenReturn(Mono.just(olderSetting));
        when(client.listAll(eq(AnnotationSetting.class), any(), any()))
            .thenReturn(Flux.just(olderSetting, newerSetting));
        when(scanner.scanFields()).thenReturn(Mono.just(List.of(
            new AnnotationFieldDefinition("setting-a", "content.halo.run/Post", "copyright",
                "Copyright", "text", "plugin", "plugin-a", true, false, "label", null, null),
            new AnnotationFieldDefinition("setting-b", "content.halo.run/Post", "summary",
                "Summary", "text", "plugin", "plugin-a", true, false, "label", null, null)
        )));

        var service = new AnnotationCleanupServiceImpl(client, scanner, null);

        var preview = service.previewDeleteSetting("setting-b").block();

        assertThat(preview).isNotNull();
        assertThat(preview.duplicate()).isTrue();
        assertThat(preview.definitions()).extracting(AnnotationFieldDefinition::annotationKey)
            .containsExactly("summary");
        assertThat(preview.definitions()).extracting(AnnotationFieldDefinition::duplicate)
            .containsExactly(true);
    }

    @Test
    void removeAnnotationKeyRemovesExactKeyOnlyAndKeepsLabels() {
        var post = new Post();
        post.setMetadata(new Metadata());
        post.getMetadata().setLabels(Map.of("existing-label", "keep"));
        var annotations = new LinkedHashMap<String, String>();
        annotations.put("foo/bar", "remove");
        annotations.put("foo", "keep");
        annotations.put("foo/bar/baz", "keep");
        post.getMetadata().setAnnotations(annotations);

        var removed = AnnotationCleanupServiceImpl.removeAnnotationKey(post, "foo/bar");

        assertThat(removed).isTrue();
        assertThat(post.getMetadata().getLabels()).containsEntry("existing-label", "keep");
        assertThat(post.getMetadata().getAnnotations())
            .doesNotContainKey("foo/bar")
            .containsEntry("foo", "keep")
            .containsEntry("foo/bar/baz", "keep");
    }

    @Test
    void replaceAnnotationsOnlyDoesNotTouchLabels() {
        var post = new Post();
        post.setMetadata(new Metadata());
        post.getMetadata().setLabels(Map.of("existing-label", "keep"));
        post.getMetadata().setAnnotations(Map.of("old", "remove"));

        var next = AnnotationValueScannerImpl.replaceAnnotationsOnly(post,
            Map.of("new", "value", "empty", ""));

        assertThat(next)
            .containsEntry("new", "value")
            .containsEntry("empty", "")
            .doesNotContainKey("old");
        assertThat(post.getMetadata().getLabels()).containsEntry("existing-label", "keep");
        assertThat(post.getMetadata().getAnnotations())
            .containsEntry("new", "value")
            .containsEntry("empty", "")
            .doesNotContainKey("old");
    }

    private AnnotationSetting annotationSetting(String name, String pluginName, String creationTimestamp) {
        var setting = new AnnotationSetting();
        setting.setMetadata(new Metadata());
        setting.getMetadata().setName(name);
        setting.getMetadata().setCreationTimestamp(Instant.parse(creationTimestamp));
        setting.getMetadata().setLabels(Map.of("plugin.halo.run/plugin-name", pluginName));
        var spec = new AnnotationSetting.AnnotationSettingSpec();
        spec.setTargetRef(new GroupKind("content.halo.run", "Post"));
        setting.setSpec(spec);
        return setting;
    }
}
