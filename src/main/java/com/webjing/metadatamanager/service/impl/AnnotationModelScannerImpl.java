package com.webjing.metadatamanager.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webjing.metadatamanager.service.AnnotationModelScanner;
import com.webjing.metadatamanager.vo.AnnotationModelVo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;

@Component
public class AnnotationModelScannerImpl implements AnnotationModelScanner {

    private final SchemeManager schemeManager;
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnnotationModelScannerImpl(SchemeManager schemeManager, ReactiveExtensionClient client) {
        this.schemeManager = schemeManager;
        this.client = client;
    }

    @Override
    public Mono<List<AnnotationModelVo>> scanModels() {
        return pluginDisplayNames()
            .map(pluginDisplayNames -> schemeManager.schemes().stream()
                .map(scheme -> toVo(scheme, pluginDisplayNames))
                .sorted(Comparator.comparing(AnnotationModelVo::sourceType)
                    .thenComparing(vo -> nullSafe(vo.sourceDisplayName()))
                    .thenComparing(AnnotationModelVo::group)
                    .thenComparing(AnnotationModelVo::kind))
                .toList());
    }

    private AnnotationModelVo toVo(Scheme scheme, Map<String, String> pluginDisplayNames) {
        var gvk = scheme.groupVersionKind();
        var source = resolveSource(scheme.type(), pluginDisplayNames);
        var description = modelDescription(scheme);
        var group = gvk.group();
        var version = gvk.version();
        return new AnnotationModelVo(
            group + "/" + gvk.kind(),
            group.isBlank() ? version : group + "/" + version,
            group,
            version,
            gvk.kind(),
            scheme.plural(),
            scheme.singular(),
            source.sourceType(),
            source.sourceName(),
            source.sourceDisplayName(),
            source.confidence(),
            scheme.type().getName(),
            description.text(),
            description.source(),
            supportsValueScan(scheme)
        );
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

    private ModelSource resolveSource(Class<?> type, Map<String, String> pluginDisplayNames) {
        var className = type.getName();
        if (className.startsWith("run.halo.app.")) {
            return new ModelSource("system", "Halo", "Halo", "class-package");
        }

        var pluginName = pluginNameFromClassLoader(type.getClassLoader());
        if (pluginName != null && !pluginName.isBlank()) {
            return new ModelSource("plugin", pluginName,
                pluginDisplayNames.getOrDefault(pluginName, pluginName), "plugin-classloader");
        }

        var packageName = packageName(className);
        return new ModelSource("unknown", packageName, packageName, "class-package");
    }

    private ModelDescription modelDescription(Scheme scheme) {
        return schemaDescription(scheme.type())
            .map(description -> new ModelDescription(description, "schema-annotation"))
            .or(() -> openApiDescription(scheme).map(description ->
                new ModelDescription(description, "openapi-schema")))
            .or(() -> javadocDescription(scheme.type()).map(description ->
                new ModelDescription(description, "javadoc")))
            .orElse(new ModelDescription(null, null));
    }

    private Optional<String> schemaDescription(Class<?> type) {
        return Optional.ofNullable(type.getAnnotation(Schema.class))
            .map(Schema::description)
            .filter(value -> !value.isBlank());
    }

    private Optional<String> openApiDescription(Scheme scheme) {
        return Optional.ofNullable(scheme.openApiSchema())
            .map(schema -> schema.path("description").asText(null))
            .filter(value -> !value.isBlank());
    }

    private Optional<String> javadocDescription(Class<?> type) {
        var resourceName = type.getName().replace('.', '/') + "__Javadoc.json";
        try (InputStream inputStream = type.getClassLoader().getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                return Optional.empty();
            }
            var root = objectMapper.readTree(inputStream);
            return Optional.ofNullable(root.path("doc").asText(null))
                .map(this::cleanJavadoc)
                .filter(value -> !value.isBlank());
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String cleanJavadoc(String doc) {
        return doc.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .filter(line -> !line.startsWith("@"))
            .filter(line -> !line.startsWith("<p>"))
            .map(line -> line.replaceAll("<[^>]+>", ""))
            .findFirst()
            .orElse("");
    }

    private String pluginNameFromClassLoader(ClassLoader classLoader) {
        if (classLoader == null || !classLoader.getClass().getName().contains("PluginClassLoader")) {
            return null;
        }
        try {
            Field field = classLoader.getClass().getDeclaredField("pluginDescriptor");
            field.setAccessible(true);
            var descriptor = field.get(classLoader);
            if (descriptor == null) {
                return null;
            }
            var getPluginId = descriptor.getClass().getMethod("getPluginId");
            return (String) getPluginId.invoke(descriptor);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private String packageName(String className) {
        var lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(0, lastDot) : null;
    }

    private boolean supportsValueScan(Scheme scheme) {
        return run.halo.app.extension.Extension.class.isAssignableFrom(scheme.type());
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record ModelSource(String sourceType, String sourceName, String sourceDisplayName,
                               String confidence) {
    }

    private record ModelDescription(String text, String source) {
    }
}
