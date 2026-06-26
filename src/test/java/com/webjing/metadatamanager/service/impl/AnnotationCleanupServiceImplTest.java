package com.webjing.metadatamanager.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.webjing.metadatamanager.service.AnnotationSettingScanner;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.core.extension.content.Post;
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
        var setting = new AnnotationSetting();
        setting.setMetadata(new Metadata());
        setting.getMetadata().setName("setting-a");
        when(client.fetch(eq(AnnotationSetting.class), eq("setting-a"))).thenReturn(Mono.just(setting));
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
}
