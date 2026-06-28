package com.webjing.metadatamanager.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnotationConflictDetectorImplTest {

    private final AnnotationConflictDetectorImpl detector = new AnnotationConflictDetectorImpl();

    @Test
    void detectsDuplicatesByTargetRefAndAnnotationKey() {
        var fields = List.of(
            field("plugin-setting", "content.halo.run/Post", "copyright", true, true),
            field("theme-setting", "content.halo.run/Post", "copyright", false, true),
            field("other-setting", "content.halo.run/Post", "summary", true, false)
        );

        var conflicts = detector.detect(fields);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).targetRef()).isEqualTo("content.halo.run/Post");
        assertThat(conflicts.get(0).annotationKey()).isEqualTo("copyright");
        assertThat(conflicts.get(0).definitions()).extracting("annotationSettingName")
            .containsExactly("plugin-setting", "theme-setting");
    }

    @Test
    void returnsOnlyDuplicateDefinitionsAndKeepsLatestDefinitionClean() {
        var fields = List.of(
            field("latest-setting", "content.halo.run/Post", "copyright", true, false),
            field("old-setting", "content.halo.run/Post", "copyright", true, true)
        );

        var conflicts = detector.detect(fields);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).definitions()).extracting("annotationSettingName")
            .containsExactly("old-setting");
    }

    private AnnotationFieldDefinition field(String settingName, String targetRef, String key,
        boolean effective, boolean duplicate) {
        return new AnnotationFieldDefinition(settingName, targetRef, key, key, "text",
            "plugin", "source", effective, duplicate, "label", null, null);
    }
}
