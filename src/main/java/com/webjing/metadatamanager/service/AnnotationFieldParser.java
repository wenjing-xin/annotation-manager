package com.webjing.metadatamanager.service;

import com.webjing.metadatamanager.vo.ParsedAnnotationField;
import java.util.List;
import run.halo.app.core.extension.AnnotationSetting;

public interface AnnotationFieldParser {
    List<ParsedAnnotationField> parse(AnnotationSetting setting);
}
