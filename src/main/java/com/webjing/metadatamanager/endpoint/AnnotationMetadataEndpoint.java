package com.webjing.metadatamanager.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import com.webjing.metadatamanager.service.AnnotationCleanupService;
import com.webjing.metadatamanager.service.AnnotationSettingScanner;
import com.webjing.metadatamanager.service.AnnotationValueScanner;
import com.webjing.metadatamanager.vo.AnnotationConflictVo;
import com.webjing.metadatamanager.vo.AnnotationFieldDefinition;
import com.webjing.metadatamanager.vo.AnnotationValueScanRequest;
import com.webjing.metadatamanager.vo.AnnotationValueUsageVo;
import com.webjing.metadatamanager.vo.CleanupPreviewVo;
import com.webjing.metadatamanager.vo.CleanupRequest;
import com.webjing.metadatamanager.vo.CleanupResultVo;
import com.webjing.metadatamanager.vo.DeleteSettingPreviewVo;
import com.webjing.metadatamanager.vo.DeleteSettingRequest;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class AnnotationMetadataEndpoint implements CustomEndpoint {

    private final AnnotationSettingScanner settingScanner;
    private final AnnotationValueScanner valueScanner;
    private final AnnotationCleanupService cleanupService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.annotation-manager.wenjing.com/v1alpha1/AnnotationMetadata";
        return SpringdocRouteBuilder.route()
            .GET("/annotation-fields", this::listFields,
                builder -> builder.operationId("ListAnnotationFields")
                    .description("List parsed annotation metadata field definitions.")
                    .tag(tag)
                    .response(responseBuilder().implementationArray(AnnotationFieldDefinition.class)))
            .GET("/annotation-conflicts", this::listConflicts,
                builder -> builder.operationId("ListAnnotationConflicts")
                    .description("List duplicate annotation metadata field definitions.")
                    .tag(tag)
                    .response(responseBuilder().implementationArray(AnnotationConflictVo.class)))
            .POST("/annotation-values/scan", this::scanValues,
                builder -> builder.operationId("ScanAnnotationValues")
                    .description("Scan stored metadata.annotations values on supported content models.")
                    .tag(tag)
                    .requestBody(requestBodyBuilder().implementation(AnnotationValueScanRequest.class))
                    .response(responseBuilder().implementation(AnnotationValueUsageVo.class)))
            .POST("/annotation-settings/{name}/delete-preview", this::previewDeleteSetting,
                builder -> builder.operationId("PreviewDeleteAnnotationSetting")
                    .description("Preview deleting a duplicate AnnotationSetting.")
                    .tag(tag)
                    .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true))
                    .response(responseBuilder().implementation(DeleteSettingPreviewVo.class)))
            .POST("/annotation-settings/{name}/delete", this::deleteSetting,
                builder -> builder.operationId("DeleteDuplicateAnnotationSetting")
                    .description("Delete a duplicate AnnotationSetting after explicit confirmation.")
                    .tag(tag)
                    .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true))
                    .requestBody(requestBodyBuilder().implementation(DeleteSettingRequest.class))
                    .response(responseBuilder().implementation(CleanupResultVo.class)))
            .POST("/annotation-values/cleanup-preview", this::previewCleanupValues,
                builder -> builder.operationId("PreviewCleanupAnnotationValues")
                    .description("Preview cleanup of one stored annotation key on supported content models.")
                    .tag(tag)
                    .requestBody(requestBodyBuilder().implementation(CleanupRequest.class))
                    .response(responseBuilder().implementation(CleanupPreviewVo.class)))
            .POST("/annotation-values/cleanup", this::cleanupValues,
                builder -> builder.operationId("CleanupAnnotationValues")
                    .description("Clean one stored annotation key after explicit confirmation.")
                    .tag(tag)
                    .requestBody(requestBodyBuilder().implementation(CleanupRequest.class))
                    .response(responseBuilder().implementation(CleanupResultVo.class)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.annotation-manager.wenjing.com/v1alpha1");
    }

    private Mono<ServerResponse> listFields(ServerRequest request) {
        return settingScanner.scanFields()
            .flatMap(fields -> ServerResponse.ok().bodyValue(fields))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> listConflicts(ServerRequest request) {
        return settingScanner.scanConflicts()
            .flatMap(conflicts -> ServerResponse.ok().bodyValue(conflicts))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> scanValues(ServerRequest request) {
        return request.bodyToMono(AnnotationValueScanRequest.class)
            .flatMap(valueScanner::scan)
            .flatMap(usage -> ServerResponse.ok().bodyValue(usage))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> previewDeleteSetting(ServerRequest request) {
        return cleanupService.previewDeleteSetting(request.pathVariable("name"))
            .flatMap(preview -> ServerResponse.ok().bodyValue(preview))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> deleteSetting(ServerRequest request) {
        var name = request.pathVariable("name");
        return request.bodyToMono(DeleteSettingRequest.class)
            .flatMap(body -> cleanupService.deleteSetting(name, body))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> previewCleanupValues(ServerRequest request) {
        return request.bodyToMono(CleanupRequest.class)
            .flatMap(cleanupService::previewCleanupValues)
            .flatMap(preview -> ServerResponse.ok().bodyValue(preview))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> cleanupValues(ServerRequest request) {
        return request.bodyToMono(CleanupRequest.class)
            .flatMap(cleanupService::cleanupValues)
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> badRequest(Throwable throwable) {
        var message = throwable.getMessage() == null ? throwable.getClass().getSimpleName()
            : throwable.getMessage();
        return ServerResponse.badRequest().bodyValue(java.util.Map.of("error", message));
    }
}
