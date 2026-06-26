package com.webjing.metadatamanager.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.extension.GroupKind;
import run.halo.app.extension.Metadata;

class AnnotationFieldParserImplTest {

    private final AnnotationFieldParserImpl parser = new AnnotationFieldParserImpl();

    @Test
    void parsesNestedFormSchemaNodes() {
        var setting = new AnnotationSetting();
        setting.setMetadata(new Metadata());
        setting.getMetadata().setName("setting-a");
        var spec = new AnnotationSetting.AnnotationSettingSpec();
        spec.setTargetRef(new GroupKind("content.halo.run", "Post"));
        spec.setFormSchema(List.of(
            Map.of(
                "$formkit", "group",
                "name", "outer",
                "children", List.of(
                    Map.of("$formkit", "text", "name", "copyright", "label", "Copyright"),
                    Map.of("if", "$enabled", "then", Map.of("$formkit", "select", "name", "license"))
                )
            )
        ));
        setting.setSpec(spec);

        var fields = parser.parse(setting);

        assertThat(fields).extracting("annotationKey")
            .containsExactly("outer", "copyright", "license");
        assertThat(fields).allMatch(field -> "setting-a".equals(field.annotationSettingName()));
        assertThat(fields).allMatch(field -> "content.halo.run/Post".equals(field.targetRef()));
    }
}
