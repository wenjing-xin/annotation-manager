package com.webjing.metadatamanager.service.impl;

import com.webjing.metadatamanager.service.AnnotationFieldParser;
import com.webjing.metadatamanager.vo.ParsedAnnotationField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.AnnotationSetting;
import run.halo.app.extension.GroupKind;

@Component
public class AnnotationFieldParserImpl implements AnnotationFieldParser {

    @Override
    public List<ParsedAnnotationField> parse(AnnotationSetting setting) {
        var fields = new ArrayList<ParsedAnnotationField>();
        var spec = setting.getSpec();
        if (spec == null || spec.getFormSchema() == null) {
            return fields;
        }
        spec.getFormSchema().forEach(node -> collectFields(setting, node, fields));
        return fields;
    }

    private void collectFields(AnnotationSetting setting, Object node,
        List<ParsedAnnotationField> fields) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("$formkit") && map.containsKey("name")) {
                fields.add(toField(setting, map));
            }
            map.values().forEach(value -> collectFields(setting, value, fields));
            return;
        }
        if (node instanceof List<?> list) {
            list.forEach(item -> collectFields(setting, item, fields));
        }
    }

    private ParsedAnnotationField toField(AnnotationSetting setting, Map<?, ?> map) {
        var key = stringValue(map.get("name"));
        return new ParsedAnnotationField(
            setting.getMetadata().getName(),
            targetRef(setting),
            key,
            stringValue(map.containsKey("label") ? map.get("label") : key),
            stringValue(map.get("$formkit")),
            stringValue(map.get("help")),
            stringValue(map.get("validation"))
        );
    }

    private String targetRef(AnnotationSetting setting) {
        var spec = setting.getSpec();
        GroupKind targetRef = spec == null ? null : spec.getTargetRef();
        if (targetRef == null) {
            return "unknown/unknown";
        }
        return targetRef.group() + "/" + targetRef.kind();
    }

    private String stringValue(Object value) {
        return value == null ? null : Objects.toString(value, null);
    }
}
