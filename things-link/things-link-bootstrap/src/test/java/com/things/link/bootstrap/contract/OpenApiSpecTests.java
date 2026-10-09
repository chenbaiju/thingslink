package com.things.link.bootstrap.contract;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 把 OpenAPI 契约导出为仓库内的文件，并保证它不过期。
 *
 * <p>架构文档 11.1：<b>OpenAPI 是唯一契约来源，前端类型与 SDK 由其生成，不手写。</b>
 *
 * <h2>为什么要把它落成文件</h2>
 * 契约由 springdoc 在运行时生成。如果前端每次都要先把后端跑起来才能生成类型，
 * 就等于把「改前端」和「能启动后端」绑死了 —— 而后端启动需要整套依赖栈。
 * 导出成 {@code docs/openapi.json} 之后，前端只依赖这个文件，CI 里前端流水线
 * 也不必起数据库。
 *
 * <h2>怎么保证不过期</h2>
 * 本测试默认是<b>校验</b>模式：生成当前契约，与仓库里的文件比对，不一致就失败。
 * 这样「改了接口忘了更新契约」会在 CI 变红，而不是等前端某天发现类型对不上。
 *
 * <p>更新契约与所有消费端类型：
 * <pre>
 * python3 scripts/generate-openapi-contracts.py
 * </pre>
 * 该根入口持有唯一写租约并把本测试的输出导向临时文件；禁止直接写仓库契约。
 */
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties =
        "springdoc.paths-to-match=/api/v1/**,/api/open/v1/**,/device-access/v1/**,/app,/app/**,/simulations/**")
@org.springframework.context.annotation.Import({com.things.link.simulator.api.controller.SimulatorController.class,
        OpenApiSpecTests.ToolCatalogConfiguration.class})
@DisplayName("OpenAPI 契约（BACKEND_ARCHITECTURE.md 11.1）")
class OpenApiSpecTests extends AbstractIntegrationTest {

    /** 可选项目描述与响应保持同名，生成类型沿用1000字符输入上限。 */
    @Test
    void projectDescriptionContractIsOptionalAndBounded() throws Exception {
        JsonNode schemas = PRETTY.readTree(fetchSpec()).path("components").path("schemas");
        JsonNode request = schemas.path("CreateProjectRequest");
        assertThat(request.path("properties").path("description").path("type").asString()).isEqualTo("string");
        assertThat(request.path("properties").path("description").path("maxLength").asInt()).isEqualTo(1000);
        assertThat(request.path("required").toString()).doesNotContain("description");
        assertThat(schemas.path("ProjectResponse").path("properties").path("description").path("type").asString()).isEqualTo("string");
    }

    @Autowired
    private org.springframework.boot.webmvc.actuate.endpoint.web.WebMvcEndpointHandlerMapping managementMapping;

    /** 聚合工具目录时仅装配控制器和替身，不启动模拟设备或改变平台生产扫描。 */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.things.link.simulator.application.DeviceSimulator simulator;

    /** 独立工具的错误处理不借用平台 Advice；此处只校正聚合目录的进程边界。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ToolCatalogConfiguration {
        @org.springframework.context.annotation.Bean
        org.springdoc.core.customizers.OpenApiCustomizer simulatorCatalogBoundary() {
            return api -> api.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/simulations/")) return;
                item.readOperations().forEach(operation -> {
                    operation.setSecurity(java.util.List.of());
                    operation.setDescription(operation.getDescription()
                            + " 仅由独立模拟器进程提供，默认回环 8090；本目录不是平台运行路由。"
                            + "含设备凭据的请求不得写入日志，远程使用须先取得受保护测试网络。"
                            + "本片不代表真实硬件或容量资格。");
                    operation.addExtension("x-deployment-role", "simulator");
                    operation.getResponses().forEach((status, response) -> {
                        if (status.matches("[45][0-9Xx]{2}")) {
                            response.setContent(null);
                            response.setDescription("独立工具请求失败；使用该进程的默认 MVC 错误处理，不保证平台 ApiError 结构");
                        }
                    });
                });
            });
        }
    }


    /** 新入口、隐藏分组或退回英文说明必须在发布生成物之前失败。 */
    @Test
    void httpInventoryRejectsMissingRoutesAndEnglishDocumentation() throws Exception {
        JsonNode good = PRETTY.readTree(fetchSpec());
        OpenApiHttpInventoryAssertions.verify(good);
        for (int mutation = 0; mutation < 6; mutation++) {
            var broken = (tools.jackson.databind.node.ObjectNode) PRETTY.readTree(fetchSpec());
            var paths = (tools.jackson.databind.node.ObjectNode) broken.path("paths");
            var operation = (tools.jackson.databind.node.ObjectNode) paths.path(
                    "/api/v1/projects/{projectId}/alarm-notification-templates").path("get");
            if (mutation == 0) paths.remove("/simulations/stats");
            else if (mutation == 1) paths.remove("/api/open/v1/realtime/ws");
            else if (mutation == 2) operation.put("summary", "List templates");
            else if (mutation == 3) operation.remove("description");
            else if (mutation == 4) operation.putArray("tags").add("alarm-notification-controller");
            else ((tools.jackson.databind.node.ObjectNode) operation.path("parameters").get(0)).put("description", "project identifier");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenApiHttpInventoryAssertions.verify(broken))
                    .isInstanceOf(AssertionError.class);
        }
    }

    /** 设备双凭据与原始错误体、静态空错误和独立模拟器不能套用平台管理 API。 */
    @Test
    void transportCatalogPreservesProcessAndWireBoundaries() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        java.util.Set<String> managementPaths = new java.util.LinkedHashSet<>();
        managementMapping.getHandlerMethods().keySet().forEach(mapping -> {
            if (!mapping.getMethodsCondition().getMethods().isEmpty()) mapping.getPatternValues()
                    .forEach(path -> managementPaths.add(path.replace("/**", "/{componentPath}")));
        });
        var documentedManagement = spec.path("paths").propertyNames().stream()
                .filter(path -> path.startsWith("/actuator")).collect(java.util.stream.Collectors.toSet());
        assertThat(documentedManagement).containsExactlyInAnyOrderElementsOf(managementPaths);
        assertThat(spec.path("paths").path("/actuator/health").path("get").path("responses").path("503")
                .path("content").toString()).doesNotContain("ApiError");
        assertThat(spec.path("paths").path("/actuator/prometheus").path("get").path("responses").path("200")
                .path("content").propertyNames()).anyMatch(media -> media.startsWith("text/"));
        var device = spec.path("paths").path("/device-access/v1/property/report").path("post");
        assertThat(device.path("security").get(0).propertyNames()).containsExactlyInAnyOrder("deviceHttpKey", "deviceHttpSecret");
        assertThat(device.path("responses").has("202")).isTrue();
        assertThat(device.path("responses").has("200")).isFalse();
        assertThat(device.path("responses").path("400").path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/DeviceHttpError");
        assertThat(spec.path("components").path("schemas").path("DeviceHttpError").path("properties").propertyNames())
                .containsExactlyInAnyOrder("errorCode", "message");
        assertThat(spec.path("paths").path("/device-access/v1/command/claim").path("post").path("requestBody").path("required").asBoolean()).isFalse();
        assertThat(spec.path("paths").path("/device-access/v1/command/claim").path("post").path("responses").path("204").has("content")).isFalse();
        var shell = spec.path("paths").path("/app/{resourcePath}");
        assertThat(shell.path("get").path("x-spring-path-pattern").asString()).isEqualTo("/app/**");
        assertThat(shell.path("get").path("responses").path("200").path("content").has("text/html")).isTrue();
        assertThat(shell.path("get").path("responses").path("404").has("content")).isFalse();
        assertThat(shell.path("head").path("responses").path("200").has("content")).isFalse();
        var simulation = spec.path("paths").path("/simulations/start").path("post");
        assertThat(simulation.path("servers").get(0).path("url").asString()).isEqualTo("http://127.0.0.1:8090");
        assertThat(simulation.path("x-deployment-role").asString()).isEqualTo("simulator");
        for (String path : java.util.List.of("/api/v1/realtime/ws", "/api/open/v1/realtime/ws", "/ws/app/properties",
                "/ws/app/dashboard", "/ws/dashboard/properties", "/ws/shares/{shareId}/properties")) {
            var handshake = spec.path("paths").path(path).path("get");
            assertThat(handshake.path("responses").has("101")).as(path).isTrue();
            assertThat(handshake.path("responses").path("403").has("content")).as(path).isFalse();
            if (path.contains("/shares/")) {
                assertThat(handshake.path("responses").has("401")).isFalse();
                assertThat(handshake.path("responses").has("404")).isTrue();
                assertThat(handshake.path("responses").has("429")).isTrue();
            }
            assertThat(handshake.path("x-transport").asString()).as(path).isEqualTo("websocket-upgrade");
        }
    }

    @Test
    void assistantModelConfigurationHasOnlyMetadataAndWriteOnlySecret() throws Exception {
        JsonNode doc = PRETTY.readTree(fetchSpec());
        JsonNode path = doc.path("paths").path("/api/v1/projects/{projectId}/assistant/model-configurations/deepseek-chat");
        assertThat(path.propertyNames()).contains("get", "put", "patch", "delete");
        for (String method : List.of("put", "patch")) {
            assertThat(path.path(method).path("responses").path("200").path("content").path("*/*")
                .path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/ModelConfigurationView");
            JsonNode unavailable = path.path(method).path("responses").path("503");
            assertThat(unavailable.path("description").asString()).contains("50061");
            assertThat(unavailable.path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApiError");
        }
        JsonNode view = doc.path("components").path("schemas").path("ModelConfigurationView");
        assertThat(view.path("properties").propertyNames()).containsExactlyInAnyOrder("configured", "enabled", "revision", "updatedAt");
        JsonNode secret = doc.path("components").path("schemas").path("ModelCredentialInput").path("properties").path("apiKey");
        assertThat(secret.path("writeOnly").asBoolean()).isTrue();
        assertThat(secret.path("maxLength").asInt()).isEqualTo(4096);
    }

    @Test void syntheticProbeIsAClosedSingleUseConsoleOperation() throws Exception {
        var spec=PRETTY.readTree(fetchSpec());var op=spec.path("paths").path("/api/v1/projects/{projectId}/assistant/model-probes/{sampleIndex}").path("post");
        assertThat(op.path("security").toString()).contains("consoleAccessBearer");
        assertThat(op.has("requestBody")).isFalse();
        assertThat(op.path("parameters").valueStream().noneMatch(p->"Idempotency-Key".equals(p.path("name").asString()))).isTrue();
        assertThat(op.path("responses").path("200").path("content").path("*/*").path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/AssistantProbeRunView");
        assertThat(spec.path("components").path("schemas").path("AssistantProbeRunView").path("properties").propertyNames())
            .containsExactlyInAnyOrder("attemptId","sampleIndex","status","category","usage");
        assertThat(spec.path("components").path("schemas").path("AssistantProbeUsage").path("properties").has("content")).isFalse();
    }

    @Test void analysisRunPublishesRequiredDomainKeyAndTransientResultContract() throws Exception {
        var spec=PRETTY.readTree(fetchSpec());String path="/api/v1/projects/{projectId}/assistant/analysis-runs";
        var post=spec.path("paths").path(path).path("post");
        assertThat(post.path("security").toString()).contains("consoleAccessBearer");
        var key=post.path("parameters").valueStream().filter(p->"Idempotency-Key".equals(p.path("name").asString())).toList();
        assertThat(key).hasSize(1);assertThat(key.getFirst().path("required").asBoolean()).isTrue();
        assertThat(post.path("requestBody").path("required").asBoolean()).isTrue();
        var schemas=spec.path("components").path("schemas");
        assertThat(schemas.path("AssistantAnalysisRequest").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schemas.path("AssistantAnalysisRequest").path("properties").propertyNames())
                .containsExactlyInAnyOrder("deviceId","expectedModelVersionId","propertyKeys","template");
        assertThat(schemas.path("AssistantAnalysisRequest").path("properties").path("propertyKeys").path("maxItems").asInt()).isEqualTo(10);
        var runView=schemas.path("AssistantAnalysisRunView");
        assertThat(runView.path("properties").propertyNames()).containsExactlyInAnyOrder("call","category","result");
        assertThat(runView.path("required").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("call","category","result");
        var result=runView.path("properties").path("result");
        assertThat(result.path("anyOf").get(0).path("$ref").asString()).isEqualTo("#/components/schemas/AssistantAnalysisResult");
        assertThat(result.path("anyOf").get(1).path("type").asString()).isEqualTo("null");
        var content=schemas.path("AssistantAnalysisResult");
        assertThat(content.path("properties").propertyNames()).containsExactlyInAnyOrder("model","promptVersion","summary","findings","limitations","usage");
        assertThat(content.path("required").size()).isEqualTo(6);assertThat(content.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schemas.path("AssistantAnalysisFinding").path("properties").propertyNames()).containsExactlyInAnyOrder("kind","statement","evidenceIds");
        assertThat(schemas.path("AssistantAnalysisFinding").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schemas.path("AssistantAnalysisUsage").path("properties").propertyNames()).containsExactlyInAnyOrder("promptTokens","completionTokens","totalTokens","cacheHitTokens","cacheMissTokens");
        var call=schemas.path("AssistantAnalysisRunView").path("properties").path("call");
        assertThat(call.has("$ref")).isFalse();assertThat(call.has("type")).isFalse();
        assertThat(call.path("anyOf").size()).isEqualTo(2);
        assertThat(call.path("anyOf").get(0).path("$ref").asString()).isEqualTo("#/components/schemas/AssistantAnalysisCallView");
        assertThat(call.path("anyOf").get(1).path("type").asString()).isEqualTo("null");
        assertThat(schemas.path("AssistantAnalysisCallView").path("required").size()).isEqualTo(7);
        assertThat(schemas.path("AssistantAnalysisAvailability").path("properties").propertyNames()).containsExactlyInAnyOrder("businessAvailable","reason");
        assertThat(schemas.path("AssistantAnalysisCallView").path("properties").propertyNames())
                .containsExactlyInAnyOrder("id","status","createdAt","deadline","expiresAt","dispatchedAt","finishedAt");
        assertThat(spec.path("paths").path(path+"/status").has("get")).isTrue();
        assertThat(spec.path("paths").path(path+"/{callId}").has("get")).isTrue();
        var lookup=spec.path("paths").path(path+"/by-key").path("get");
        assertThat(lookup.path("operationId").asString()).isEqualTo("getAssistantAnalysisCallByKey");
        var lookupKey=lookup.path("parameters").valueStream().filter(p->"Idempotency-Key".equals(p.path("name").asString())).toList();
        assertThat(lookupKey).hasSize(1);assertThat(lookupKey.getFirst().path("required").asBoolean()).isTrue();
        assertThat(lookupKey.getFirst().path("in").asString()).isEqualTo("header");
        assertThat(lookup.has("requestBody")).isFalse();
    }

    @Test void invitationsHaveDistinctPublicProofAndAuthenticatedSingleUseAcceptance() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        var paths = spec.path("paths");
        for (String path : List.of("/api/v1/auth/project-invitation/preview", "/api/v1/auth/project-invitation/register")) {
            var operation = paths.path(path).path("post");
            assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
            assertThat(operation.path("security").isMissingNode() || operation.path("security").isEmpty()).isTrue();
        }
        var accept = paths.path("/api/v1/project-invitations/{invitationId}/accept").path("post");
        assertThat(accept.path("security").toString()).contains("consoleAccessBearer");
        assertThat(accept.path("responses").has("204")).isTrue();
        assertThat(accept.path("parameters").valueStream().noneMatch(p -> "Idempotency-Key".equals(p.path("name").asString()))).isTrue();
        var view = spec.path("components").path("schemas").path("ProjectInvitationView");
        assertThat(view.path("required").valueStream().map(JsonNode::asString).toList())
                .contains("id", "projectId", "targetEmail", "status", "expiresAt").doesNotContain("code");
    }

    @Test void personalFactCollectionIsClosedBoundedReadWithHistoricalSources() throws Exception {
        var spec=PRETTY.readTree(fetchSpec());
        var operation=spec.path("paths").path("/api/v1/projects/{projectId}/assistant/fact-reports/collection").path("post");
        assertThat(operation.path("operationId").asString()).isEqualTo("generatePersonalFactCollection");
        assertThat(operation.path("security").toString()).contains("consoleAccessBearer");
        assertThat(operation.path("parameters").valueStream().map(p->p.path("name").asString()).toList()).containsExactly("projectId");
        assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
        var schemas=spec.path("components").path("schemas");
        var input=schemas.path("PersonalFactCollectionInput");
        assertThat(input.path("properties").propertyNames()).containsExactly("recordIds");
        assertThat(input.path("additionalProperties").asBoolean()).isFalse();
        assertThat(input.path("required").get(0).asString()).isEqualTo("recordIds");
        var ids=input.path("properties").path("recordIds");
        assertThat(ids.path("minItems").asInt()).isEqualTo(1);assertThat(ids.path("maxItems").asInt()).isEqualTo(5);
        assertThat(ids.path("uniqueItems").asBoolean()).isTrue();
        assertThat(ids.path("items").path("format").asString()).isEqualTo("uuid");
        assertThat(ids.path("items").path("pattern").asString()).isEqualTo("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        var report=schemas.path("PersonalFactCollectionReport");
        assertThat(report.path("properties").propertyNames()).containsExactlyInAnyOrder("schemaVersion","mode","scope","sourceRecords",
                "coverage","earliestCollectionAt","latestCollectionAt","expiresAt","contentSha256","markdown");
        assertThat(report.path("required").size()).isEqualTo(10);
        var version=report.path("properties").path("schemaVersion");
        assertThat(version.path("type").asString()).isEqualTo("integer");assertThat(version.has("enum")).isFalse();
        assertThat(version.path("minimum").asInt()).isEqualTo(1);assertThat(version.path("maximum").asInt()).isEqualTo(1);
        assertThat(report.path("properties").path("mode").path("enum").get(0).asString()).isEqualTo("FACTS_ONLY");
        assertThat(report.path("properties").path("scope").path("enum").get(0).asString()).isEqualTo("SELECTED_PERSONAL_RECORDS");
        assertThat(report.path("properties").path("markdown").path("maxLength").asInt()).isEqualTo(1048576);
        var sources=report.path("properties").path("sourceRecords");
        assertThat(sources.path("minItems").asInt()).isEqualTo(1);assertThat(sources.path("maxItems").asInt()).isEqualTo(5);
        assertThat(sources.path("items").path("$ref").asString()).endsWith("/PersonalEvidenceRecordView");
        assertThat(schemas.path("PersonalFactCollectionCoverage").path("required").size()).isEqualTo(5);
        assertThat(schemas.path("PersonalFactCollectionCoverage").path("properties").path("devices").path("maximum").asInt()).isEqualTo(5);
        assertThat(schemas.path("PersonalFactCollectionCoverage").path("properties").path("selectedProperties").path("maximum").asInt()).isEqualTo(50);
    }

    @Test void personalFactReportHasBoundedHistoricalOnlySchemaAndNoWriteInput() throws Exception {
        var spec=PRETTY.readTree(fetchSpec());
        var operation=spec.path("paths").path("/api/v1/projects/{projectId}/assistant/evidence-records/{recordId}/fact-report").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("generatePersonalFactReport");
        assertThat(operation.path("security").toString()).contains("consoleAccessBearer");
        assertThat(operation.has("requestBody")).isFalse();
        assertThat(operation.path("parameters").valueStream().map(p->p.path("name").asString()).toList())
                .containsExactlyInAnyOrder("projectId","recordId");
        var schema=spec.path("components").path("schemas").path("PersonalFactReport");
        assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrder("schemaVersion","mode","sourceRecord","contentSha256","markdown");
        assertThat(schema.path("required").valueStream().map(JsonNode::asString).toList()).hasSize(5);
        var version=schema.path("properties").path("schemaVersion");
        assertThat(version.path("type").asString()).isEqualTo("integer");
        assertThat(version.has("enum")).isFalse();
        assertThat(version.path("minimum").asInt()).isEqualTo(1);
        assertThat(version.path("maximum").asInt()).isEqualTo(1);
        assertThat(schema.path("properties").path("mode").path("enum").get(0).asString()).isEqualTo("FACTS_ONLY");
        assertThat(schema.path("properties").path("markdown").path("maxLength").asInt()).isEqualTo(131072);
    }

    @Test void personalEvidenceRecordsHaveClosedSelectorsAndHistoricalOnlyContent() throws Exception {
        var spec=PRETTY.readTree(fetchSpec());var paths=spec.path("paths");
        String base="/api/v1/projects/{projectId}/assistant/evidence-records";
        assertThat(paths.path(base).path("post").path("responses").has("201")).isTrue();
        assertThat(paths.path(base+"/{recordId}").path("delete").path("responses").has("204")).isTrue();
        for(String path:List.of(base,base+"/{recordId}"))
            assertThat(paths.path(path).path("get").path("security").toString()).contains("consoleAccessBearer");
        var schemas=spec.path("components").path("schemas");
        assertThat(schemas.path("CreatePersonalEvidenceRecordRequest").path("properties").propertyNames())
                .containsExactlyInAnyOrder("deviceId","expectedModelVersionId","propertyKeys");
        assertThat(schemas.path("CreatePersonalEvidenceRecordRequest").path("additionalProperties").asBoolean()).isFalse();
        assertThat(schemas.path("PersonalEvidenceRecordView").path("properties").propertyNames())
                .containsExactlyInAnyOrder("id","deviceId","modelVersionId","createdAt","expiresAt","contentSha256");
        assertThat(schemas.path("PersonalEvidenceProperty").path("properties").path("value").path("type")
                .valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("number","boolean","null");
    }


    @Test void controlledProjectKnowledgeHasClosedApprovalVersionAndLocalCitationContract() throws Exception {
        var spec=PRETTY.readTree(fetchSpec()); var paths=spec.path("paths");
        String base="/api/v1/projects/{projectId}/assistant/knowledge";
        var publish=paths.path(base+"/sources/{sourceKey}").path("put");
        assertThat(publish.path("responses").has("201")).isTrue();
        assertThat(paths.path(base+"/sources/{sourceKey}").path("delete").path("responses").has("204")).isTrue();
        var search=paths.path(base+"/search").path("post");
        assertThat(search.path("security").toString()).contains("consoleAccessBearer");
        assertThat(search.path("parameters").valueStream().map(p -> p.path("name").asString()).toList()).doesNotContain("Idempotency-Key");
        var schemas=spec.path("components").path("schemas");
        var request=schemas.path("PublishAssistantKnowledgeRequest");
        assertThat(request.path("properties").propertyNames()).containsExactlyInAnyOrder("expectedCurrentVersionId","content","approvedForProjectMembers");
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        var keywords=schemas.path("SearchAssistantKnowledgeRequest").path("properties").path("keywords");
        assertThat(keywords.path("minItems").asInt()).isEqualTo(1);assertThat(keywords.path("maxItems").asInt()).isEqualTo(5);
        var result=schemas.path("AssistantKnowledgeSearchResult");
        assertThat(result.path("properties").path("hits").path("maxItems").asInt()).isEqualTo(3);
        assertThat(result.path("properties").path("mode").path("enum").get(0).asString()).isEqualTo("LOCAL_LITERAL");
        assertThat(schemas.path("AssistantKnowledgeHit").path("properties").propertyNames())
            .containsExactlyInAnyOrder("source","startCodePoint","endCodePoint","truncated","text");
        assertThat(schemas.path("AssistantKnowledgeHit").path("properties").path("text").path("maxLength").asInt()).isEqualTo(256);
        assertThat(schemas.path("AssistantKnowledgeSource").path("properties").propertyNames())
            .containsExactlyInAnyOrder("id","sourceKey","versionNumber","createdAt","contentSha256");
    }

    @Test void assistantAlarmsPublishBoundedClosedFactsAndNullableCursor() throws Exception {
        var spec = PRETTY.readTree(fetchSpec());
        var operation = spec.path("paths").path("/api/v1/projects/{projectId}/assistant/devices/{deviceId}/alarms").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("getAssistantDeviceAlarms");
        assertThat(operation.path("security").toString()).contains("consoleAccessBearer");
        assertThat(parameter(operation, "expectedModelVersionId", "query").path("required").asBoolean()).isTrue();
        assertThat(parameter(operation, "limit", "query").path("schema").path("maximum").asInt()).isEqualTo(50);
        assertThat(parameter(operation, "cursor", "query").path("schema").path("maxLength").asInt()).isEqualTo(4096);
        var schemas = spec.path("components").path("schemas");
        var fields = schemas.path("AssistantAlarmEvidence").path("properties");
        assertThat(fields.path("items").path("maxItems").asInt()).isEqualTo(50);
        assertThat(fields.path("sourceModelState").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactly("NOT_PROVIDED");
        assertThat(fields.path("nextCursor").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
        var item = schemas.path("AssistantAlarmEvidenceItem").path("properties");
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "severity", "conditionState", "ackState",
                "firstConditionAt", "activatedAt", "clearedAt", "acknowledgedAt", "lastReceivedAt", "version");
        assertThat(item.path("severity").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");
        assertThat(item.path("activatedAt").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
    }

    @Test void assistantHistoryPublishesBoundedNullableVersionedFacts() throws Exception {
        var spec = PRETTY.readTree(fetchSpec());
        var operation = spec.path("paths").path("/api/v1/projects/{projectId}/assistant/devices/{deviceId}/history").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("getAssistantDeviceHistory");
        assertThat(operation.path("security").toString()).contains("consoleAccessBearer");
        for (String name : List.of("expectedModelVersionId", "propertyKey", "from", "to"))
            assertThat(parameter(operation, name, "query").path("required").asBoolean()).isTrue();
        var schemas = spec.path("components").path("schemas");
        assertThat(schemas.path("AssistantHistoryEvidence").path("properties").path("points").path("maxItems").asInt()).isEqualTo(2000);
        assertThat(schemas.path("AssistantHistoryPoint").path("properties").path("value").path("type")
                .valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("number", "null");
        assertThat(schemas.path("AssistantHistoryPoint").path("properties").path("sourceModelVersionId").path("type")
                .valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("string", "null");
    }

    @Test void assistantSnapshotKeepsConsoleAuthAndBoundedEvidenceSchema() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        var operation = spec.path("paths").path("/api/v1/projects/{projectId}/assistant/devices/{deviceId}/snapshot").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("getAssistantDeviceSnapshot");
        assertThat(operation.path("security").toString()).contains("consoleAccessBearer");
        var keys = parameter(operation, "propertyKey", "query").path("schema");
        assertThat(keys.path("minItems").asInt()).isEqualTo(1);
        assertThat(keys.path("maxItems").asInt()).isEqualTo(10);
        assertThat(keys.path("uniqueItems").asBoolean()).isTrue();
        assertThat(parameter(operation, "expectedModelVersionId", "query").path("required").asBoolean()).isTrue();
        var fields = spec.path("components").path("schemas").path("AssistantEvidenceProperty").path("properties");
        assertThat(fields.path("value").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("number", "string", "boolean", "object", "array", "null");
        assertThat(fields.path("availability").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("PRESENT", "MISSING", "SOURCE_UNKNOWN", "MODEL_MISMATCH");
    }

    /** PS-026a：运行时null必须出现在生成类型中，版本不能退化为JS number。 */
    @Test
    void consoleDeviceAlarmSummaryIsABoundedAuthenticatedRead() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode operation = spec.path("paths").path("/api/v1/projects/{projectId}/alarms/device-status").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("getConsoleDeviceAlarmStatus");
        assertThat(operation.path("security").toString()).contains("consoleAccessBearer");
        var parameter = operation.path("parameters").valueStream()
                .filter(item -> "deviceId".equals(item.path("name").asString())).findFirst().orElseThrow();
        assertThat(parameter.path("schema").path("minItems").asInt()).isEqualTo(1);
        assertThat(parameter.path("schema").path("maxItems").asInt()).isEqualTo(20);
        assertThat(parameter.path("schema").path("uniqueItems").asBoolean()).isTrue();
        assertThat(spec.path("components").path("schemas").path("DeviceAlarmStatusResponse")
                .path("properties").has("observedAt")).isTrue();
    }

    @Test void deviceLocationPointKeepsNullablePairAndExactVersion() throws Exception {
        var schemas=PRETTY.readTree(fetchSpec()).path("components").path("schemas");
        for(String name:List.of("DeviceLocationPointResponse","UpdateDeviceLocationPointRequest")) {
            var fields=schemas.path(name).path("properties");
            for(String field:List.of("longitude","latitude"))
                assertThat(fields.path(field).path("type").valueStream().map(JsonNode::asString).toList())
                        .as(name+"."+field).containsExactlyInAnyOrder("number","null");
            assertThat(fields.path("version").path("type").asString()).isEqualTo("string");
        }
        assertThat(schemas.path("DeviceLocationPointResponse").path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("longitude","latitude","version");
    }

    /** 曲线桶空值不能在生成合同中被缩成非空整数。 */
    @Test void overviewTrendsPreservesNullableBucketValuesAndExplicitSource() throws Exception {
        var spec = PRETTY.readTree(fetchSpec());
        var items = spec.path("components").path("schemas").path("OverviewTrendSeries")
                .path("properties").path("values").path("items");
        assertThat(items.path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("integer", "null");
        assertThat(spec.path("components").path("schemas").path("OverviewTrendsResponse")
                .path("properties").path("source").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactly("OBSERVED", "SIMULATED", "NONE");
    }

    /** 全局守卫必须能发现头、错误和分页的真实结构破坏。 */
    @Test void globalStructureRejectsMissingHeadersWrongErrorsAndBrokenPages() throws Exception {
        var good = PRETTY.readTree(fetchSpec());
        OpenApiGlobalStructureAssertions.verify(good);
        for (int mutation = 0; mutation < 3; mutation++) {
            var broken = (tools.jackson.databind.node.ObjectNode) PRETTY.readTree(fetchSpec());
            if (mutation == 0) ((tools.jackson.databind.node.ObjectNode) broken.path("paths")
                    .path("/api/v1/projects/{projectId}/devices").path("post")).remove("parameters");
            if (mutation == 1) ((tools.jackson.databind.node.ObjectNode) broken.path("paths")
                    .path("/api/v1/app/browser-auth/login").path("post").path("responses").path("400")
                    .path("content").path("application/json").path("schema"))
                    .put("$ref", "#/components/schemas/AppBrowserSessionResponse");
            if (mutation == 2) ((tools.jackson.databind.node.ObjectNode) broken.path("components")
                    .path("schemas").path("CursorPageDeviceResponse").path("properties")).remove("hasMore");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenApiGlobalStructureAssertions.verify(broken))
                    .as("三类错误结构均不可放行").isInstanceOf(AssertionError.class);
        }
    }

    /** ADR0211：管理端点的精确类型、写幂等与安全投影随生成一并验证。 */
    @Test void webhookManagementContractHasTypedRequestsAndSafeQueries() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());JsonNode schemas=spec.path("components").path("schemas");
        String base="/api/v1/projects/{projectId}/webhooks";
        for(String suffix:List.of("","/{subscriptionId}/pause","/{subscriptionId}/resume","/{subscriptionId}/revoke","/{subscriptionId}/rotate","/deliveries/{deliveryId}/recover")) {
            var operation=spec.path("paths").path(base+suffix).path("post");
            assertThat(operation.path("operationId").asString()).isNotBlank();
            assertThat(operation.path("parameters").valueStream().anyMatch(p->p.path("name").asString().equals("Idempotency-Key")&&p.path("required").asBoolean())).isTrue();
        }
        assertThat(schemas.path("WebhookChange").path("properties").path("expectedRevision").path("type").asString()).isEqualTo("string");
        assertThat(schemas.path("WebhookCreate").path("properties").path("deviceIds").path("maxItems").asInt()).isEqualTo(100);
        assertThat(schemas.path("WebhookUpdate").path("properties").path("eventTypes").path("maxItems").asInt()).isEqualTo(7);
        assertThat(schemas.path("AttemptView").path("properties").path("elapsedMillis").path("anyOf").get(0).path("type").asString()).isEqualTo("string");
        assertThat(spec.path("paths").path(base).path("post").path("responses").has("201")).isTrue();
        assertThat(schemas.path("WebhookRecoveryView").path("properties").path("current").path("anyOf")).hasSize(2);
        for(String name:List.of("DeliveryView","DeliveryDetail","AttemptView","EventView","WebhookOperationView","WebhookRecoveryView")) {
            assertThat(schemas.path(name).isMissingNode()).as(name).isFalse();
            assertThat(schemas.path(name).toString()).doesNotContain("leaseToken","eventText","signingSecret","signingGeneration");
        }
        assertThat(schemas.path("WebhookIssued").path("properties").has("signingSecret")).isTrue();
        assertThat(schemas.path("WebhookOperationView").path("properties").path("resultRevision").path("type").asString()).isEqualTo("string");
    }

    /** 浏览器适配只公开精确三路与最小DTO，不能把刷新凭据混入JSON或漂移为可选身份。 */
    @Test
    void browserAuthenticationContractHasExactFieldsAndOperations() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        for (String operation : List.of("login", "refresh", "logout")) {
            JsonNode route = spec.path("paths").path("/api/v1/app/browser-auth/" + operation);
            assertThat(route.propertyNames()).containsExactly("post");
            assertThat(route.path("post").path("responses").has("503")).isTrue();
        }
        JsonNode session = schemas.path("AppBrowserSessionResponse");
        assertThat(session.path("properties").propertyNames())
                .containsExactlyInAnyOrder("accessToken", "accessExpiresAt", "appUserId", "projectId");
        assertThat(java.util.stream.StreamSupport.stream(session.path("required").spliterator(), false)
                .map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("accessToken", "accessExpiresAt", "appUserId", "projectId");
        assertThat(schemas.path("AppBrowserEpochRequest").path("properties").propertyNames())
                .containsExactly("browserEpoch");
        assertThat(schemas.path("AppBrowserLoginRequest").path("properties").propertyNames())
                .containsExactlyInAnyOrder("projectKey", "username", "password", "browserEpoch");
        assertThat(schemas.path("AppBrowserLoginRequest").path("additionalProperties").asBoolean()).isFalse();
    }

    /**
     * 契约文件路径，由 surefire 从 pom 注入。
     *
     * <p>不在代码里拼相对路径：测试的工作目录取决于用 Maven 跑还是用 IDE 跑，
     * 硬编码 {@code ../../docs} 在 IDE 里会指到别处。
     */
    private static final String OUTPUT_PROPERTY = "openapi.output";

    /** 根签名包保留嵌套标量/数组及闭集，不能退化为全部object的Map。 */
    @Test
    void otaTrustBundleContractPreservesExactNestedTypes() throws Exception {
        JsonNode schemas = PRETTY.readTree(fetchSpec()).path("components").path("schemas");
        JsonNode request = schemas.path("OtaTrustImportRequest");
        assertExactRequiredSchema(request, "expectedRevision", "bundle", "signature");
        assertThat(request.path("properties").path("bundle").path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaTrustBundleBody");
        JsonNode bundle = schemas.path("OtaTrustBundleBody");
        assertExactRequiredSchema(bundle, "contractVersion", "trustDomain", "bundleVersion", "keys");
        JsonNode fields = bundle.path("properties");
        assertThat(fields.path("contractVersion").path("type").asString()).isEqualTo("string");
        assertThat(fields.path("contractVersion").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactly("tc-ota-trust-bundle/v1");
        assertThat(fields.path("trustDomain").path("type").asString()).isEqualTo("string");
        assertThat(fields.path("bundleVersion").path("type").asString()).isEqualTo("integer");
        assertThat(fields.path("bundleVersion").path("minimum").asLong()).isEqualTo(1);
        assertThat(fields.path("bundleVersion").path("maximum").asLong()).isEqualTo(9007199254740991L);
        JsonNode keys = fields.path("keys");
        assertThat(keys.path("type").asString()).isEqualTo("array");
        assertThat(keys.path("minItems").asInt()).isEqualTo(1);
        assertThat(keys.path("maxItems").asInt()).isEqualTo(64);
        assertThat(keys.path("items").path("$ref").asString()).isEqualTo("#/components/schemas/OtaTrustKeyBody");
        JsonNode key = schemas.path("OtaTrustKeyBody");
        assertExactRequiredSchema(key, "keyVersion", "signatureProfile", "spki", "fingerprint", "state", "notBefore", "notAfter");
        for (JsonNode closed : List.of(request, bundle, key)) {
            assertThat(closed.has("additionalProperties")).isTrue();
            assertThat(closed.path("additionalProperties").asBoolean()).isFalse();
        }
        JsonNode keyFields = key.path("properties");
        for (String text : List.of("keyVersion", "signatureProfile", "spki", "fingerprint", "state")) {
            assertThat(keyFields.path(text).path("type").asString()).isEqualTo("string");
        }
        assertThat(keyFields.path("signatureProfile").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1");
        assertThat(keyFields.path("state").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("PREPARED", "ACTIVE", "VERIFY_ONLY", "REVOKED");
        for (String time : List.of("notBefore", "notAfter")) {
            assertThat(keyFields.path(time).path("type").asString()).isEqualTo("integer");
            assertThat(keyFields.path(time).path("minimum").asLong()).isZero();
            assertThat(keyFields.path(time).path("maximum").asLong()).isEqualTo(253402300799L);
        }
    }

    /** 发布尝试保留完整声明的闭集和精确标量边界，202响应只公开脱敏状态。 */
    @Test
    void otaPublicationContractPreservesCompleteManifestAndAcceptedResponse() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String path = "/api/v1/projects/{projectId}/ota/firmwares/{firmwareId}/publications";
        JsonNode create = spec.path("paths").path(path).path("post");
        assertThat(create.path("operationId").asString()).isEqualTo("createOtaPublication");
        assertThat(create.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(create.path("requestBody").path("content").path("application/json")
                .path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaPublicationCreateRequest");
        JsonNode acceptedContent = create.path("responses").path("202").path("content");
        JsonNode accepted = acceptedContent.has("application/json")
                ? acceptedContent.path("application/json") : acceptedContent.path("*/*");
        assertThat(accepted.path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaPublicationResponse");
        JsonNode find = spec.path("paths").path(path + "/{publicationId}").path("get");
        assertThat(find.path("operationId").asString()).isEqualTo("getOtaPublication");
        assertThat(successSchema(find).path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaPublicationResponse");
        JsonNode response = schemas.path("OtaPublicationResponse").path("properties");
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("id", "firmwareId", "uploadSessionId",
                "status", "revision", "failureCode", "createdAt", "updatedAt");
        assertThat(response.path("revision").path("type").asString()).isEqualTo("string");

        JsonNode request = schemas.path("OtaPublicationCreateRequest");
        assertExactRequiredSchema(request, "expectedRevision", "uploadSessionId", "manifest");
        JsonNode requestFields = request.path("properties");
        assertThat(requestFields.path("expectedRevision").path("type").asString()).isEqualTo("string");
        assertThat(requestFields.path("expectedRevision").path("pattern").asString())
                .isEqualTo("0|[1-9][0-9]{0,18}");
        assertThat(requestFields.path("uploadSessionId").path("type").asString()).isEqualTo("string");
        assertThat(requestFields.path("uploadSessionId").path("format").asString()).isEqualTo("uuid");
        assertThat(requestFields.path("manifest").path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaPublicationManifest");
        JsonNode manifest = schemas.path("OtaPublicationManifest");
        assertExactRequiredSchema(manifest, "contractVersion", "firmwareId", "firmwareVersion", "trustDomain",
                "deviceTypeId", "productKey", "hardware", "bootloaderMinimumVersion", "artifactSize",
                "artifactSha256", "compression", "delta", "securityVersion", "thingModelVersionId",
                "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest", "allowedSourceThingModelVersionIds",
                "requirements", "signatureProfile", "signingKeyFingerprint", "minimumTrustBundleVersion");
        JsonNode fields = manifest.path("properties");
        for (String uuid : List.of("firmwareId", "deviceTypeId", "thingModelVersionId")) {
            assertThat(fields.path(uuid).path("type").asString()).isEqualTo("string");
            assertThat(fields.path(uuid).path("format").asString()).isEqualTo("uuid");
        }
        for (String text : List.of("contractVersion", "firmwareVersion", "trustDomain", "productKey",
                "bootloaderMinimumVersion", "artifactSha256", "compression", "thingModelSchemaDigestAlgorithm",
                "thingModelSchemaDigest", "signatureProfile", "signingKeyFingerprint")) {
            assertThat(fields.path(text).path("type").asString()).isEqualTo("string");
        }
        assertThat(fields.path("firmwareVersion").path("minLength").asInt()).isEqualTo(1);
        assertThat(fields.path("firmwareVersion").path("maxLength").asInt()).isEqualTo(128);
        for (String digest : List.of("artifactSha256", "thingModelSchemaDigest", "signingKeyFingerprint")) {
            assertThat(fields.path(digest).path("pattern").asString()).isEqualTo("[0-9a-f]{64}");
        }
        assertThat(fields.path("contractVersion").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactly("tc-ota-manifest/v1");
        assertThat(fields.path("compression").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactly("NONE");
        assertThat(fields.path("thingModelSchemaDigestAlgorithm").path("enum")
                .valueStream().map(JsonNode::asString).toList()).containsExactly("PG_JSONB_TEXT_V1_SHA256");
        assertThat(fields.path("signatureProfile").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1");
        JsonNode sources = fields.path("allowedSourceThingModelVersionIds");
        assertThat(sources.path("type").asString()).isEqualTo("array");
        assertThat(sources.path("minItems").asInt()).isEqualTo(1);
        assertThat(sources.path("maxItems").asInt()).isEqualTo(256);
        assertThat(sources.path("uniqueItems").asBoolean()).isTrue();
        assertThat(sources.path("items").path("type").asString()).isEqualTo("string");
        assertThat(sources.path("items").path("format").asString()).isEqualTo("uuid");
        assertThat(fields.path("hardware").path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaPublicationHardware");
        assertThat(fields.path("delta").path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaPublicationDelta");
        assertThat(fields.path("requirements").path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaPublicationRequirements");
        JsonNode hardware = schemas.path("OtaPublicationHardware");
        JsonNode delta = schemas.path("OtaPublicationDelta");
        JsonNode requirements = schemas.path("OtaPublicationRequirements");
        assertExactRequiredSchema(hardware, "model", "boardRevisionMin", "boardRevisionMax");
        assertExactRequiredSchema(delta, "mode");
        assertExactRequiredSchema(requirements, "profile", "minimumRamBytes", "minimumFlashBytes",
                "requiresAbSlots", "requiresRangeDownload", "requiresProtectedSecurityCounter");
        for (JsonNode closed : List.of(request, manifest, hardware, delta, requirements)) {
            assertThat(closed.path("type").asString()).isEqualTo("object");
            assertThat(closed.path("additionalProperties").isBoolean()).isTrue();
            assertThat(closed.path("additionalProperties").asBoolean()).isFalse();
        }
        JsonNode hardwareFields = hardware.path("properties");
        assertThat(hardwareFields.path("model").path("type").asString()).isEqualTo("string");
        assertThat(hardwareFields.path("model").path("pattern").asString())
                .isEqualTo("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        assertThat(delta.path("properties").path("mode").path("type").asString()).isEqualTo("string");
        assertThat(delta.path("properties").path("mode").path("enum")
                .valueStream().map(JsonNode::asString).toList()).containsExactly("NONE");
        JsonNode requirementFields = requirements.path("properties");
        assertThat(requirementFields.path("profile").path("type").asString()).isEqualTo("string");
        assertThat(requirementFields.path("profile").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactly("TC_PROPERTY_COMPOSITE_V1");
        for (String flag : List.of("requiresAbSlots", "requiresRangeDownload", "requiresProtectedSecurityCounter")) {
            assertThat(requirementFields.path(flag).path("type").asString()).isEqualTo("boolean");
        }
        JsonNode protectedCounter = requirementFields.path("requiresProtectedSecurityCounter").path("enum");
        assertThat(protectedCounter.size()).isEqualTo(1);
        assertThat(protectedCounter.path(0).isBoolean()).isTrue();
        assertThat(protectedCounter.path(0).asBoolean()).isTrue();
        for (JsonNode positive : List.of(fields.path("artifactSize"), fields.path("minimumTrustBundleVersion"))) {
            assertThat(positive.path("type").asString()).isEqualTo("integer");
            assertThat(positive.path("minimum").asLong()).isEqualTo(1);
            assertThat(positive.path("maximum").asLong()).isEqualTo(9007199254740991L);
        }
        for (JsonNode nonnegative : List.of(fields.path("securityVersion"), hardwareFields.path("boardRevisionMin"),
                hardwareFields.path("boardRevisionMax"), requirementFields.path("minimumRamBytes"),
                requirementFields.path("minimumFlashBytes"))) {
            assertThat(nonnegative.path("type").asString()).isEqualTo("integer");
            assertThat(nonnegative.has("minimum")).isTrue();
            assertThat(nonnegative.path("minimum").asLong()).isZero();
            assertThat(nonnegative.path("maximum").asLong()).isEqualTo(9007199254740991L);
        }
    }

    /** 管理发布证明与短URL各有精确公开模型，不能把对象定位或设备资格混入合同。 */
    @Test
    void otaReleaseDownloadContractHasOnlyManagementProofAndShortTicket() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String path = "/api/v1/projects/{projectId}/ota/firmwares/{firmwareId}/release";
        JsonNode read = spec.path("paths").path(path).path("get");
        JsonNode download = spec.path("paths").path(path + "/downloads").path("post");
        assertThat(read.path("operationId").asString()).isEqualTo("getOtaRelease");
        assertThat(download.path("operationId").asString()).isEqualTo("createOtaReleaseDownload");
        assertThat(parameter(download, "Idempotency-Key", "header").path("required").asBoolean()).isTrue();
        assertThat(successSchema(read).path("$ref").asString()).isEqualTo("#/components/schemas/OtaReleaseResponse");
        assertThat(successSchema(download).path("$ref").asString()).isEqualTo("#/components/schemas/OtaReleaseDownloadResponse");
        JsonNode proof = schemas.path("OtaReleaseResponse");
        JsonNode ticket = schemas.path("OtaReleaseDownloadResponse");
        assertExactRequiredSchema(proof, "firmwareId", "publicationId", "manifestBase64", "signatureBase64",
                "publicKeySpkiBase64", "signatureProfile", "keyFingerprint", "artifactSize", "artifactSha256", "releaseCreatedAt");
        assertExactRequiredSchema(ticket, "firmwareId", "downloadUrl", "expiresAt");
        for (JsonNode closed : List.of(proof, ticket)) {
            assertThat(closed.path("additionalProperties").isBoolean()).isTrue();
            assertThat(closed.path("additionalProperties").asBoolean()).isFalse();
            assertThat(closed.path("properties").path("firmwareId").path("format").asString()).isEqualTo("uuid");
        }
        JsonNode fields = proof.path("properties");
        assertThat(fields.path("publicationId").path("format").asString()).isEqualTo("uuid");
        for (String base64 : List.of("manifestBase64", "signatureBase64", "publicKeySpkiBase64")) {
            assertThat(fields.path(base64).path("type").asString()).isEqualTo("string");
            assertThat(fields.path(base64).path("format").asString()).isEqualTo("byte");
        }
        assertThat(fields.path("signatureProfile").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1");
        assertThat(fields.path("artifactSize").path("type").asString()).isEqualTo("string");
        for (String digest : List.of("keyFingerprint", "artifactSha256")) {
            assertThat(fields.path(digest).path("pattern").asString()).isEqualTo("[0-9a-f]{64}");
        }
        assertThat(fields.path("releaseCreatedAt").path("format").asString()).isEqualTo("date-time");
        assertThat(ticket.path("properties").path("downloadUrl").path("type").asString()).isEqualTo("string");
        assertThat(ticket.path("properties").path("downloadUrl").path("format").asString()).isEqualTo("uri");
        assertThat(ticket.path("properties").path("expiresAt").path("format").asString()).isEqualTo("date-time");
    }

    /** 生命周期合同仅公开首次转移事实，空转移明确nullable且不是可省略的宽松Map。 */
    @Test
    void otaFirmwareLifecycleContractPreservesClosedChangesAndNullableFacts() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String path = "/api/v1/projects/{projectId}/ota/firmwares/{firmwareId}";
        JsonNode read = spec.path("paths").path(path + "/lifecycle").path("get");
        assertThat(read.path("operationId").asString()).isEqualTo("getOtaFirmwareLifecycle");
        assertThat(successSchema(read).path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaFirmwareLifecycleResponse");
        for (String action : List.of("deprecations", "revocations")) {
            JsonNode operation = spec.path("paths").path(path + "/" + action).path("post");
            assertThat(operation.path("operationId").asString())
                    .isEqualTo(action.equals("deprecations") ? "deprecateOtaFirmware" : "revokeOtaFirmware");
            assertThat(parameter(operation, "Idempotency-Key", "header").path("required").asBoolean()).isTrue();
            assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
            assertThat(operation.path("requestBody").path("content").path("application/json")
                    .path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/OtaFirmwareLifecycleChange");
            assertThat(successSchema(operation).path("$ref").asString())
                    .isEqualTo("#/components/schemas/OtaFirmwareLifecycleResponse");
        }
        JsonNode change = schemas.path("OtaFirmwareLifecycleChange");
        JsonNode state = schemas.path("OtaFirmwareLifecycleResponse");
        JsonNode fact = schemas.path("OtaFirmwareLifecycleTransition");
        assertExactRequiredSchema(change, "expectedRevision", "reason");
        assertExactRequiredSchema(state, "firmwareId", "status", "revision", "deprecation", "revocation");
        assertExactRequiredSchema(fact, "reason", "actorId", "occurredAt");
        for (JsonNode closed : List.of(change, state, fact)) {
            assertThat(closed.path("additionalProperties").isBoolean()).isTrue();
            assertThat(closed.path("additionalProperties").asBoolean()).isFalse();
        }
        for (JsonNode reason : List.of(change.path("properties").path("reason"), fact.path("properties").path("reason"))) {
            assertThat(reason.path("type").asString()).isEqualTo("string");
            assertThat(reason.path("minLength").asInt()).isEqualTo(1);
            assertThat(reason.path("maxLength").asInt()).isEqualTo(512);
        }
        for (JsonNode revision : List.of(change.path("properties").path("expectedRevision"), state.path("properties").path("revision"))) {
            assertThat(revision.path("type").asString()).isEqualTo("string");
            assertThat(revision.path("pattern").asString()).isEqualTo("0|[1-9][0-9]{0,18}");
        }
        assertThat(state.path("properties").path("firmwareId").path("format").asString()).isEqualTo("uuid");
        assertThat(fact.path("properties").path("actorId").path("format").asString()).isEqualTo("uuid");
        assertThat(fact.path("properties").path("occurredAt").path("format").asString()).isEqualTo("date-time");
        for (String nullable : List.of("deprecation", "revocation")) {
            JsonNode field = state.path("properties").path(nullable);
            // ref与type:[object,null]并列仍受非空ref限制；必须用真正的联合类型表达null分支。
            JsonNode variants = field.has("anyOf") ? field.path("anyOf") : field.path("oneOf");
            assertThat(field.has("$ref")).isFalse();
            assertThat(variants.size()).isEqualTo(2);
            assertThat(variants.valueStream().anyMatch(value ->
                    "#/components/schemas/OtaFirmwareLifecycleTransition".equals(value.path("$ref").asString()))).isTrue();
            assertThat(variants.valueStream().anyMatch(value -> "null".equals(value.path("type").asString()))).isTrue();
        }
    }

    /** 运行合同保留可空控制事实和独立计数，不把通知意图表述为设备执行结果。 */
    @Test
    void otaCampaignExecutionContractKeepsExactNullableControlFacts() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());JsonNode schemas=spec.path("components").path("schemas");
        String base="/api/v1/projects/{projectId}/ota/campaigns/{campaignId}";
        JsonNode read=spec.path("paths").path(base+"/execution").path("get");
        assertThat(read.isMissingNode()).isFalse();
        assertThat(successSchema(read).path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignExecutionResponse");
        for(String suffix:List.of("starting","pauses","resumptions")) {
            JsonNode operation=spec.path("paths").path(base+"/"+suffix).path("post");
            assertThat(parameter(operation,"Idempotency-Key","header").path("required").asBoolean()).isTrue();
            assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
            assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString())
                    .isEqualTo("#/components/schemas/"+("starting".equals(suffix)?"OtaCampaignStartingRequest":"OtaCampaignChangeRequest"));
            assertThat(successSchema(operation).path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignExecutionResponse");
        }
        JsonNode response=schemas.path("OtaCampaignExecutionResponse"),start=schemas.path("OtaCampaignStartingRequest"),change=schemas.path("OtaCampaignChangeRequest");
        assertExactRequiredSchema(response,"id","status","stateVersion","startedAt","currentBatch","pauseKind","pauseReason","pausedAt","pauseActorId","pauseJobId","pendingCount","dispatchedCount","skippedCount","runtimeCancellation","batchProgress");
        JsonNode cancellation = schemas.path("OtaCampaignRuntimeCancellationResponse");
        assertExactRequiredSchema(cancellation, "requestedRevision", "requestedFromStatus", "requestedAt", "requestedBy",
                "reason", "cancelledPendingCount", "unresolvedCount", "completedAt");
        assertThat(cancellation.path("additionalProperties").isBoolean()).isTrue();
        assertThat(cancellation.path("additionalProperties").asBoolean()).isFalse();
        JsonNode nullableCancellation = response.path("properties").path("runtimeCancellation");
        assertThat(nullableCancellation.has("$ref")).isFalse();
        assertThat(nullableCancellation.has("type")).isFalse();
        JsonNode alternatives = nullableCancellation.path("anyOf");
        assertThat(alternatives.isArray()).isTrue();
        assertThat(alternatives.size()).isEqualTo(2);
        assertThat(alternatives.valueStream().filter(value ->
                "#/components/schemas/OtaCampaignRuntimeCancellationResponse".equals(value.path("$ref").asString()))
                .allMatch(value -> value.size() == 1)).isTrue();
        assertThat(alternatives.valueStream().filter(value ->
                "#/components/schemas/OtaCampaignRuntimeCancellationResponse".equals(value.path("$ref").asString())).count()).isEqualTo(1);
        assertThat(alternatives.valueStream().filter(value -> "null".equals(value.path("type").asString()))
                .filter(value -> value.size() == 1).count()).isEqualTo(1);
        JsonNode cancellationFields = cancellation.path("properties");
        assertThat(cancellationFields.path("requestedRevision").path("type").asString()).isEqualTo("string");
        assertThat(cancellationFields.path("requestedRevision").path("pattern").asString()).isEqualTo("0|[1-9][0-9]{0,18}");
        assertThat(cancellationFields.path("requestedFromStatus").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("RUNNING", "PAUSED");
        assertThat(cancellationFields.path("requestedAt").path("format").asString()).isEqualTo("date-time");
        assertThat(cancellationFields.path("requestedBy").path("format").asString()).isEqualTo("uuid");
        assertThat(cancellationFields.path("reason").path("minLength").asInt()).isEqualTo(1);
        assertThat(cancellationFields.path("reason").path("maxLength").asInt()).isEqualTo(256);
        for (String name : List.of("cancelledPendingCount", "unresolvedCount")) {
            assertCampaignIntegerRange(cancellationFields.path(name), 0, 1000);
        }
        assertThat(cancellationFields.path("completedAt").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
        assertThat(cancellationFields.path("completedAt").path("format").asString()).isEqualTo("date-time");
        assertExactRequiredSchema(start,"expectedRevision");assertExactRequiredSchema(change,"expectedRevision","reason");
        for(JsonNode closed:List.of(response,start,change)) {
            assertThat(closed.path("additionalProperties").isBoolean()).isTrue();assertThat(closed.path("additionalProperties").asBoolean()).isFalse();
        }
        JsonNode fields=response.path("properties");
        assertThat(fields.path("id").path("format").asString()).isEqualTo("uuid");
        assertThat(fields.path("status").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("DRAFT","SCHEDULED","RUNNING","PAUSED","CANCELLING","CANCELLED","COMPLETED");
        for(String name:List.of("startedAt","pauseKind","pauseReason","pausedAt","pauseActorId","pauseJobId")) {
            assertThat(fields.path(name).path("type").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("string","null");
        }
        assertThat(fields.path("currentBatch").path("type").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("integer","null");
        assertThat(fields.path("currentBatch").path("minimum").asInt()).isEqualTo(1);assertThat(fields.path("currentBatch").path("maximum").asInt()).isEqualTo(1000);
        for(String name:List.of("startedAt","pausedAt")) assertThat(fields.path(name).path("format").asString()).isEqualTo("date-time");
        for(String name:List.of("pauseActorId","pauseJobId")) assertThat(fields.path(name).path("format").asString()).isEqualTo("uuid");
        assertThat(fields.path("pauseKind").path("enum").valueStream().filter(value->!value.isNull()).map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("MANUAL","AUTO","SECURITY");
        assertThat(fields.path("pauseKind").path("enum").valueStream().anyMatch(JsonNode::isNull)).isTrue();
        for(String name:List.of("pendingCount","dispatchedCount","skippedCount")) assertCampaignIntegerRange(fields.path(name),0,1000);
        for(JsonNode value:List.of(fields.path("stateVersion"),start.path("properties").path("expectedRevision"),change.path("properties").path("expectedRevision"))) {
            assertThat(value.path("type").asString()).isEqualTo("string");assertThat(value.path("pattern").asString()).isEqualTo("0|[1-9][0-9]{0,18}");
        }
        assertThat(change.path("properties").path("reason").path("minLength").asInt()).isEqualTo(1);
        assertThat(change.path("properties").path("reason").path("maxLength").asInt()).isEqualTo(256);
    }

    /** 扩批仅接收版本、批序和原因，固定终态统计与等待人工放行必须显式表达。 */
    @Test
    void otaCampaignAdvancementContractKeepsExactApprovalAndCompletionFacts() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());
        JsonNode schemas=spec.path("components").path("schemas");
        JsonNode operation=spec.path("paths").path(
                "/api/v1/projects/{projectId}/ota/campaigns/{campaignId}/batch-advancements").path("post");
        assertThat(operation.path("operationId").asString()).isEqualTo("advanceOtaCampaignBatch");
        assertThat(parameter(operation,"Idempotency-Key","header").path("required").asBoolean()).isTrue();
        assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
        JsonNode request=schemas.path("OtaCampaignAdvancementRequest");
        assertExactRequiredSchema(request,"expectedRevision","expectedBatchNumber","reason");
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        JsonNode fields=request.path("properties");
        assertThat(fields.path("expectedRevision").path("type").asString()).isEqualTo("string");
        assertThat(fields.path("expectedRevision").path("pattern").asString()).isEqualTo("0|[1-9][0-9]{0,18}");
        assertThat(fields.path("expectedBatchNumber").path("type").asString()).isEqualTo("string");
        assertThat(fields.path("reason").path("minLength").asInt()).isEqualTo(1);
        assertThat(fields.path("reason").path("maxLength").asInt()).isEqualTo(256);
        JsonNode progress=schemas.path("OtaCampaignBatchProgressResponse");
        assertExactRequiredSchema(progress,"currentBatchStatus","requireManualBatchApproval","awaitingManualApproval",
                "nextBatchNumber","completedAt","outcome","targetCount","succeededCount","rolledBackCount","skippedCount","timedOutCount","cancelledCount");
        assertThat(progress.path("additionalProperties").isBoolean()).isTrue();
        assertThat(progress.path("additionalProperties").asBoolean()).isFalse();
        JsonNode values=progress.path("properties");
        for(String name:List.of("currentBatchStatus","completedAt","outcome")) {
            assertThat(values.path(name).path("type").valueStream().map(JsonNode::asString).toList())
                    .containsExactlyInAnyOrder("string","null");
        }
        assertThat(values.path("currentBatchStatus").path("enum").valueStream().filter(v->!v.isNull()).map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("PENDING","RUNNING","PAUSED","DRAINING","SUCCEEDED","FAILED","CANCELLING","CANCELLED");
        assertThat(values.path("completedAt").path("format").asString()).isEqualTo("date-time");
        assertThat(values.path("nextBatchNumber").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("integer","null");
        assertThat(values.path("outcome").path("enum").valueStream().filter(v->!v.isNull()).map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("SUCCESS","PARTIAL_FAILURE","FAILED");
        assertThat(values.path("outcome").path("enum").valueStream().anyMatch(JsonNode::isNull)).isTrue();
        for(String name:List.of("requireManualBatchApproval","awaitingManualApproval"))
            assertThat(values.path(name).path("type").asString()).isEqualTo("boolean");
        for(String name:List.of("targetCount","succeededCount","rolledBackCount","skippedCount","timedOutCount","cancelledCount"))
            assertCampaignIntegerRange(values.path(name),0,1000);
        JsonNode batch=schemas.path("OtaCampaignBatchResponse");
        assertThat(batch.path("required").valueStream().map(JsonNode::asString).toList()).contains("timedOutCount");
        assertCampaignIntegerRange(batch.path("properties").path("timedOutCount"),0,1000);
    }

    /** 活动合同完整表达计划、策略和冻结作业，不能退化为Map或丢失可空模型与时间。 */
    @Test
    void otaCampaignContractKeepsExactPlanPolicyAndNullableFrozenTargets() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());JsonNode schemas=spec.path("components").path("schemas");
        String base="/api/v1/projects/{projectId}/ota/campaigns";
        JsonNode create=spec.path("paths").path(base).path("post");
        JsonNode read=spec.path("paths").path(base+"/{campaignId}").path("get");
        JsonNode schedule=spec.path("paths").path(base+"/{campaignId}/scheduling").path("post");
        JsonNode cancel=spec.path("paths").path(base+"/{campaignId}/cancellation").path("post");
        assertThat(create.path("operationId").asString()).isEqualTo("createOtaCampaign");
        assertThat(read.path("operationId").asString()).isEqualTo("getOtaCampaign");
        assertThat(schedule.path("operationId").asString()).isEqualTo("scheduleOtaCampaign");
        assertThat(cancel.path("operationId").asString()).isEqualTo("cancelOtaCampaign");
        for(JsonNode operation:List.of(create,schedule,cancel)) {
            assertThat(parameter(operation,"Idempotency-Key","header").path("required").asBoolean()).isTrue();
            assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
        }
        assertThat(create.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignPlanBody");
        assertThat(schedule.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignSchedulingRequest");
        assertThat(cancel.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignCancellationRequest");
        assertThat(create.path("responses").path("201").path("content").path("application/json").path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignResponse");
        for(JsonNode operation:List.of(read,schedule,cancel)) assertThat(successSchema(operation).path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignResponse");
        JsonNode plan=schemas.path("OtaCampaignPlanBody"),policy=schemas.path("OtaCampaignExecutionPolicy"),stages=schemas.path("OtaCampaignStageTimeouts");
        JsonNode response=schemas.path("OtaCampaignResponse"),job=schemas.path("OtaCampaignJobResponse");
        JsonNode scheduling=schemas.path("OtaCampaignSchedulingRequest"),cancellation=schemas.path("OtaCampaignCancellationRequest");
        assertExactRequiredSchema(plan,"contractVersion","firmwareId","deviceIds","batchSize","notBefore","executionPolicy");
        assertExactRequiredSchema(policy,"maxConcurrentDownloads","maxDownloadBytesPerSecond","downloadRetryLimit","retryBackoffSeconds","healthWindowSeconds","pauseMinEvaluated","pauseFailureCount","pauseFailureRateBps","batchMinSuccessRateBps","requireManualBatchApproval","stageTimeoutSeconds");
        assertExactRequiredSchema(stages,"DISPATCHED","DOWNLOADING","VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING","CONFIRMING","ROLLBACK_PENDING","ROLLING_BACK");
        assertExactRequiredSchema(response,"id","firmwareId","status","stateVersion","planSha256","plan","targetCount","batchCount","jobs","createdAt","updatedAt","scheduledAt","cancelledAt");
        assertExactRequiredSchema(job,"id","batchNumber","deviceId","deviceTypeId","thingModelVersionId","credentialVersion","status");
        assertExactRequiredSchema(scheduling,"expectedRevision");assertExactRequiredSchema(cancellation,"expectedRevision","reason");
        for(JsonNode closed:List.of(plan,policy,stages,response,job,scheduling,cancellation)) {
            assertThat(closed.path("additionalProperties").isBoolean()).isTrue();assertThat(closed.path("additionalProperties").asBoolean()).isFalse();
        }
        JsonNode p=plan.path("properties"),r=response.path("properties"),j=job.path("properties"),e=policy.path("properties");
        assertThat(p.path("contractVersion").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("tc-ota-campaign-plan/v1");
        assertThat(p.path("firmwareId").path("format").asString()).isEqualTo("uuid");
        JsonNode ids=p.path("deviceIds");assertThat(ids.path("type").asString()).isEqualTo("array");
        assertThat(ids.path("minItems").asInt()).isEqualTo(1);assertThat(ids.path("maxItems").asInt()).isEqualTo(1000);
        assertThat(ids.path("uniqueItems").asBoolean()).isTrue();
        assertThat(ids.path("items").path("type").asString()).isEqualTo("string");assertThat(ids.path("items").path("format").asString()).isEqualTo("uuid");
        assertThat(p.path("executionPolicy").path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignExecutionPolicy");
        assertThat(e.path("stageTimeoutSeconds").path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignStageTimeouts");
        assertThat(r.path("plan").path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignPlanBody");
        assertThat(r.path("jobs").path("type").asString()).isEqualTo("array");assertThat(r.path("jobs").path("maxItems").asInt()).isEqualTo(1000);
        assertThat(r.path("jobs").path("items").path("$ref").asString()).isEqualTo("#/components/schemas/OtaCampaignJobResponse");
        for(String name:List.of("scheduledAt","cancelledAt")) {
            assertThat(r.path(name).path("type").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("string","null");
            assertThat(r.path(name).path("format").asString()).isEqualTo("date-time");
        }
        assertThat(j.path("thingModelVersionId").path("type").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("string","null");
        assertThat(j.path("thingModelVersionId").path("format").asString()).isEqualTo("uuid");
        for(JsonNode decimal:List.of(r.path("stateVersion"),j.path("credentialVersion"),scheduling.path("properties").path("expectedRevision"),cancellation.path("properties").path("expectedRevision"))) {
            assertThat(decimal.path("type").asString()).isEqualTo("string");assertThat(decimal.path("pattern").asString()).isEqualTo("0|[1-9][0-9]{0,18}");
        }
        assertThat(e.path("requireManualBatchApproval").path("type").asString()).isEqualTo("boolean");
        assertThat(r.path("status").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("DRAFT","SCHEDULED","RUNNING","PAUSED","CANCELLING","CANCELLED","COMPLETED");
        // D-151：作业状态闭集必须与ota_device_job_runtime_values_ck逐项一致（含重试等待与失败终态）。
        assertThat(j.path("status").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("PENDING","CANCELLED","SKIPPED_INELIGIBLE","DISPATCHED","RETRY_WAIT","DOWNLOADING","VERIFYING","INSTALLING","REBOOTING","HEALTH_CHECKING","CONFIRMING","SUCCEEDED","ROLLBACK_PENDING","ROLLING_BACK","ROLLED_BACK","RECOVERY_REQUIRED","FAILED","TIMED_OUT");
        for(String name:List.of("maxConcurrentDownloads","pauseMinEvaluated","pauseFailureCount")) assertCampaignIntegerRange(e.path(name),1,1000);
        assertCampaignIntegerRange(p.path("batchSize"),1,1000);assertCampaignIntegerRange(j.path("batchNumber"),1,1000);
        assertCampaignIntegerRange(e.path("maxDownloadBytesPerSecond"),1,1073741824);assertCampaignIntegerRange(e.path("downloadRetryLimit"),0,10);
        assertCampaignIntegerRange(e.path("retryBackoffSeconds"),1,3600);assertCampaignIntegerRange(e.path("healthWindowSeconds"),1,86400);
        for(String name:List.of("pauseFailureRateBps","batchMinSuccessRateBps")) assertCampaignIntegerRange(e.path(name),1,10000);
        for(JsonNode stage:stages.path("properties")) assertCampaignIntegerRange(stage,1,86400);
        assertCampaignIntegerRange(r.path("targetCount"),0,1000);assertCampaignIntegerRange(r.path("batchCount"),0,1000);
    }

    /** 具体整数上下限均须生成，缺失minimum不能以默认零蒙混通过。 */
    private static void assertCampaignIntegerRange(JsonNode field,long minimum,long maximum) {
        assertThat(field.path("type").asString()).isEqualTo("integer");assertThat(field.has("minimum")).isTrue();
        assertThat(field.path("minimum").asLong()).isEqualTo(minimum);assertThat(field.path("maximum").asLong()).isEqualTo(maximum);
    }

    /** 资格管理API只输出当前判断，不接受设备报告正文或泄漏下载凭据。 */
    @Test
    void otaDeviceEligibilityContractIsExactAndNullableWithoutDeviceHttpIngress() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode path = spec.path("paths").path("/api/v1/projects/{projectId}/ota/devices/{deviceId}/eligibility");
        JsonNode read = path.path("get");
        assertThat(read.path("operationId").asString()).isEqualTo("getOtaDeviceEligibility");
        assertThat(path.has("post")).isFalse();
        assertThat(read.has("requestBody")).isFalse();
        for (String name : List.of("projectId", "deviceId", "firmwareId")) {
            JsonNode param = parameter(read, name, "firmwareId".equals(name) ? "query" : "path");
            assertThat(param.path("required").asBoolean()).isTrue();
            assertThat(param.path("schema").path("type").asString()).isEqualTo("string");
            assertThat(param.path("schema").path("format").asString()).isEqualTo("uuid");
        }
        assertThat(successSchema(read).path("$ref").asString()).isEqualTo("#/components/schemas/OtaDeviceEligibilityResponse");
        JsonNode response = schemas.path("OtaDeviceEligibilityResponse");
        assertExactRequiredSchema(response, "eligible", "reason", "reportRevision", "checkedAt");
        assertThat(response.path("additionalProperties").isBoolean()).isTrue();
        assertThat(response.path("additionalProperties").asBoolean()).isFalse();
        JsonNode fields = response.path("properties");
        assertThat(fields.path("eligible").path("type").asString()).isEqualTo("boolean");
        assertThat(fields.path("reason").path("type").asString()).isEqualTo("string");
        assertThat(fields.path("reason").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("ELIGIBLE", "REPORT_MISSING", "REPORT_STALE", "IDENTITY_CHANGED",
                        "INCOMPATIBLE", "TRUST_NOT_ACKNOWLEDGED", "FIRMWARE_UNAVAILABLE");
        assertThat(fields.path("reportRevision").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
        assertThat(fields.path("reportRevision").path("pattern").asString()).isEqualTo("[1-9][0-9]*");
        assertThat(fields.path("checkedAt").path("type").asString()).isEqualTo("string");
        assertThat(fields.path("checkedAt").path("format").asString()).isEqualTo("date-time");
    }

    /** 基线登记请求不能声明能力，读取模型保留完整22字段和嵌套范围的精确类型。 */
    @Test
    void otaTypeBaselineContractPreservesControlledSourceAndExactCapabilityFields() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String path = "/api/v1/projects/{projectId}/ota/device-types/{deviceTypeId}/baseline";
        JsonNode read = spec.path("paths").path(path).path("get");
        JsonNode register = spec.path("paths").path(path + "/registrations").path("post");
        assertThat(read.path("operationId").asString()).isEqualTo("getOtaTypeBaseline");
        assertThat(register.path("operationId").asString()).isEqualTo("registerOtaTypeBaseline");
        assertThat(parameter(register, "Idempotency-Key", "header").path("required").asBoolean()).isTrue();
        assertThat(register.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(register.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/OtaTypeBaselineRegistration");
        for (JsonNode operation : List.of(read, register)) {
            assertThat(successSchema(operation).path("$ref").asString()).isEqualTo("#/components/schemas/OtaTypeBaselineResponse");
        }
        JsonNode request = schemas.path("OtaTypeBaselineRegistration");
        JsonNode response = schemas.path("OtaTypeBaselineResponse");
        JsonNode baseline = schemas.path("OtaTypeBaselineBody");
        JsonNode hardware = schemas.path("OtaTypeBaselineHardware");
        JsonNode bootloader = schemas.path("OtaTypeBaselineBootloader");
        assertExactRequiredSchema(request, "expectedRevision");
        assertThat(request.path("properties").path("expectedRevision").path("type").asString()).isEqualTo("string");
        assertThat(request.path("properties").path("expectedRevision").path("pattern").asString()).isEqualTo("0|[1-9][0-9]{0,18}");
        assertExactRequiredSchema(response, "revision", "baselineHash", "baseline", "registeredAt", "updatedAt");
        assertThat(response.path("properties").path("revision").path("type").asString()).isEqualTo("string");
        assertThat(response.path("properties").path("baseline").path("$ref").asString()).isEqualTo("#/components/schemas/OtaTypeBaselineBody");
        assertExactRequiredSchema(baseline, "contractVersion", "tenantId", "projectId", "deviceTypeId", "productKey",
                "baselineVersion", "trustDomain", "rootFingerprint", "hardware", "bootloader", "signatureProfiles",
                "maximumArtifactBytes", "availableRamBytes", "availableFlashBytes", "supportsAbSlots", "supportsRangeDownload",
                "supportsResumeDownload", "protectedSecurityCounterBits", "compressionAlgorithms", "deltaModes", "propertyProfile", "evidenceReference");
        assertExactRequiredSchema(hardware, "model", "boardRevisionMin", "boardRevisionMax");
        assertExactRequiredSchema(bootloader, "minimumVersion", "maximumVersion");
        for (JsonNode closed : List.of(request, response, baseline, hardware, bootloader)) {
            assertThat(closed.path("additionalProperties").isBoolean()).isTrue();
            assertThat(closed.path("additionalProperties").asBoolean()).isFalse();
        }
        JsonNode fields = baseline.path("properties");
        for (String uuid : List.of("tenantId", "projectId", "deviceTypeId")) {
            assertThat(fields.path(uuid).path("type").asString()).isEqualTo("string");
            assertThat(fields.path(uuid).path("format").asString()).isEqualTo("uuid");
        }
        assertThat(fields.path("hardware").path("$ref").asString()).isEqualTo("#/components/schemas/OtaTypeBaselineHardware");
        assertThat(fields.path("bootloader").path("$ref").asString()).isEqualTo("#/components/schemas/OtaTypeBaselineBootloader");
        for (String flag : List.of("supportsAbSlots", "supportsRangeDownload", "supportsResumeDownload")) {
            assertThat(fields.path(flag).path("type").asString()).isEqualTo("boolean");
        }
        for (String array : List.of("signatureProfiles", "compressionAlgorithms", "deltaModes")) {
            JsonNode list = fields.path(array);
            assertThat(list.path("type").asString()).isEqualTo("array");
            assertThat(list.path("minItems").asInt()).isEqualTo(1);
            assertThat(list.path("maxItems").asInt()).isEqualTo(array.equals("signatureProfiles") ? 2 : 1);
            assertThat(list.path("uniqueItems").asBoolean()).isTrue();
            assertThat(list.path("items").path("type").asString()).isEqualTo("string");
            if (array.equals("signatureProfiles")) {
                assertThat(list.path("items").path("enum").valueStream().map(JsonNode::asString).toList())
                        .containsExactlyInAnyOrder("TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1");
            } else assertThat(list.path("items").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("NONE");
        }
        for (String integer : List.of("baselineVersion", "maximumArtifactBytes", "availableRamBytes", "availableFlashBytes", "protectedSecurityCounterBits")) {
            JsonNode value = fields.path(integer);
            assertThat(value.path("type").asString()).isEqualTo("integer");
            assertThat(value.has("minimum")).isTrue();
            assertThat(value.path("minimum").asLong()).isEqualTo(integer.equals("baselineVersion") || integer.equals("maximumArtifactBytes") ? 1 : 0);
            long maximum = integer.equals("maximumArtifactBytes") ? 67108864L
                    : integer.equals("protectedSecurityCounterBits") ? 53L : 9007199254740991L;
            assertThat(value.path("maximum").asLong()).isEqualTo(maximum);
        }
        assertThat(fields.path("contractVersion").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("tc-ota-type-baseline/v1");
        assertThat(fields.path("propertyProfile").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("TC_PROPERTY_COMPOSITE_V1");
        assertThat(fields.path("rootFingerprint").path("pattern").asString()).isEqualTo("[0-9a-f]{64}");
        assertThat(fields.path("evidenceReference").path("minLength").asInt()).isEqualTo(1);
        assertThat(fields.path("evidenceReference").path("maxLength").asInt()).isEqualTo(256);
    }

    /** 置为true时只允许写入唯一生成入口分配的临时文件。 */
    private static final String WRITE_PROPERTY = "openapi.write";

    /** 唯一生成入口传给Maven子进程的租约令牌属性。 */
    private static final String LEASE_TOKEN_PROPERTY = "openapi.write.lease-token";

    /** 唯一生成入口放入子进程环境的租约令牌副本，避免旧CLI只补一个公开参数就绕过。 */
    private static final String LEASE_TOKEN_ENVIRONMENT = "THINGS_LINK_OPENAPI_WRITE_LEASE_TOKEN";

    /** JSON格式化器用于稳定输出与忽略对象成员顺序的语义比较。 */
    private static final ObjectMapper PRETTY = JsonMapper.builder().build();

    /** MockMvc读取与真实启动装配一致的springdoc文档，不要求前端先启动后端进程。 */
    @Autowired
    private MockMvc mockMvc;

    /** D-144：生成前拒绝任何全局重复ID，路径不变也不能让生成客户端悄悄合并不同响应。 */
    @Test
    void operationIdsAreGloballyUniqueAndDashboardMigrationPreservesRegionIdentity() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        assertUniqueOperationIds(spec);
        assertThat(spec.path("paths").path("/api/v1/projects/{projectId}/dashboards")
                .path("get").path("operationId").asString()).isEqualTo("listDashboards");
        assertThat(spec.path("paths").path("/api/v1/project-regions")
                .path("get").path("operationId").asString()).isEqualTo("list_18");
    }

    /** 标准HTTP操作逐个收集，不把path-level参数或扩展误判为操作。 */
    private static void assertUniqueOperationIds(JsonNode spec) {
        java.util.Map<String, String> owners = new java.util.LinkedHashMap<>();
        JsonNode paths = spec.path("paths");
        for (String path : paths.propertyNames()) {
            for (String method : List.of("get", "put", "post", "delete", "options", "head", "patch", "trace")) {
                JsonNode operation = paths.path(path).path(method);
                if (operation.isMissingNode()) { continue; }
                String id = operation.path("operationId").asString();
                assertThat(id).as("%s %s 必须有operationId", method, path).isNotBlank();
                assertThat(owners.putIfAbsent(id, method + " " + path))
                        .as("operationId=%s 被多个HTTP操作占用，当前=%s %s", id, method, path).isNull();
            }
        }
    }

    /** 校验仓库契约，或在全部结构断言通过后写入租约临时文件。 */
    @Test
    @DisplayName("契约文件与当前代码一致")
    void specFileIsUpToDate() throws Exception {
        boolean writeMode = Boolean.getBoolean(WRITE_PROPERTY);
        Path output = requiredPathProperty(OUTPUT_PROPERTY);

        if (writeMode) {
            OpenApiGenerationWriteGuard.requireAuthorizedTemporaryWrite(
                    output,
                    OpenApiGenerationWriteGuard.canonicalRepositoryOutput(OpenApiSpecTests.class),
                    System.getProperty(LEASE_TOKEN_PROPERTY),
                    System.getenv(LEASE_TOKEN_ENVIRONMENT));
        }

        // 先拒绝旧直写命令，避免无租约调用仍生成整份契约后才失败。
        String generated = fetchSpec();
        var parsed = PRETTY.readTree(generated);
        assertUniqueOperationIds(parsed);
        OpenApiGlobalStructureAssertions.verify(parsed);
        OpenApiHttpInventoryAssertions.verify(parsed);

        if (writeMode) {
            // JUnit不保证测试顺序；写入测试自身复用全部结构断言，任何失败都发生在落盘之前。
            assertExpectedMetadata(parsed);
            assertRecursiveMenuSchema(parsed);
            Files.createDirectories(output.getParent());
            Files.writeString(output, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(output)
                .as("契约文件不存在。请执行唯一生成入口：%s",
                        OpenApiGenerationWriteGuard.GENERATION_COMMAND)
                .exists();
        // JSON 对象成员顺序没有契约语义；不同 Spring 上下文装配顺序不应制造整份文件过期的假阳性。
        assertThat(PRETTY.readTree(Files.readString(output, StandardCharsets.UTF_8)))
                .as("docs/openapi.json 已过期。接口变更后必须同步契约，否则前端生成的类型"
                        + "会与后端不符。更新：%s", OpenApiGenerationWriteGuard.GENERATION_COMMAND)
                .isEqualTo(parsed);
    }

    /**
     * 读取必需的Maven注入路径，缺失时给出可定位的配置错误。
     *
     * @param propertyName 系统属性名。
     * @return 该属性表示的路径。
     */
    private static Path requiredPathProperty(String propertyName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少OpenAPI路径属性：" + propertyName);
        }
        return Path.of(value);
    }


    /**
     * 契约本身必须符合架构文档11.1的基本约定。
     *
     * @throws Exception springdoc读取或JSON解析失败。
     */
    @Test
    @DisplayName("契约元信息正确")
    void specHasExpectedMetadata() throws Exception {
        assertExpectedMetadata(PRETTY.readTree(fetchSpec()));
    }

    /**
     * 断言契约符合架构文档11.1的基本元信息。
     *
     * <p>G3-LOCAL-4全局结构由独立适用矩阵守卫，生成写入前也必须执行。
     *
     * @param spec 已确认是OpenAPI文档的JSON树。
     */
    private static void assertExpectedMetadata(JsonNode spec) {

        assertThat(spec.get("openapi").asString()).startsWith("3.1");
        assertThat(spec.get("info").get("title").asString()).isEqualTo("ThingsLink HTTP 接口目录");
        // 契约版本，不是应用构建版本 —— 两者分别演进
        assertThat(spec.get("info").get("version").asString()).isEqualTo("v1");
        assertThat(spec.get("servers").get(0).get("url").asString())
                .as("契约不能写入 MockMvc 推导出的 http://localhost，否则生成客户端会绕过同源部署约定")
                .isEqualTo("/");
    }

    /**
     * 菜单自引用类型必须在契约里表达为{@code $ref}数组。
     *
     * @throws Exception springdoc读取或JSON解析失败。
     */
    @Test
    @DisplayName("菜单树的自引用 children 表达为 $ref 数组")
    void recursiveSchemaKeepsItemType() throws Exception {
        assertRecursiveMenuSchema(PRETTY.readTree(fetchSpec()));
    }

    /**
     * 应用只读入口必须把三条路径、精确响应类型和Long字符串合同同时公开。
     *
     * <p>整份OpenAPI漂移比较只能证明生成文件跟随代码，不能证明代码没有把revision误生成为
     * JavaScript不安全的number，或把可空当前指针错误声明成必有UUID。</p>
     *
     * @throws Exception springdoc读取或JSON解析失败
     */
    @Test
    @DisplayName("应用管理只读路径与revision类型精确")
    void applicationManagementReadContractKeepsPathsAndRevisionStrings() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        String collection = "/api/v1/projects/{projectId}/applications";
        String item = collection + "/{applicationId}";
        String draft = item + "/draft";

        assertThat(paths.has(collection)).isTrue();
        assertThat(paths.has(item)).isTrue();
        assertThat(paths.has(draft)).isTrue();
        assertThat(paths.path(collection).has("get")).isTrue();
        assertThat(paths.path(item).has("get")).isTrue();
        assertThat(paths.path(draft).has("get")).isTrue();

        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode catalog = schemas.path("ApplicationCatalogResponse").path("properties");
        JsonNode draftResponse = schemas.path("ApplicationDraftResponse").path("properties");
        assertThat(catalog.path("publicationRevision").path("type").asString()).isEqualTo("string");
        assertThat(draftResponse.path("revision").path("type").asString()).isEqualTo("string");
        JsonNode currentVersionTypes = catalog.path("currentVersionId").path("type");
        assertThat(java.util.stream.StreamSupport.stream(currentVersionTypes.spliterator(), false)
                .map(JsonNode::asString).toList())
                .as("未发布或已撤回应用的当前版本必须在OpenAPI中允许null")
                .containsExactlyInAnyOrder("string", "null");
        assertThat(catalog.path("currentVersionId").path("format").asString()).isEqualTo("uuid");

        JsonNode listSchema = successSchema(paths.path(collection).path("get"));
        String pageReference = listSchema.path("$ref").asString();
        assertThat(pageReference).startsWith("#/components/schemas/");
        JsonNode page = schemas.path(pageReference.substring(pageReference.lastIndexOf('/') + 1));
        assertThat(page.path("properties").path("items").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationCatalogResponse");
        assertThat(successSchema(paths.path(item).path("get")).path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationCatalogResponse");
        assertThat(successSchema(paths.path(draft).path("get")).path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationDraftResponse");
    }

    /** 应用历史必须分页公开轻量摘要，完整不可变快照只出现在精确详情。 */
    @Test
    @DisplayName("应用历史版本路径、分页与最小DTO精确")
    void applicationVersionHistoryContractKeepsPaginationAndPrivateFields() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        JsonNode schemas = spec.path("components").path("schemas");
        String versions = "/api/v1/projects/{projectId}/applications/{applicationId}/versions";
        String version = versions + "/{versionId}";
        JsonNode list = paths.path(versions).path("get");
        JsonNode detail = paths.path(version).path("get");

        assertThat(list.isMissingNode()).isFalse();
        assertThat(detail.isMissingNode()).isFalse();
        assertThat(list.path("operationId").asString()).isEqualTo("listApplicationVersions");
        assertThat(detail.path("operationId").asString()).isEqualTo("getApplicationVersion");

        JsonNode cursor = parameter(list, "cursor", "query");
        assertThat(cursor.path("required").asBoolean()).isFalse();
        assertThat(cursor.path("schema").path("type").asString()).isEqualTo("string");
        JsonNode limit = parameter(list, "limit", "query");
        assertThat(limit.path("required").asBoolean()).isFalse();
        assertThat(limit.path("schema").path("type").asString()).isEqualTo("integer");
        assertThat(limit.path("schema").path("default").asInt()).isEqualTo(50);
        assertThat(limit.path("schema").path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("schema").path("maximum").asInt()).isEqualTo(200);

        for (String name : List.of("projectId", "applicationId")) {
            assertUuidPathParameter(list, name);
            assertUuidPathParameter(detail, name);
        }
        assertUuidPathParameter(detail, "versionId");
        for (JsonNode operation : List.of(list, detail)) {
            assertThat(java.util.stream.StreamSupport.stream(operation.path("parameters").spliterator(), false)
                    .noneMatch(value -> "Idempotency-Key".equals(value.path("name").asString())))
                    .isTrue();
        }

        JsonNode pageSchema = successSchema(list);
        String pageReference = pageSchema.path("$ref").asString();
        assertThat(pageReference).startsWith("#/components/schemas/");
        JsonNode page = schemas.path(pageReference.substring(pageReference.lastIndexOf('/') + 1));
        assertThat(page.path("properties").path("items").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationVersionSummaryResponse");
        assertThat(successSchema(detail).path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationVersionResponse");

        JsonNode summary = schemas.path("ApplicationVersionSummaryResponse");
        JsonNode summaryProperties = summary.path("properties");
        assertThat(summaryProperties.propertyNames()).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision",
                "snapshotDigestAlgorithm", "snapshotDigest", "publishedAt");
        assertThat(summaryProperties.path("id").path("format").asString()).isEqualTo("uuid");
        assertThat(summaryProperties.path("versionNumber").path("type").asString()).isEqualTo("string");
        assertThat(summaryProperties.path("sourceDraftRevision").path("type").asString()).isEqualTo("string");
        assertThat(summaryProperties.path("publishedAt").path("format").asString()).isEqualTo("date-time");
        assertThat(summary.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(
                        "id", "versionNumber", "sourceDraftRevision",
                        "snapshotDigestAlgorithm", "snapshotDigest", "publishedAt");

        JsonNode full = schemas.path("ApplicationVersionResponse");
        JsonNode fullProperties = full.path("properties");
        assertThat(fullProperties.propertyNames()).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision", "snapshot",
                "snapshotDigestAlgorithm", "snapshotDigest", "publishedAt");
        assertThat(fullProperties.path("id").path("format").asString()).isEqualTo("uuid");
        assertThat(fullProperties.path("versionNumber").path("type").asString()).isEqualTo("string");
        assertThat(fullProperties.path("sourceDraftRevision").path("type").asString()).isEqualTo("string");
        assertThat(fullProperties.path("snapshot").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationSnapshotResponse");
        assertThat(fullProperties.path("publishedAt").path("format").asString()).isEqualTo("date-time");
        assertThat(full.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(
                        "id", "versionNumber", "sourceDraftRevision", "snapshot",
                        "snapshotDigestAlgorithm", "snapshotDigest", "publishedAt");

        JsonNode snapshot = schemas.path("ApplicationSnapshotResponse");
        JsonNode snapshotProperties = snapshot.path("properties");
        assertThat(snapshotProperties.propertyNames()).containsExactlyInAnyOrder(
                "formatVersion", "displayName", "hostCompatibility", "dashboardRefs",
                "entryDashboardId", "requiredSchemas", "requiredComponents", "requiredResources");
        assertThat(snapshot.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(
                        "formatVersion", "displayName", "hostCompatibility", "dashboardRefs",
                        "entryDashboardId", "requiredSchemas", "requiredComponents", "requiredResources");
        assertThat(snapshotProperties.path("formatVersion").path("enum").valueStream()
                .map(JsonNode::asString).toList()).containsExactly("tc.application/v1");
        assertThat(snapshotProperties.path("entryDashboardId").path("format").asString()).isEqualTo("uuid");
        assertThat(snapshotProperties.path("hostCompatibility").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationSnapshotHostCompatibilityResponse");
        assertThat(snapshotProperties.path("dashboardRefs").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationSnapshotDashboardReferenceResponse");
        assertThat(snapshotProperties.path("requiredComponents").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationSnapshotRequiredComponentResponse");
        assertThat(snapshotProperties.path("requiredResources").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationSnapshotRequiredResourceResponse");
        assertThat(fullProperties.path("snapshot").toString()).doesNotContain("JsonNode");
        assertApplicationSnapshotNestedSchemas(schemas);
    }

    /** 应用发布在既有历史集合POST上公开双字符串revision、可选幂等键、201正文和稳定Location。 */
    @Test
    @DisplayName("应用发布OpenAPI合同精确")
    void applicationPublicationContractKeepsRevisionsIdempotencyAndLocation() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        JsonNode schemas = spec.path("components").path("schemas");
        String collection = "/api/v1/projects/{projectId}/applications/{applicationId}/versions";
        String detailPath = collection + "/{versionId}";
        JsonNode publish = paths.path(collection).path("post");

        assertThat(publish.isMissingNode()).isFalse();
        assertThat(publish.path("operationId").asString()).isEqualTo("publishApplicationVersion");
        assertThat(paths.path(collection).path("get").path("operationId").asString())
                .isEqualTo("listApplicationVersions");
        assertThat(paths.path(detailPath).path("get").path("operationId").asString())
                .isEqualTo("getApplicationVersion");
        assertUuidPathParameter(publish, "projectId");
        assertUuidPathParameter(publish, "applicationId");

        JsonNode keyHeader = parameter(publish, "Idempotency-Key", "header");
        assertThat(keyHeader.path("required").asBoolean()).isFalse();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");

        assertThat(publish.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(publish).path("$ref").asString())
                .isEqualTo("#/components/schemas/PublishApplicationVersionRequest");
        JsonNode request = schemas.path("PublishApplicationVersionRequest");
        assertThat(request.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "expectedDraftRevision", "expectedPublicationRevision");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("expectedDraftRevision", "expectedPublicationRevision");
        assertThat(request.path("properties").path("expectedDraftRevision").path("type").asString())
                .isEqualTo("string");
        assertThat(request.path("properties").path("expectedPublicationRevision").path("type").asString())
                .isEqualTo("string");

        JsonNode responses = publish.path("responses");
        assertThat(responses.has("201")).isTrue();
        assertThat(responses.has("200")).isFalse();
        JsonNode created = responses.path("201");
        assertThat(created.path("headers").path("Location").path("schema").path("type").asString())
                .isEqualTo("string");
        assertThat(created.path("headers").path("Location").path("schema").path("format").asString())
                .isEqualTo("uri");
        assertThat(created.path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationVersionResponse");
    }

    /** 应用回滚公开单字符串revision、可选幂等键和200目标版本，且不得误带创建型Location。 */
    @Test
    @DisplayName("应用回滚OpenAPI合同精确")
    void applicationRollbackContractKeepsRevisionIdempotencyAndResponse() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        JsonNode schemas = spec.path("components").path("schemas");
        String collection = "/api/v1/projects/{projectId}/applications/{applicationId}/versions";
        String detail = collection + "/{versionId}";
        String rollbackPath = detail + "/rollback";
        JsonNode rollback = paths.path(rollbackPath).path("post");

        assertThat(rollback.isMissingNode()).isFalse();
        assertThat(rollback.path("operationId").asString()).isEqualTo("rollbackApplicationVersion");
        assertThat(paths.path(collection).path("get").path("operationId").asString())
                .isEqualTo("listApplicationVersions");
        assertThat(paths.path(detail).path("get").path("operationId").asString())
                .isEqualTo("getApplicationVersion");
        assertThat(paths.path(collection).path("post").path("operationId").asString())
                .isEqualTo("publishApplicationVersion");
        assertUuidPathParameter(rollback, "projectId");
        assertUuidPathParameter(rollback, "applicationId");
        assertUuidPathParameter(rollback, "versionId");

        JsonNode keyHeader = parameter(rollback, "Idempotency-Key", "header");
        assertThat(keyHeader.path("required").asBoolean()).isFalse();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");

        assertThat(rollback.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(rollback).path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationPublicationRevisionRequest");
        JsonNode request = schemas.path("ApplicationPublicationRevisionRequest");
        assertThat(request.path("properties").propertyNames())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("properties").path("expectedPublicationRevision").path("type").asString())
                .isEqualTo("string");

        JsonNode responses = rollback.path("responses");
        assertThat(responses.has("200")).isTrue();
        assertThat(responses.has("201")).isFalse();
        assertThat(responses.has("204")).isFalse();
        JsonNode ok = responses.path("200");
        assertThat(ok.path("headers").has("Location")).isFalse();
        assertThat(ok.path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationVersionResponse");
    }

    /** 应用撤回复用单字符串revision并返回无正文无Location的204，不引入Host资格字段。 */
    @Test
    @DisplayName("应用撤回OpenAPI合同精确")
    void applicationWithdrawalContractKeepsRevisionIdempotencyAndEmptyResponse() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String withdrawalPath = "/api/v1/projects/{projectId}/applications/{applicationId}/withdraw";
        JsonNode withdrawal = spec.path("paths").path(withdrawalPath).path("post");

        assertThat(withdrawal.isMissingNode()).isFalse();
        assertThat(withdrawal.path("operationId").asString()).isEqualTo("withdrawApplicationPublication");
        assertUuidPathParameter(withdrawal, "projectId");
        assertUuidPathParameter(withdrawal, "applicationId");

        JsonNode keyHeader = parameter(withdrawal, "Idempotency-Key", "header");
        assertThat(keyHeader.path("required").asBoolean()).isFalse();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");

        assertThat(withdrawal.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(withdrawal).path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationPublicationRevisionRequest");
        JsonNode request = schemas.path("ApplicationPublicationRevisionRequest");
        assertThat(request.path("properties").propertyNames())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("properties").path("expectedPublicationRevision").path("type").asString())
                .isEqualTo("string");

        JsonNode responses = withdrawal.path("responses");
        assertThat(responses.has("204")).isTrue();
        assertThat(responses.has("200")).isFalse();
        assertThat(responses.has("201")).isFalse();
        JsonNode noContent = responses.path("204");
        assertThat(noContent.path("content").isMissingNode()).isTrue();
        assertThat(noContent.path("headers").has("Location")).isFalse();
    }

    /** 最新导出任务是有界只读入口，200含任务投影，204无正文，不声明分页或下载能力。 */
    @Test
    void projectExportLatestReadHasTaskOrNoContentContract() throws Exception {
        var spec = PRETTY.readTree(fetchSpec());
        var operation = spec.path("paths").path("/api/v1/projects/{projectId}/exports/latest").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("latestProjectExport");
        assertThat(operation.path("responses").path("200").path("content").path("application/json")
                .path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/ProjectExportResponse");
        assertThat(operation.path("responses").path("204").path("content").isMissingNode()).isTrue();
        assertThat(operation.path("parameters").size()).isEqualTo(1);
    }

    /** 应用软删复用单字符串revision、可选公共幂等键和无正文无Location的204。 */
    @Test
    @DisplayName("应用软删OpenAPI合同精确")
    void applicationSoftDeleteContractKeepsRevisionIdempotencyAndEmptyResponse() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String softDeletePath = "/api/v1/projects/{projectId}/applications/{applicationId}/soft-delete";
        JsonNode softDelete = spec.path("paths").path(softDeletePath).path("post");

        assertThat(softDelete.isMissingNode()).isFalse();
        assertThat(softDelete.path("operationId").asString()).isEqualTo("softDeleteApplication");
        assertUuidPathParameter(softDelete, "projectId");
        assertUuidPathParameter(softDelete, "applicationId");

        JsonNode keyHeader = parameter(softDelete, "Idempotency-Key", "header");
        assertThat(keyHeader.path("required").asBoolean()).isFalse();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");

        assertThat(softDelete.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(softDelete).path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationPublicationRevisionRequest");
        JsonNode request = schemas.path("ApplicationPublicationRevisionRequest");
        assertThat(request.path("properties").propertyNames())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("properties").path("expectedPublicationRevision").path("type").asString())
                .isEqualTo("string");

        JsonNode responses = softDelete.path("responses");
        assertThat(responses.has("204")).isTrue();
        assertThat(responses.has("200")).isFalse();
        assertThat(responses.has("201")).isFalse();
        JsonNode noContent = responses.path("204");
        assertThat(noContent.path("content").isMissingNode()).isTrue();
        assertThat(noContent.path("headers").has("Location")).isFalse();
    }

    /** 应用快照嵌套对象必须保持第3.2节字段闭集和必填性。 */
    private static void assertApplicationSnapshotNestedSchemas(JsonNode schemas) {
        assertExactRequiredSchema(schemas.path("ApplicationSnapshotHostCompatibilityResponse"),
                "minInclusive", "maxExclusive");
        JsonNode reference = schemas.path("ApplicationSnapshotDashboardReferenceResponse");
        assertExactRequiredSchema(reference,
                "dashboardId", "dashboardVersionId", "dashboardVersionNumber", "title",
                "schemaVersion", "schemaDigestAlgorithm", "schemaDigest", "pages");
        assertThat(reference.path("properties").path("dashboardId").path("format").asString()).isEqualTo("uuid");
        assertThat(reference.path("properties").path("dashboardVersionId").path("format").asString())
                .isEqualTo("uuid");
        assertThat(reference.path("properties").path("dashboardVersionNumber").path("type").asString())
                .isEqualTo("string");
        assertThat(reference.path("properties").path("pages").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationSnapshotPageResponse");
        assertExactRequiredSchema(schemas.path("ApplicationSnapshotPageResponse"), "id", "title");
        assertExactRequiredSchema(schemas.path("ApplicationSnapshotRequiredComponentResponse"),
                "kind", "componentVersion");
        assertExactRequiredSchema(schemas.path("ApplicationSnapshotRequiredResourceResponse"),
                "resourceId", "digest");
    }

    /** 断言对象Schema的属性闭集与必填闭集完全一致。 */
    private static void assertExactRequiredSchema(JsonNode schema, String... fields) {
        assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrder(fields);
        assertThat(schema.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(fields);
    }

    /** 看板只读入口必须公开三条路径、最小DTO、分页范围和Long字符串合同。 */
    @Test
    @DisplayName("看板管理只读路径与revision类型精确")
    void dashboardManagementReadContractKeepsPathsAndRevisionStrings() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        JsonNode schemas = spec.path("components").path("schemas");
        String collection = "/api/v1/projects/{projectId}/dashboards";
        String item = collection + "/{dashboardId}";
        String draft = item + "/draft";

        JsonNode list = paths.path(collection).path("get");
        assertThat(list.isMissingNode()).isFalse();
        assertThat(paths.path(item).has("get")).isTrue();
        assertThat(paths.path(draft).has("get")).isTrue();
        assertThat(list.path("operationId").asString()).isEqualTo("listDashboards");
        assertThat(paths.path(item).path("get").path("operationId").asString()).isEqualTo("find_1");
        assertThat(paths.path(draft).path("get").path("operationId").asString()).isEqualTo("getDraft_1");

        JsonNode limit = java.util.stream.StreamSupport.stream(list.path("parameters").spliterator(), false)
                .filter(parameter -> "limit".equals(parameter.path("name").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板目录缺少limit参数"));
        assertThat(limit.path("schema").path("default").asInt()).isEqualTo(50);
        assertThat(limit.path("schema").path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("schema").path("maximum").asInt()).isEqualTo(200);

        JsonNode catalogSchema = schemas.path("DashboardCatalogResponse");
        JsonNode catalog = catalogSchema.path("properties");
        assertThat(catalog.propertyNames()).containsExactlyInAnyOrder(
                "id", "managementName", "publicationRevision", "currentVersionId", "createdAt", "updatedAt");
        assertThat(catalog.path("publicationRevision").path("type").asString()).isEqualTo("string");
        JsonNode currentVersionTypes = catalog.path("currentVersionId").path("type");
        assertThat(java.util.stream.StreamSupport.stream(currentVersionTypes.spliterator(), false)
                .map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
        assertThat(catalog.path("currentVersionId").path("format").asString()).isEqualTo("uuid");

        JsonNode draftSchema = schemas.path("DashboardDraftResponse");
        JsonNode draftProperties = draftSchema.path("properties");
        assertThat(draftProperties.propertyNames()).containsExactlyInAnyOrder(
                "dashboardId", "content", "revision", "updatedAt");
        assertThat(draftProperties.path("content").path("type").asString()).isEqualTo("object");
        assertThat(draftProperties.path("revision").path("type").asString()).isEqualTo("string");

        JsonNode listSchema = successSchema(list);
        String pageReference = listSchema.path("$ref").asString();
        assertThat(pageReference).startsWith("#/components/schemas/");
        JsonNode page = schemas.path(pageReference.substring(pageReference.lastIndexOf('/') + 1));
        assertThat(page.path("properties").path("items").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardCatalogResponse");
        assertThat(successSchema(paths.path(item).path("get")).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardCatalogResponse");
        assertThat(successSchema(paths.path(draft).path("get")).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardDraftResponse");
    }

    /** 看板历史必须分页公开轻量摘要，并把完整不可变内容限制在精确详情。 */
    @Test
    @DisplayName("看板历史版本路径、分页与最小DTO精确")
    void dashboardVersionHistoryContractKeepsPaginationAndPrivateFields() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        JsonNode schemas = spec.path("components").path("schemas");
        String versions = "/api/v1/projects/{projectId}/dashboards/{dashboardId}/versions";
        String version = versions + "/{versionId}";
        JsonNode list = paths.path(versions).path("get");
        JsonNode detail = paths.path(version).path("get");

        assertThat(list.isMissingNode()).isFalse();
        assertThat(detail.isMissingNode()).isFalse();
        assertThat(list.path("operationId").asString()).isEqualTo("listDashboardVersions");
        assertThat(detail.path("operationId").asString()).isEqualTo("getDashboardVersion");
        JsonNode limit = java.util.stream.StreamSupport.stream(list.path("parameters").spliterator(), false)
                .filter(parameter -> "limit".equals(parameter.path("name").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板历史缺少limit参数"));
        assertThat(limit.path("schema").path("default").asInt()).isEqualTo(50);
        assertThat(limit.path("schema").path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("schema").path("maximum").asInt()).isEqualTo(200);
        for (JsonNode operation : List.of(list, detail)) {
            assertThat(java.util.stream.StreamSupport.stream(operation.path("parameters").spliterator(), false)
                    .noneMatch(parameter -> "Idempotency-Key".equals(parameter.path("name").asString())))
                    .isTrue();
        }

        JsonNode pageSchema = successSchema(list);
        String pageReference = pageSchema.path("$ref").asString();
        assertThat(pageReference).startsWith("#/components/schemas/");
        JsonNode page = schemas.path(pageReference.substring(pageReference.lastIndexOf('/') + 1));
        assertThat(page.path("properties").path("items").path("items").path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardVersionSummaryResponse");
        assertThat(successSchema(detail).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardVersionResponse");

        JsonNode summary = schemas.path("DashboardVersionSummaryResponse");
        JsonNode summaryProperties = summary.path("properties");
        assertThat(summaryProperties.propertyNames()).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision", "schemaVersion",
                "schemaDigestAlgorithm", "schemaDigest", "publishedAt");
        assertThat(summaryProperties.path("id").path("format").asString()).isEqualTo("uuid");
        assertThat(summaryProperties.path("versionNumber").path("type").asString()).isEqualTo("string");
        assertThat(summaryProperties.path("sourceDraftRevision").path("type").asString()).isEqualTo("string");
        assertThat(summaryProperties.path("publishedAt").path("format").asString()).isEqualTo("date-time");

        JsonNode full = schemas.path("DashboardVersionResponse");
        JsonNode fullProperties = full.path("properties");
        assertThat(fullProperties.propertyNames()).containsExactlyInAnyOrder(
                "id", "versionNumber", "sourceDraftRevision", "schema", "schemaVersion",
                "schemaDigestAlgorithm", "schemaDigest", "requiredComponents", "requiredResources",
                "publishedAt");
        assertThat(fullProperties.path("versionNumber").path("type").asString()).isEqualTo("string");
        assertThat(fullProperties.path("sourceDraftRevision").path("type").asString()).isEqualTo("string");
        assertThat(fullProperties.path("schema").path("type").asString()).isEqualTo("object");
        assertThat(fullProperties.path("requiredComponents").path("type").asString()).isEqualTo("array");
        assertThat(fullProperties.path("requiredResources").path("type").asString()).isEqualTo("array");

        // 保留1e1a既有操作，目录仅按D-144迁移为唯一listDashboards。
        String dashboard = "/api/v1/projects/{projectId}/dashboards";
        assertThat(paths.path(dashboard).path("post").path("operationId").asString())
                .isEqualTo("createDashboard");
        assertThat(paths.path(dashboard).path("get").path("operationId").asString()).isEqualTo("listDashboards");
        assertThat(paths.path(dashboard + "/{dashboardId}").path("get").path("operationId").asString())
                .isEqualTo("find_1");
        assertThat(paths.path(dashboard + "/{dashboardId}/draft").path("get").path("operationId").asString())
                .isEqualTo("getDraft_1");
        assertThat(paths.path(dashboard + "/{dashboardId}").path("patch").path("operationId").asString())
                .isEqualTo("renameDashboard");
        assertThat(paths.path(dashboard + "/{dashboardId}/draft").path("put").path("operationId").asString())
                .isEqualTo("saveDashboardDraft");
        assertThat(paths.path("/api/v1/projects/{projectId}/applications").path("post")
                .path("operationId").asString()).isEqualTo("create_10");
    }

    /** 看板发布必须公开双字符串revision、可选公共幂等键、唯一201版本正文和稳定Location。 */
    @Test
    @DisplayName("看板发布OpenAPI合同精确")
    void dashboardPublicationContractKeepsRevisionsIdempotencyAndLocation() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        JsonNode schemas = spec.path("components").path("schemas");
        String collection = "/api/v1/projects/{projectId}/dashboards/{dashboardId}/versions";
        JsonNode publish = paths.path(collection).path("post");

        assertThat(publish.isMissingNode()).isFalse();
        assertThat(publish.path("operationId").asString()).isEqualTo("publishDashboardVersion");
        JsonNode keyHeader = java.util.stream.StreamSupport.stream(
                        publish.path("parameters").spliterator(), false)
                .filter(parameter -> "Idempotency-Key".equals(parameter.path("name").asString()))
                .filter(parameter -> "header".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板发布缺少可选Idempotency-Key请求头"));
        assertThat(keyHeader.path("required").asBoolean()).isFalse();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");

        assertThat(publish.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(publish).path("$ref").asString())
                .isEqualTo("#/components/schemas/PublishDashboardVersionRequest");
        JsonNode request = schemas.path("PublishDashboardVersionRequest");
        assertThat(request.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "expectedDraftRevision", "expectedPublicationRevision");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("expectedDraftRevision", "expectedPublicationRevision");
        assertThat(request.path("properties").path("expectedDraftRevision").path("type").asString())
                .isEqualTo("string");
        assertThat(request.path("properties").path("expectedPublicationRevision").path("type").asString())
                .isEqualTo("string");

        JsonNode responses = publish.path("responses");
        assertThat(responses.has("201")).isTrue();
        assertThat(responses.has("200")).isFalse();
        JsonNode created = responses.path("201");
        assertThat(created.path("headers").path("Location").path("schema").path("type").asString())
                .isEqualTo("string");
        assertThat(created.path("headers").path("Location").path("schema").path("format").asString())
                .isEqualTo("uri");
        assertThat(created.path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardVersionResponse");
    }

    /** 看板回滚必须公开单字符串revision、可选公共幂等键和唯一200目标版本正文。 */
    @Test
    @DisplayName("看板回滚OpenAPI合同精确")
    void dashboardRollbackContractKeepsRevisionIdempotencyAndResponse() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String rollbackPath = "/api/v1/projects/{projectId}/dashboards/{dashboardId}"
                + "/versions/{versionId}/rollback";
        JsonNode rollback = spec.path("paths").path(rollbackPath).path("post");

        assertThat(rollback.isMissingNode()).isFalse();
        assertThat(rollback.path("operationId").asString()).isEqualTo("rollbackDashboardVersion");
        JsonNode keyHeader = java.util.stream.StreamSupport.stream(
                        rollback.path("parameters").spliterator(), false)
                .filter(parameter -> "Idempotency-Key".equals(parameter.path("name").asString()))
                .filter(parameter -> "header".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板回滚缺少可选Idempotency-Key请求头"));
        assertThat(keyHeader.path("required").asBoolean()).isFalse();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");
        JsonNode versionId = java.util.stream.StreamSupport.stream(
                        rollback.path("parameters").spliterator(), false)
                .filter(parameter -> "versionId".equals(parameter.path("name").asString()))
                .filter(parameter -> "path".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板回滚缺少versionId路径参数"));
        assertThat(versionId.path("required").asBoolean()).isTrue();
        assertThat(versionId.path("schema").path("format").asString()).isEqualTo("uuid");

        assertThat(rollback.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(rollback).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardPublicationRevisionRequest");
        JsonNode request = schemas.path("DashboardPublicationRevisionRequest");
        assertThat(request.path("properties").propertyNames())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("properties").path("expectedPublicationRevision")
                .path("type").asString()).isEqualTo("string");

        JsonNode responses = rollback.path("responses");
        assertThat(responses.has("200")).isTrue();
        assertThat(responses.has("201")).isFalse();
        assertThat(successSchema(rollback).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardVersionResponse");
        assertThat(responses.path("200").path("headers").has("Location")).isFalse();
    }

    /** 看板撤回必须公开单字符串revision、可选公共幂等键和无正文无Location的204。 */
    @Test
    @DisplayName("看板撤回OpenAPI合同精确")
    void dashboardWithdrawalContractKeepsRevisionIdempotencyAndEmptyResponse() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String withdrawalPath = "/api/v1/projects/{projectId}/dashboards/{dashboardId}/withdraw";
        JsonNode withdrawal = spec.path("paths").path(withdrawalPath).path("post");

        assertThat(withdrawal.isMissingNode()).isFalse();
        assertThat(withdrawal.path("operationId").asString()).isEqualTo("withdrawDashboardPublication");
        JsonNode keyHeader = java.util.stream.StreamSupport.stream(
                        withdrawal.path("parameters").spliterator(), false)
                .filter(parameter -> "Idempotency-Key".equals(parameter.path("name").asString()))
                .filter(parameter -> "header".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板撤回缺少可选Idempotency-Key请求头"));
        assertThat(keyHeader.path("required").asBoolean()).isFalse();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");
        JsonNode dashboardId = java.util.stream.StreamSupport.stream(
                        withdrawal.path("parameters").spliterator(), false)
                .filter(parameter -> "dashboardId".equals(parameter.path("name").asString()))
                .filter(parameter -> "path".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板撤回缺少dashboardId路径参数"));
        assertThat(dashboardId.path("required").asBoolean()).isTrue();
        assertThat(dashboardId.path("schema").path("format").asString()).isEqualTo("uuid");

        assertThat(withdrawal.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(withdrawal).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardPublicationRevisionRequest");
        JsonNode request = schemas.path("DashboardPublicationRevisionRequest");
        assertThat(request.path("properties").propertyNames())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("properties").path("expectedPublicationRevision")
                .path("type").asString()).isEqualTo("string");

        JsonNode responses = withdrawal.path("responses");
        assertThat(responses.has("204")).isTrue();
        assertThat(responses.has("200")).isFalse();
        assertThat(responses.has("201")).isFalse();
        JsonNode noContent = responses.path("204");
        assertThat(noContent.path("content").isMissingNode()).isTrue();
        assertThat(noContent.path("headers").has("Location")).isFalse();
    }

    /** 看板软删必须复用单字符串revision、可选公共幂等键和无正文无Location的204。 */
    @Test
    @DisplayName("看板软删OpenAPI合同精确")
    void dashboardSoftDeleteContractKeepsRevisionIdempotencyAndEmptyResponse() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String softDeletePath = "/api/v1/projects/{projectId}/dashboards/{dashboardId}/soft-delete";
        JsonNode softDelete = spec.path("paths").path(softDeletePath).path("post");

        assertThat(softDelete.isMissingNode()).isFalse();
        assertThat(softDelete.path("operationId").asString()).isEqualTo("softDeleteDashboard");
        JsonNode keyHeader = java.util.stream.StreamSupport.stream(
                        softDelete.path("parameters").spliterator(), false)
                .filter(parameter -> "Idempotency-Key".equals(parameter.path("name").asString()))
                .filter(parameter -> "header".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板软删缺少可选Idempotency-Key请求头"));
        assertThat(keyHeader.path("required").asBoolean()).isFalse();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");
        JsonNode dashboardId = java.util.stream.StreamSupport.stream(
                        softDelete.path("parameters").spliterator(), false)
                .filter(parameter -> "dashboardId".equals(parameter.path("name").asString()))
                .filter(parameter -> "path".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板软删缺少dashboardId路径参数"));
        assertThat(dashboardId.path("required").asBoolean()).isTrue();
        assertThat(dashboardId.path("schema").path("format").asString()).isEqualTo("uuid");

        assertThat(softDelete.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(softDelete).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardPublicationRevisionRequest");
        JsonNode request = schemas.path("DashboardPublicationRevisionRequest");
        assertThat(request.path("properties").propertyNames())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("expectedPublicationRevision");
        assertThat(request.path("properties").path("expectedPublicationRevision")
                .path("type").asString()).isEqualTo("string");

        JsonNode responses = softDelete.path("responses");
        assertThat(responses.has("204")).isTrue();
        assertThat(responses.has("200")).isFalse();
        assertThat(responses.has("201")).isFalse();
        JsonNode noContent = responses.path("204");
        assertThat(noContent.path("content").isMissingNode()).isTrue();
        assertThat(noContent.path("headers").has("Location")).isFalse();
    }

    /** 看板更新入口必须公开严格信封，尤其不能把CAS revision生成为JavaScript number。 */
    @Test
    @DisplayName("看板管理写路径与严格信封类型精确")
    void dashboardManagementWriteContractKeepsEnvelopeTypes() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        JsonNode schemas = spec.path("components").path("schemas");
        String item = "/api/v1/projects/{projectId}/dashboards/{dashboardId}";
        String draft = item + "/draft";

        JsonNode rename = paths.path(item).path("patch");
        JsonNode save = paths.path(draft).path("put");
        assertThat(rename.path("operationId").asString()).isEqualTo("renameDashboard");
        assertThat(save.path("operationId").asString()).isEqualTo("saveDashboardDraft");
        assertThat(requestSchema(rename).path("$ref").asString())
                .isEqualTo("#/components/schemas/RenameDashboardRequest");
        assertThat(requestSchema(save).path("$ref").asString())
                .isEqualTo("#/components/schemas/SaveDashboardDraftRequest");
        assertThat(successSchema(rename).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardCatalogResponse");
        assertThat(successSchema(save).path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardDraftResponse");

        JsonNode renameEnvelope = schemas.path("RenameDashboardRequest");
        JsonNode draftEnvelope = schemas.path("SaveDashboardDraftRequest");
        assertThat(renameEnvelope.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("managementName");
        assertThat(draftEnvelope.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("expectedRevision", "content");
        assertThat(draftEnvelope.path("properties").path("expectedRevision").path("type").asString())
                .isEqualTo("string");
        assertThat(draftEnvelope.path("properties").path("content").path("type").asString())
                .isEqualTo("object");

        // Dashboard新增同名Java方法不得反向改写已发布的Application客户端操作名称。
        String application = "/api/v1/projects/{projectId}/applications/{applicationId}";
        assertThat(paths.path(application).path("get").path("operationId").asString()).isEqualTo("find");
        assertThat(paths.path(application).path("patch").path("operationId").asString()).isEqualTo("rename");
        assertThat(paths.path(application + "/draft").path("get").path("operationId").asString())
                .isEqualTo("getDraft");
        assertThat(paths.path(application + "/draft").path("put").path("operationId").asString())
                .isEqualTo("saveDraft");
    }

    /** 看板创建必须公开必填幂等键、严格创建信封、唯一201成功响应和稳定Location。 */
    @Test
    @DisplayName("看板创建OpenAPI合同精确")
    void dashboardCreationContractKeepsIdempotencyReceiptAndLocation() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode paths = spec.path("paths");
        String collection = "/api/v1/projects/{projectId}/dashboards";
        JsonNode create = paths.path(collection).path("post");

        assertThat(create.isMissingNode()).isFalse();
        assertThat(create.path("operationId").asString()).isEqualTo("createDashboard");
        JsonNode keyHeader = java.util.stream.StreamSupport.stream(
                        create.path("parameters").spliterator(), false)
                .filter(parameter -> "Idempotency-Key".equals(parameter.path("name").asString()))
                .filter(parameter -> "header".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("看板创建缺少Idempotency-Key请求头"));
        assertThat(keyHeader.path("required").asBoolean()).isTrue();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");

        assertThat(create.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(create).path("$ref").asString())
                .isEqualTo("#/components/schemas/CreateDashboardRequest");
        JsonNode request = schemas.path("CreateDashboardRequest");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("managementName", "content");
        assertThat(request.path("properties").path("managementName").path("type").asString())
                .isEqualTo("string");
        assertThat(request.path("properties").path("content").path("type").asString())
                .isEqualTo("object");

        JsonNode responses = create.path("responses");
        assertThat(responses.has("201")).isTrue();
        assertThat(responses.has("200")).isFalse();
        JsonNode created = responses.path("201");
        assertThat(created.path("headers").path("Location").path("schema").path("type").asString())
                .isEqualTo("string");
        assertThat(created.path("headers").path("Location").path("schema").path("format").asString())
                .isEqualTo("uri");
        assertThat(created.path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/DashboardCreationResponse");

        JsonNode response = schemas.path("DashboardCreationResponse");
        JsonNode receipt = response.path("properties");
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder("id", "createdAt");
        assertThat(receipt.path("id").path("type").asString()).isEqualTo("string");
        assertThat(receipt.path("id").path("format").asString()).isEqualTo("uuid");
        assertThat(receipt.path("createdAt").path("type").asString()).isEqualTo("string");
        assertThat(receipt.path("createdAt").path("format").asString()).isEqualTo("date-time");
        assertThat(response.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("id", "createdAt");

        // 保留既有创建操作；Dashboard目录按D-144显式迁移，不再与区域目录共用ID。
        assertThat(paths.path("/api/v1/projects/{projectId}/applications").path("post")
                .path("operationId").asString()).isEqualTo("create_10");
        assertThat(paths.path(collection).path("get").path("operationId").asString()).isEqualTo("listDashboards");
        String item = collection + "/{dashboardId}";
        assertThat(paths.path(item).path("get").path("operationId").asString()).isEqualTo("find_1");
        assertThat(paths.path(item + "/draft").path("get").path("operationId").asString())
                .isEqualTo("getDraft_1");
        assertThat(paths.path(item).path("patch").path("operationId").asString())
                .isEqualTo("renameDashboard");
        assertThat(paths.path(item + "/draft").path("put").path("operationId").asString())
                .isEqualTo("saveDashboardDraft");
    }

    /** 应用写入口必须公开严格信封，尤其不能把CAS revision生成为JavaScript number。 */
    @Test
    @DisplayName("应用管理写路径与严格信封类型精确")
    void applicationManagementWriteContractKeepsEnvelopeTypes() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths");
        JsonNode schemas = spec.path("components").path("schemas");
        String item = "/api/v1/projects/{projectId}/applications/{applicationId}";
        String draft = item + "/draft";

        JsonNode rename = paths.path(item).path("patch");
        JsonNode save = paths.path(draft).path("put");
        assertThat(requestSchema(rename).path("$ref").asString())
                .isEqualTo("#/components/schemas/RenameApplicationRequest");
        assertThat(requestSchema(save).path("$ref").asString())
                .isEqualTo("#/components/schemas/SaveApplicationDraftRequest");
        assertThat(successSchema(rename).path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationCatalogResponse");
        assertThat(successSchema(save).path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationDraftResponse");

        JsonNode renameEnvelope = schemas.path("RenameApplicationRequest");
        JsonNode draftEnvelope = schemas.path("SaveApplicationDraftRequest");
        assertThat(renameEnvelope.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("managementName");
        assertThat(draftEnvelope.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("expectedRevision", "content");
        assertThat(draftEnvelope.path("properties").path("expectedRevision").path("type").asString())
                .isEqualTo("string");
        assertThat(draftEnvelope.path("properties").path("content").path("type").asString())
                .isEqualTo("object");
    }

    /** 应用创建必须公开必填幂等键、严格创建信封、唯一201成功响应和稳定Location。 */
    @Test
    @DisplayName("应用创建OpenAPI合同精确")
    void applicationCreationContractKeepsIdempotencyReceiptAndLocation() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode create = spec.path("paths")
                .path("/api/v1/projects/{projectId}/applications").path("post");

        assertThat(create.isMissingNode()).isFalse();
        JsonNode keyHeader = java.util.stream.StreamSupport.stream(
                        create.path("parameters").spliterator(), false)
                .filter(parameter -> "Idempotency-Key".equals(parameter.path("name").asString()))
                .filter(parameter -> "header".equals(parameter.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应用创建缺少Idempotency-Key请求头"));
        assertThat(keyHeader.path("required").asBoolean()).isTrue();
        assertThat(keyHeader.path("schema").path("type").asString()).isEqualTo("string");

        assertThat(create.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(requestSchema(create).path("$ref").asString())
                .isEqualTo("#/components/schemas/CreateApplicationRequest");
        JsonNode request = schemas.path("CreateApplicationRequest");
        assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("managementName", "content");
        assertThat(request.path("properties").path("managementName").path("type").asString())
                .isEqualTo("string");
        assertThat(request.path("properties").path("content").path("type").asString())
                .isEqualTo("object");

        JsonNode responses = create.path("responses");
        assertThat(responses.has("201")).isTrue();
        assertThat(responses.has("200")).isFalse();
        JsonNode created = responses.path("201");
        assertThat(created.path("headers").path("Location").path("schema").path("type").asString())
                .isEqualTo("string");
        assertThat(created.path("headers").path("Location").path("schema").path("format").asString())
                .isEqualTo("uri");
        assertThat(created.path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApplicationCreationResponse");

        JsonNode receipt = schemas.path("ApplicationCreationResponse").path("properties");
        assertThat(receipt.size()).isEqualTo(3);
        assertThat(receipt.has("id")).isTrue();
        assertThat(receipt.has("appKey")).isTrue();
        assertThat(receipt.has("createdAt")).isTrue();
        assertThat(receipt.path("id").path("type").asString()).isEqualTo("string");
        assertThat(receipt.path("id").path("format").asString()).isEqualTo("uuid");
        assertThat(receipt.path("appKey").path("type").asString()).isEqualTo("string");
        assertThat(receipt.path("createdAt").path("type").asString()).isEqualTo("string");
        assertThat(receipt.path("createdAt").path("format").asString()).isEqualTo("date-time");
        assertThat(schemas.path("ApplicationCreationResponse").path("required")
                .valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("id", "appKey", "createdAt");
    }

    /** S12-2a2b公开resolve只能暴露规范AppKey的精确GET与三字段无缓存响应。 */
    @Test
    @DisplayName("App公开resolve路径、参数与响应合同精确")
    void webAppApplicationResolutionContractIsExactAndCacheFree() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode path = spec.path("paths")
                .path("/api/v1/app/applications/{appKey}/resolve");

        assertThat(path.isMissingNode()).isFalse();
        assertThat(path.propertyNames()).containsExactly("get");
        JsonNode resolve = path.path("get");
        assertThat(resolve.path("operationId").asString()).isEqualTo("resolveWebAppApplication");
        assertThat(operationIds(spec)
                .filter(value -> "resolveWebAppApplication".equals(value.asString())))
                .hasSize(1);

        JsonNode appKey = parameter(resolve, "appKey", "path");
        assertThat(appKey.path("required").asBoolean()).isTrue();
        assertThat(appKey.path("schema").path("type").asString()).isEqualTo("string");
        assertThat(appKey.path("schema").path("pattern").asString())
                .isEqualTo("^app_[0-9a-f]{32}$");
        assertThat(java.util.stream.StreamSupport.stream(resolve.path("parameters").spliterator(), false)
                .noneMatch(value -> "query".equals(value.path("in").asString())))
                .isTrue();
        assertThat(resolve.path("requestBody").isMissingNode()).isTrue();

        JsonNode success = resolve.path("responses").path("200");
        JsonNode cacheControl = success.path("headers").path("Cache-Control");
        assertThat(cacheControl.isMissingNode()).isFalse();
        assertThat(cacheControl.path("schema").path("enum").valueStream())
                .allMatch(JsonNode::isString);
        assertThat(cacheControl.path("schema").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactly("no-store");

        JsonNode successReference = successSchema(resolve);
        assertThat(successReference.path("$ref").asString()).startsWith("#/components/schemas/");
        String responseName = successReference.path("$ref").asString()
                .substring(successReference.path("$ref").asString().lastIndexOf('/') + 1);
        JsonNode response = schemas.path(responseName);
        assertThat(response.path("properties").propertyNames())
                .containsExactlyInAnyOrder("appKey", "displayName", "projectKey");
        assertThat(response.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("appKey", "displayName", "projectKey");
        for (String field : List.of("appKey", "displayName", "projectKey")) {
            assertThat(response.path("properties").path(field).path("type").asString()).isEqualTo("string");
        }

        JsonNode notFound = resolve.path("responses").path("404");
        assertThat(notFound.isMissingNode()).isFalse();
        assertThat(notFound.path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApiError");
    }

    /** S12-2a3b管理grant只暴露三个operationId、八字段事实与字符串CAS，不泄露内部操作者。 */
    @Test
    void appUserDashboardGrantManagementContractIsExact() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String base = "/api/v1/projects/{projectId}/end-users/{appUserId}/dashboard-grants";
        JsonNode collection = spec.path("paths").path(base);
        JsonNode item = spec.path("paths").path(base + "/{dashboardId}");
        assertThat(collection.propertyNames()).containsExactly("get");
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("get", "put");
        JsonNode list = collection.path("get");
        JsonNode get = item.path("get");
        JsonNode put = item.path("put");
        assertThat(list.path("operationId").asString()).isEqualTo("listAppUserDashboardGrants");
        assertThat(get.path("operationId").asString()).isEqualTo("getAppUserDashboardGrant");
        assertThat(put.path("operationId").asString()).isEqualTo("updateAppUserDashboardGrant");
        JsonNode limit = parameter(list, "limit", "query").path("schema");
        assertThat(limit.path("default").asInt()).isEqualTo(50);
        assertThat(limit.path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("maximum").asInt()).isEqualTo(200);
        assertThat(parameter(put, "Idempotency-Key", "header").path("required").asBoolean()).isFalse();
        assertThat(put.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/UpdateAppUserDashboardGrantRequest");
        JsonNode request = schemas.path("UpdateAppUserDashboardGrantRequest");
        assertExactRequiredSchema(request, "expectedRevision", "status");
        assertThat(request.path("properties").path("expectedRevision").path("type").asString()).isEqualTo("string");
        JsonNode response = schemas.path("AppUserDashboardGrantResponse");
        assertExactRequiredSchema(response, "appUserId", "dashboardId", "permission", "status", "revision",
                "createdAt", "updatedAt", "revokedAt");
        assertThat(response.path("properties").path("revision").path("type").asString()).isEqualTo("string");
        assertThat(response.path("properties").path("permission").path("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactly("READ");
        assertThat(response.path("properties").path("revokedAt").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
        for (JsonNode operation : List.of(get, put)) {
            assertThat(successSchema(operation).path("$ref").asString())
                    .isEqualTo("#/components/schemas/AppUserDashboardGrantResponse");
        }
    }

    /** 单类型核对仅返回公开投影，产品识别码真实空值进入生成合同。 */
    @Test
    void deviceTypeDetailContractKeepsNullablePublicProductKey() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        assertThat(spec.path("paths").path("/api/v1/projects/{projectId}/device-types/{id}").path("get").isMissingNode()).isFalse();
        JsonNode schema = spec.path("components").path("schemas").path("DeviceTypeResponse");
        assertThat(schema.path("properties").path("productKey").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
        assertThat(schema.path("properties").has("productSecret")).isFalse();
        assertThat(schema.path("properties").has("productSecretHash")).isFalse();
    }

    /** 通知初态及终态均允许没有下次计划，不能把空值生成为不可空字符串。 */
    @Test
    void alarmDeliveryContractKeepsNullableNextAttempt() throws Exception {
        JsonNode schema = PRETTY.readTree(fetchSpec()).path("components").path("schemas").path("AlarmNotificationDeliveryResponse");
        assertThat(schema.path("properties").path("nextAttemptAt").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
    }

    /** 未设置显示名或未分配角色时的实际空值必须进入双端生成合同。 */
    @Test
    void endUserContractKeepsNullableIdentityAndAssignmentFields() throws Exception {
        JsonNode schema = PRETTY.readTree(fetchSpec()).path("components").path("schemas").path("EndUserResponse");
        for (String field : List.of("displayName", "role", "roleStatus", "assignedAt")) {
            assertThat(schema.path("properties").path(field).path("type").valueStream().map(JsonNode::asString).toList())
                    .as(field).containsExactlyInAnyOrder("string", "null");
        }
    }

    /** S12-2a4b：App current只暴露授权交集、字符串Long和必填可空入口，所有分支声明no-store。 */
    @Test
    void webAppCurrentContractKeepsClosedProjectionAndAppBearer() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode path = spec.path("paths").path("/api/v1/app/applications/{appKey}/current");
        assertThat(path.propertyNames()).containsExactly("get");
        JsonNode operation = path.path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("getCurrentWebAppApplication");
        assertThat(operationIds(spec)
                .filter(value -> "getCurrentWebAppApplication".equals(value.asString()))).hasSize(1);
        assertThat(operation.path("parameters").valueStream())
                .allMatch(parameter -> "path".equals(parameter.path("in").asString()));
        assertThat(parameter(operation, "appKey", "path").path("schema").path("pattern").asString())
                .isEqualTo("^app_[0-9a-f]{32}$");
        assertThat(operation.has("requestBody")).isFalse();
        assertThat(operation.path("security").get(0).has("appAccessBearer")).isTrue();
        assertThat(spec.path("components").path("securitySchemes").path("appAccessBearer").path("scheme").asString())
                .isEqualTo("bearer");
        JsonNode response = referencedSchema(schemas, successSchema(operation));
        assertExactRequiredSchema(response, "identity", "application", "publicationRevision", "applicationVersionId",
                "applicationVersionNumber", "applicationFormatVersion", "hostCompatibility", "entryDashboardId", "dashboards");
        JsonNode properties = response.path("properties");
        assertExactRequiredSchema(referencedSchema(schemas, properties.path("identity")), "kind", "appUserId", "projectId");
        assertExactRequiredSchema(referencedSchema(schemas, properties.path("application")), "id", "appKey", "displayName");
        assertExactRequiredSchema(referencedSchema(schemas, properties.path("hostCompatibility")), "minInclusive", "maxExclusive");
        assertThat(properties.path("publicationRevision").path("type").asString()).isEqualTo("string");
        assertThat(properties.path("applicationVersionNumber").path("type").asString()).isEqualTo("string");
        assertThat(properties.path("entryDashboardId").path("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
        JsonNode reference = referencedSchema(schemas, properties.path("dashboards").path("items"));
        assertExactRequiredSchema(reference, "dashboardId", "dashboardVersionId", "dashboardVersionNumber", "title",
                "schemaVersion", "schemaDigestAlgorithm", "schemaDigest", "pages");
        assertThat(reference.path("properties").path("dashboardVersionNumber").path("type").asString()).isEqualTo("string");
        assertExactRequiredSchema(referencedSchema(schemas, reference.path("properties").path("pages").path("items")), "id", "title");
        for (String status : List.of("200", "400", "401", "404", "500")) {
            assertThat(operation.path("responses").path(status).path("headers").path("Cache-Control")
                    .path("schema").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("no-store");
        }
    }

    /** S12-2a5：精确Schema包保持11字段、唯一revision query、App Bearer及各状态no-store。 */
    @Test
    void webAppSchemaContractKeepsExactEntryAndClosedPackage() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode path = spec.path("paths").path(
                "/api/v1/app/applications/{appKey}/versions/{applicationVersionId}/dashboards/{dashboardVersionId}/schema");
        assertThat(path.propertyNames()).containsExactly("get");
        JsonNode operation = path.path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("getWebAppDashboardSchema");
        assertThat(operationIds(spec)
                .filter(value -> "getWebAppDashboardSchema".equals(value.asString()))).hasSize(1);
        assertThat(operation.has("requestBody")).isFalse();
        assertThat(operation.path("security").get(0).has("appAccessBearer")).isTrue();
        assertThat(operation.path("parameters")).hasSize(4);
        assertUuidPathParameter(operation, "applicationVersionId");
        assertUuidPathParameter(operation, "dashboardVersionId");
        JsonNode revision = parameter(operation, "expectedPublicationRevision", "query");
        assertThat(revision.path("required").asBoolean()).isTrue();
        assertThat(revision.path("schema").path("type").asString()).isEqualTo("string");
        assertThat(revision.path("schema").path("pattern").asString()).isEqualTo("^[1-9][0-9]{0,18}$");
        JsonNode response = referencedSchema(schemas, successSchema(operation));
        assertExactRequiredSchema(response, "applicationVersionId", "publicationRevision", "dashboardId",
                "dashboardVersionId", "dashboardVersionNumber", "schemaVersion", "schemaDigestAlgorithm", "schemaDigest",
                "requiredComponents", "requiredResources", "schema");
        JsonNode properties = response.path("properties");
        assertThat(properties.path("publicationRevision").path("type").asString()).isEqualTo("string");
        assertThat(properties.path("dashboardVersionNumber").path("type").asString()).isEqualTo("string");
        assertThat(properties.path("schema").path("type").asString()).isEqualTo("object");
        assertThat(properties.path("requiredComponents").path("maxItems").asInt()).isEqualTo(10);
        assertThat(properties.path("requiredResources").path("maxItems").asInt()).isEqualTo(50);
        assertExactRequiredSchema(referencedSchema(schemas, properties.path("requiredComponents").path("items")),
                "kind", "componentVersion");
        assertExactRequiredSchema(referencedSchema(schemas, properties.path("requiredResources").path("items")),
                "resourceId", "digest");
        for (String status : List.of("200", "400", "401", "404", "500")) {
            assertThat(operation.path("responses").path(status).path("headers").path("Cache-Control")
                    .path("schema").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("no-store");
        }
    }

    /** ADR0173/0174：公开Key为独立头身份，成功DTO无data包络，只读POST无写幂等。 */
    @Test
    void publicDeviceReadContractUsesKeyAndDirectBoundedDtos() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());
        JsonNode scheme=spec.path("components").path("securitySchemes").path("openApiKey");
        assertThat(scheme.path("type").asString()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asString()).isEqualTo("header");
        assertThat(scheme.path("name").asString()).isEqualTo("X-Api-Key");
        for(String path:List.of("/api/open/v1/devices","/api/open/v1/devices/{deviceId}","/api/open/v1/models/{versionId}","/api/open/v1/devices/current-values/query")){
            JsonNode op=spec.path("paths").path(path).path(path.endsWith("query")?"post":"get");
            assertThat(op.path("security")).as(path).hasSize(1);
            assertThat(op.path("security").get(0).propertyNames()).containsExactly("openApiKey");
            assertThat(op.path("parameters").valueStream().map(v->v.path("name").asString()).toList()).doesNotContain("projectId","Idempotency-Key");
            assertThat(op.path("responses").path("200").path("headers").has("Cache-Control")).isTrue();
        }
        JsonNode schemas=spec.path("components").path("schemas");
        assertThat(schemas.path("OpenDevicePage").path("properties").propertyNames()).containsExactlyInAnyOrder("items","nextCursor","hasMore");
        assertThat(schemas.path("OpenDeviceModel").path("properties").propertyNames()).contains("snapshot","digest");
        assertThat(schemas.path("OpenDeviceModel").path("properties").path("snapshot").path("$ref").asString()).isEmpty();
        assertThat(schemas.path("OpenCurrentRequest").path("additionalProperties").asBoolean(true)).isFalse();
    }

    /** ADR0173公开历史/告警绑定独立Key，历史计数不可降成JavaScript number。 */
    @Test
    void publicHistoryAlarmContractKeepsScopeAndExactCount() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());
        JsonNode history=spec.path("paths").path("/api/open/v1/devices/{deviceId}/history").path("get");
        JsonNode alarms=spec.path("paths").path("/api/open/v1/alarms/query").path("post");
        for(JsonNode op:List.of(history,alarms)){
            assertThat(op.path("security")).hasSize(1);
            assertThat(op.path("security").get(0).propertyNames()).containsExactly("openApiKey");
            assertThat(op.path("parameters").valueStream().map(v->v.path("name").asString()).toList()).doesNotContain("Idempotency-Key","projectId");
        }
        assertThat(history.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asString()).endsWith("/OpenPropertyHistoryResult");
        assertThat(history.path("responses").path("503").path("description").asString()).contains("50048");
        assertThat(history.path("responses").path("503").path("content").path("application/json").path("schema").path("$ref").asString()).endsWith("/ApiError");
        JsonNode schemas=spec.path("components").path("schemas");
        assertThat(schemas.path("OpenPropertyHistoryPoint").path("properties").path("sampleCount").path("type").asString()).isEqualTo("string");
        assertThat(schemas.path("OpenAlarmQueryRequest").path("additionalProperties").asBoolean(true)).isFalse();
    }

    /** ADR0173/0175：命令202与409墓碑明确，输出只含安全结果，不暴露内部幂等或账号。 */
    @Test
    void publicCommandContractKeepsAcceptanceAndBusinessRecovery() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());
        String base="/api/open/v1/devices/{deviceId}/commands";
        JsonNode post=spec.path("paths").path(base).path("post");
        assertThat(post.path("responses").path("202").path("content").path("application/json").path("schema").path("$ref").asString()).endsWith("/OpenCommandResult");
        assertThat(parameter(post,"Idempotency-Key","header").path("required").asBoolean()).isTrue();
        for(JsonNode op:List.of(post,spec.path("paths").path(base+"/{commandId}").path("get"),spec.path("paths").path(base+"/by-key").path("get"))){
            assertThat(op.path("security")).hasSize(1);assertThat(op.path("security").get(0).propertyNames()).containsExactly("openApiKey");
            assertThat(op.path("responses").path("409").path("content").path("application/json").path("schema").path("$ref").asString()).endsWith("/ApiError");
        }
        var schemas=spec.path("components").path("schemas");
        assertThat(schemas.path("OpenCommandResult").path("properties").propertyNames()).contains("commandId","status","completedAt").doesNotContain("idempotencyKey","requestedBy","connectionDeviceId");
        assertThat(schemas.path("OpenCommandSubmit").path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void realtimeTicketsHaveThreeExplicitIssuersAndClosedBoundedScope() throws Exception {
        var spec=PRETTY.readTree(fetchSpec());
        for(String path:List.of("/api/open/v1/realtime/tickets","/api/v1/projects/{projectId}/realtime-tickets","/api/v1/app/realtime-tickets")){
            var op=spec.path("paths").path(path).path("post");
            assertThat(op.path("responses").has("201")).as(path).isTrue();
            assertThat(parameter(op,"Idempotency-Key","header").path("required").asBoolean()).isTrue();
            assertThat(op.path("parameters").valueStream().map(v->v.path("name").asString()).toList()).doesNotContain("credential","token");
        }
        var schema=spec.path("components").path("schemas").path("PublicRealtimeTicketRequest");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").path("eventTypes").path("maxItems").asInt()).isEqualTo(1);
        assertThat(schema.path("properties").path("devices").path("maxItems").asInt()).isEqualTo(20);
    }

    /** S12-2b1：八条新读取必须区分App/Console Bearer、精确运行头、只读形态和所有错误的no-store。 */
    @Test
    void runtimeDataOperationsKeepIdentityReadOnlyAndErrorContracts() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        for (String[] endpoint : runtimeDataEndpoints()) {
            JsonNode operation = runtimeDataOperation(spec, endpoint);
            boolean app = "APP".equals(endpoint[3]);
            String scheme = app ? "appAccessBearer" : "consoleAccessBearer";
            assertThat(operation.path("security")).hasSize(1);
            assertThat(operation.path("security").get(0).propertyNames()).containsExactly(scheme);
            assertThat(spec.path("components").path("securitySchemes").path(scheme).path("type").asString()).isEqualTo("http");
            assertThat(spec.path("components").path("securitySchemes").path(scheme).path("scheme").asString()).isEqualTo("bearer");
            List<String> headers = operation.path("parameters").valueStream()
                    .filter(value -> "header".equals(value.path("in").asString()))
                    .map(value -> value.path("name").asString()).toList();
            assertThat(headers).doesNotContain("Idempotency-Key");
            if (app) {
                assertThat(headers).containsExactlyInAnyOrder("X-Application-Key", "X-Application-Version",
                        "X-Application-Revision", "X-Dashboard-Version");
                for (String header : headers) assertThat(parameter(operation, header, "header").path("required").asBoolean()).isTrue();
                assertThat(parameter(operation, "X-Application-Key", "header").path("schema").path("pattern").asString())
                        .isEqualTo("^app_[0-9a-f]{32}$");
                for (String header : List.of("X-Application-Version", "X-Dashboard-Version")) {
                    assertThat(parameter(operation, header, "header").path("schema").path("format").asString()).isEqualTo("uuid");
                }
                JsonNode revision = parameter(operation, "X-Application-Revision", "header").path("schema");
                assertThat(revision.path("type").asString()).isEqualTo("string");
                assertThat(revision.path("pattern").asString()).isEqualTo("^[1-9][0-9]{0,18}$");
            } else {
                assertThat(headers).doesNotContain("X-Application-Key", "X-Application-Version", "X-Application-Revision", "X-Dashboard-Version");
                assertUuidPathParameter(operation, "projectId");
            }
            if ("post".equals(endpoint[1])) assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
            else assertThat(operation.has("requestBody")).isFalse();
            assertThat(operation.toString()).as("最终响应预算必须公开说明；实际字节边界由HTTP测试独立验证")
                    .contains("4MiB");
            for (String status : List.of("200", "400", "401", "404", "429", "500")) {
                JsonNode response = operation.path("responses").path(status);
                assertThat(response.isMissingNode()).as(endpoint[0] + "缺少" + status).isFalse();
                assertThat(response.path("headers").path("Cache-Control").path("schema").path("enum")
                        .valueStream().map(JsonNode::asString).toList()).containsExactly("no-store");
                if (!"200".equals(status)) {
                    JsonNode content = response.path("content");
                    JsonNode error = content.has("application/json") ? content.path("application/json") : content.path("*/*");
                    assertThat(error.path("schema").path("$ref").asString()).isEqualTo("#/components/schemas/ApiError");
                }
            }
        }
    }

    /** 三类POST请求保持同形闭集、集合唯一性与数量边界，byte[]实现不能污染成binary合同。 */
    @Test
    void runtimeDataRequestsKeepBoundedClosedInputs() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        for (String[] endpoint : runtimeDataEndpoints()) {
            if (!"post".equals(endpoint[1])) continue;
            JsonNode request = referencedSchema(schemas, requestSchema(runtimeDataOperation(spec, endpoint)));
            boolean snapshot = endpoint[0].endsWith("/snapshots/query");
            boolean alarm = endpoint[0].endsWith("/alarms/query");
            if (snapshot) assertExactRequiredSchema(request, "models", "devices");
            else if (!alarm) assertExactRequiredSchema(request, "devices");
            else {
                assertThat(request.path("properties").propertyNames()).containsExactlyInAnyOrder(
                        "devices", "conditionStates", "ackStates", "severities", "cursor", "limit");
                assertThat(request.path("required").valueStream().map(JsonNode::asString).toList())
                        .containsExactlyInAnyOrder("devices", "conditionStates", "ackStates", "severities");
                assertRuntimeFilter(request, "conditionStates", "PENDING", "ACTIVE", "CLEARED");
                assertRuntimeFilter(request, "ackStates", "UNACKNOWLEDGED", "ACKNOWLEDGED");
                assertRuntimeFilter(request, "severities", "CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");
                assertThat(request.path("properties").path("cursor").path("maxLength").asInt()).isEqualTo(2048);
                assertRuntimePageLimit(request.path("properties").path("limit"));
            }
            JsonNode devices = request.path("properties").path("devices");
            assertBoundedUniqueArray(devices, 1, 20);
            JsonNode item = referencedSchema(schemas, devices.path("items"));
            if (alarm) assertExactRequiredSchema(item, "deviceId", "expectedModelVersionId");
            else {
                assertExactRequiredSchema(item, "deviceId", "expectedModelVersionId", "propertyKeys");
                assertBoundedUniqueArray(item.path("properties").path("propertyKeys"), snapshot ? 0 : 1, 50);
            }
            if (snapshot) {
                JsonNode models = request.path("properties").path("models");
                assertBoundedUniqueArray(models, 0, 20);
                assertExactRequiredSchema(referencedSchema(schemas, models.path("items")), "versionId", "digestAlgorithm", "digest", "profile");
            }
        }
    }

    /** 成功和失败三态、值五态必须按分支闭合，不能让生成客户端误认为隐藏字段总可读取。 */
    @Test
    void runtimeSnapshotAndCurrentResponsesPreserveDiscriminatedPrivacy() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        for (String[] endpoint : runtimeDataEndpoints()) {
            if (!endpoint[0].contains("/devices/") || !"post".equals(endpoint[1])) continue;
            JsonNode response = referencedSchema(schemas, successSchema(runtimeDataOperation(spec, endpoint)));
            boolean snapshot = endpoint[0].endsWith("/snapshots/query");
            if (snapshot) {
                assertExactRequiredSchema(response, "devices", "models");
                JsonNode item = referencedSchema(schemas, response.path("properties").path("devices").path("items"));
                JsonNode unavailable = runtimeBranch(schemas, item, "status", "NOT_AVAILABLE");
                assertExactRequiredSchema(unavailable, "deviceId", "status");
                JsonNode mismatch = runtimeBranch(schemas, item, "status", "MODEL_MISMATCH");
                assertExactRequiredSchema(mismatch, "deviceId", "status", "currentModelVersionId");
                assertRuntimeNullable(mismatch.path("properties").path("currentModelVersionId"), "string");
                JsonNode available = runtimeBranch(schemas, item, "status", "AVAILABLE");
                assertExactRequiredSchema(available, "deviceId", "status", "currentModelVersionId", "name", "deviceStatus", "lastOnlineAt");
                assertRuntimeNullable(available.path("properties").path("lastOnlineAt"), "string");
                JsonNode model = referencedSchema(schemas, response.path("properties").path("models").path("items"));
                assertExactRequiredSchema(model, "versionId", "digestAlgorithm", "digest", "profile", "properties");
                JsonNode property = referencedSchema(schemas, model.path("properties").path("properties").path("items"));
                assertExactRequiredSchema(property, "propertyKey", "dataType", "unit", "minimumValue", "maximumValue", "enumOptions", "onLabel", "offLabel");
                for (String field : List.of("unit", "onLabel", "offLabel")) assertRuntimeNullable(property.path("properties").path(field), "string");
                for (String field : List.of("minimumValue", "maximumValue")) assertRuntimeNullable(property.path("properties").path(field), "number");
                assertRuntimeNullable(property.path("properties").path("enumOptions"), "array");
            } else {
                assertExactRequiredSchema(response, "devices");
                JsonNode item = referencedSchema(schemas, response.path("properties").path("devices").path("items"));
                assertExactRequiredSchema(item, "deviceId", "status", "values");
                assertThat(item.path("properties").path("status").path("enum").valueStream().map(JsonNode::asString).toList())
                        .containsExactlyInAnyOrder("NOT_AVAILABLE", "MODEL_MISMATCH", "AVAILABLE");
                JsonNode value = referencedSchema(schemas, item.path("properties").path("values").path("items"));
                JsonNode availableValue = runtimeBranch(schemas, value, "state", "VALUE");
                assertExactRequiredSchema(availableValue,
                        "propertyKey", "state", "value", "occurredAt", "reportedModelVersionId");
                // VALUE传输真实JSON值，不能把Jackson节点的Java getter当成业务对象合同。
                JsonNode jsonValue = availableValue.path("properties").path("value");
                assertThat(jsonValue.has("$ref")).isFalse();
                assertThat(jsonValue.path("type").valueStream().map(JsonNode::asString).toList())
                        .containsExactlyInAnyOrder("number", "string", "boolean", "object", "array");
                for (String state : List.of("NO_VALUE", "SOURCE_VERSION_UNKNOWN", "SOURCE_MODEL_MISMATCH", "CONTRACT_MISMATCH")) {
                    assertExactRequiredSchema(runtimeBranch(schemas, value, "state", state), "propertyKey", "state");
                }
            }
        }
    }

    /** 目录分页、版本化历史与告警时间的nullable/数量边界不能退回旧通用接口的投影。 */
    @Test
    void runtimeCatalogHistoryAndAlarmContractsKeepPreciseFacts() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode catalog = spec.path("paths").path("/api/v1/app/devices/catalog").path("get");
        assertThat(catalog.path("parameters").valueStream().filter(value -> "query".equals(value.path("in").asString()))
                .map(value -> value.path("name").asString()).toList()).containsExactlyInAnyOrder("modelVersionId", "cursor", "limit");
        assertThat(parameter(catalog, "modelVersionId", "query").path("required").asBoolean()).isTrue();
        assertThat(parameter(catalog, "modelVersionId", "query").path("schema").path("format").asString()).isEqualTo("uuid");
        assertRuntimePageLimit(parameter(catalog, "limit", "query").path("schema"));
        JsonNode catalogPage = referencedSchema(schemas, successSchema(catalog));
        assertRuntimePage(catalogPage);
        assertExactRequiredSchema(referencedSchema(schemas, catalogPage.path("properties").path("items").path("items")),
                "deviceId", "name", "deviceStatus", "currentModelVersionId");
        JsonNode history = spec.path("paths").path("/api/v1/app/devices/{deviceId}/properties/{propertyKey}/history/versioned").path("get");
        for (String field : List.of("from", "to", "granularity", "aggregation", "expectedModelVersionId")) {
            assertThat(parameter(history, field, "query").path("required").asBoolean()).isTrue();
        }
        assertThat(history.path("parameters").valueStream().filter(value -> "query".equals(value.path("in").asString()))
                .map(value -> value.path("name").asString()).toList())
                .containsExactlyInAnyOrder("from", "to", "granularity", "aggregation", "expectedModelVersionId");
        JsonNode historyResponse = referencedSchema(schemas, successSchema(history));
        assertExactRequiredSchema(historyResponse, "requestedGranularity", "actualGranularity", "aggregation", "points");
        assertThat(historyResponse.path("properties").path("points").path("maxItems").asInt()).isEqualTo(2000);
        JsonNode point = referencedSchema(schemas, historyResponse.path("properties").path("points").path("items"));
        assertExactRequiredSchema(point, "ts", "value", "sampleCount", "thingModelVersionId", "modelVersion");
        assertRuntimeNullable(point.path("properties").path("thingModelVersionId"), "string");
        assertThat(point.path("properties").path("sampleCount").path("type").asString()).isEqualTo("integer");
        for (String path : List.of("/api/v1/app/alarms/query", "/api/v1/projects/{projectId}/alarms/query")) {
            JsonNode page = referencedSchema(schemas, successSchema(spec.path("paths").path(path).path("post")));
            assertRuntimePage(page);
            JsonNode item = referencedSchema(schemas, page.path("properties").path("items").path("items"));
            assertExactRequiredSchema(item, "id", "deviceId", "alarmType", "severity", "conditionState", "ackState",
                    "firstConditionAt", "activatedAt", "clearedAt", "acknowledgedAt", "lastReceivedAt", "version");
            for (String field : List.of("activatedAt", "clearedAt", "acknowledgedAt")) assertRuntimeNullable(item.path("properties").path(field), "string");
            for (String field : List.of("firstConditionAt", "lastReceivedAt")) {
                assertThat(item.path("properties").path(field).path("type").asString()).isEqualTo("string");
                assertThat(item.path("properties").path(field).path("format").asString()).isEqualTo("date-time");
            }
        }
    }

    /** 八条完整功能路由以固定操作身份核对，不把旧通用App接口算作新增读取。 */
    private static List<String[]> runtimeDataEndpoints() {
        return List.of(
                new String[]{"/api/v1/app/devices/snapshots/query", "post", "queryWebAppDeviceSnapshots", "APP"},
                new String[]{"/api/v1/app/devices/current-values/query", "post", "queryWebAppDeviceCurrentValues", "APP"},
                new String[]{"/api/v1/app/devices/catalog", "get", "listWebAppDeviceCatalog", "APP"},
                new String[]{"/api/v1/app/devices/{deviceId}/properties/{propertyKey}/history/versioned", "get", "getWebAppVersionedPropertyHistory", "APP"},
                new String[]{"/api/v1/app/alarms/query", "post", "queryWebAppAlarms", "APP"},
                new String[]{"/api/v1/projects/{projectId}/devices/snapshots/query", "post", "queryDeviceRuntimeSnapshots", "CONSOLE"},
                new String[]{"/api/v1/projects/{projectId}/devices/current-value-snapshots/query", "post", "queryDeviceCurrentValueSnapshots", "CONSOLE"},
                new String[]{"/api/v1/projects/{projectId}/alarms/query", "post", "queryConsoleAlarmsByDevices", "CONSOLE"});
    }

    /** 只枚举Path Item下HTTP operation，业务Schema同名字段不是接口标识。 */
    private static java.util.stream.Stream<JsonNode> operationIds(JsonNode spec) {
        return spec.path("paths").valueStream().flatMap(path ->
                java.util.stream.Stream.of("get", "put", "post", "delete", "options", "head", "patch", "trace")
                        .filter(path::has).map(method -> path.path(method).path("operationId")));
    }

    /** 精确方法及全局唯一operationId必须一起成立，防止更宽路由或复制标识悄悄过门禁。 */
    private static JsonNode runtimeDataOperation(JsonNode spec, String[] endpoint) {
        JsonNode path = spec.path("paths").path(endpoint[0]);
        assertThat(path.propertyNames()).containsExactly(endpoint[1]);
        JsonNode operation = path.path(endpoint[1]);
        assertThat(operation.path("operationId").asString()).isEqualTo(endpoint[2]);
        assertThat(operationIds(spec).filter(value -> endpoint[2].equals(value.asString()))).hasSize(1);
        return operation;
    }

    /** 数组的完整性与数量一起公开，required数组由对象闭集断言另行核对且不允许重复必填名。 */
    private static void assertBoundedUniqueArray(JsonNode schema, int minimum, int maximum) {
        assertThat(schema.path("type").asString()).isEqualTo("array");
        assertThat(schema.path("minItems").asInt()).isEqualTo(minimum);
        assertThat(schema.path("maxItems").asInt()).isEqualTo(maximum);
        assertThat(schema.path("uniqueItems").asBoolean()).isTrue();
    }

    /** 非空去重枚举过滤不能退化成任意字符串列表。 */
    private static void assertRuntimeFilter(JsonNode request, String field, String... values) {
        JsonNode array = request.path("properties").path(field);
        assertBoundedUniqueArray(array, 1, values.length);
        assertThat(array.path("items").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder(values);
    }

    /** 目录和告警的默认页大小相同，最大50不能继承管理历史默认200。 */
    private static void assertRuntimePageLimit(JsonNode schema) {
        assertThat(schema.path("default").asInt()).isEqualTo(20);
        assertThat(schema.path("minimum").asInt()).isEqualTo(1);
        assertThat(schema.path("maximum").asInt()).isEqualTo(50);
    }

    /** 页面字段恒存在，末页nullable游标不是可省略字段。 */
    private static void assertRuntimePage(JsonNode page) {
        assertExactRequiredSchema(page, "items", "nextCursor", "hasMore");
        assertThat(page.path("properties").path("items").path("maxItems").asInt()).isEqualTo(50);
        assertRuntimeNullable(page.path("properties").path("nextCursor"), "string");
    }

    /** 只允许冻结基础类型和null，不能以任意object或字段省略代替明确null语义。 */
    private static void assertRuntimeNullable(JsonNode field, String type) {
        assertThat(field.path("type").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder(type, "null");
    }

    /** 按判别字段找到唯一分支；只有VALUE分支能拥有事实字段，失败分支不能借可选字段扩权。 */
    private static JsonNode runtimeBranch(JsonNode schemas, JsonNode union, String discriminator, String value) {
        assertThat(union.path("oneOf").size()).as("状态投影必须表达oneOf分支").isGreaterThanOrEqualTo(2);
        List<JsonNode> matches = union.path("oneOf").valueStream().map(schema -> referencedSchema(schemas, schema))
                .filter(schema -> schema.path("properties").path(discriminator).path("enum").valueStream()
                        .anyMatch(item -> value.equals(item.asString()))).toList();
        assertThat(matches).as("状态只能命中一个闭合分支：" + value).hasSize(1);
        return matches.getFirst();
    }

    /** 解开局部Schema引用，用真实生成对象验证嵌套闭集，避免绑定实现中的内部类型名。 */
    private static JsonNode referencedSchema(JsonNode schemas, JsonNode schema) {
        if (!schema.has("$ref")) return schema;
        String reference = schema.path("$ref").asString();
        assertThat(reference).startsWith("#/components/schemas/");
        return schemas.path(reference.substring(reference.lastIndexOf('/') + 1));
    }

    /** 读取一个操作的200 JSON响应Schema，避免三处重复易错路径。 */
    private static JsonNode successSchema(JsonNode operation) {
        JsonNode content = operation.path("responses").path("200").path("content");
        // springdoc会把未显式声明produces的ResponseEntity记作通配媒体类型；两种写法的响应模型相同。
        JsonNode mediaType = content.has("application/json")
                ? content.path("application/json")
                : content.path("*/*");
        return mediaType.path("schema");
    }

    /** 从操作参数中取得指定名称和位置的唯一项。 */
    private static JsonNode parameter(JsonNode operation, String name, String location) {
        return java.util.stream.StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(value -> name.equals(value.path("name").asString()))
                .filter(value -> location.equals(value.path("in").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少" + location + "参数：" + name));
    }

    /** 断言路径身份必填且使用UUID字符串，避免生成客户端放宽定位符。 */
    private static void assertUuidPathParameter(JsonNode operation, String name) {
        JsonNode value = parameter(operation, name, "path");
        assertThat(value.path("required").asBoolean()).isTrue();
        assertThat(value.path("schema").path("type").asString()).isEqualTo("string");
        assertThat(value.path("schema").path("format").asString()).isEqualTo("uuid");
    }

    /** 读取写操作显式application/json请求Schema，缺失时返回missing node令断言定位合同漂移。 */
    private static JsonNode requestSchema(JsonNode operation) {
        return operation.path("requestBody").path("content")
                .path("application/json").path("schema");
    }

    /**
     * 断言菜单自引用类型在契约里表达为{@code $ref}数组。
     *
     * <p>springdoc 推导不出 record 的自引用组件类型：视注解写法不同，
     * {@code children} 可能整个消失、可能退化成一个只有 description 的空 schema。
     * 两种都不会让别的测试变红 —— 接口照常返回子菜单，只是<b>契约与实现不符</b>，
     * 而前端类型是从契约生成的，于是菜单树里唯一的递归结构成了唯一没有类型约束
     * 的地方。
     *
     * <p>这条断言是那三种错误写法唯一的拦截点。
     *
     * @param spec 已通过基本元信息检查的OpenAPI文档。
     */
    private static void assertRecursiveMenuSchema(JsonNode spec) {
        var children = spec
                .get("components").get("schemas")
                .get("MenuNodeResponse").get("properties").get("children");

        assertThat(children)
                .as("children 属性从契约里消失了 —— 接口返回它，契约却说没有")
                .isNotNull();
        assertThat(children.get("type").asString()).isEqualTo("array");
        assertThat(children.get("items"))
                .as("数组元素类型缺失，前端会把 children 生成为 unknown")
                .isNotNull();
        assertThat(children.get("items").get("$ref").asString())
                .isEqualTo("#/components/schemas/MenuNodeResponse");
    }

    /**
     * 取当前契约并格式化。
     *
     * <p>必须格式化后再比对：springdoc 输出的是压缩 JSON，一行几十 KB，
     * 任何一点改动在 diff 里都是「整个文件都变了」，评审时完全看不出改了什么。
     *
     * <h2>为什么要先确认这是一份契约</h2>
     * springdoc 生成契约时可能自己抛异常（例如某个 schema 注解写成了自引用递归），
     * 这时 {@code /v3/api-docs} 返回的是本站统一的 {@code ApiError} JSON —— 依然是
     * 合法 JSON，状态码 500。<b>写入模式会把这份错误响应原样覆盖到
     * {@code docs/openapi.json} 上</b>，而测试全绿。
     *
     * <p>这不是假设：实测中它真的发生过一次，直到前端生成类型时才发现契约文件
     * 变成了 {@code {"code":90000,...}}。所以这里对状态码与顶层字段各拦一道。
     */
    private String fetchSpec() throws Exception {
        var response = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("springdoc 生成契约失败，返回的是错误响应而不是契约：%s",
                        response.getContentAsString())
                .isEqualTo(200);

        var parsed = PRETTY.readTree(response.getContentAsString());
        assertThat(parsed.get("openapi"))
                .as("响应不是一份 OpenAPI 文档，绝不能写进契约文件")
                .isNotNull();

        return PRETTY.writerWithDefaultPrettyPrinter().writeValueAsString(parsed) + "\n";
    }

    /** 匿名版本读取只能使用单独Header能力；String版本号、闭集投影和所有响应禁止缓存。 */
    @Test
    void anonymousShareVersionReadsKeepCapabilityAndBoundedContracts() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        JsonNode scheme = spec.path("components").path("securitySchemes").path("shareCapability");
        assertThat(scheme.path("type").asString()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asString()).isEqualTo("header");
        assertThat(scheme.path("name").asString()).isEqualTo("X-Share-Token");
        for (String endpoint : List.of("context", "schema")) {
            JsonNode operation = spec.path("paths").path("/api/v1/shares/{shareId}/" + endpoint).path("get");
            assertThat(operation.path("security")).hasSize(1);
            assertThat(operation.path("security").get(0).propertyNames()).containsExactly("shareCapability");
            assertThat(operation.has("requestBody")).isFalse();
            assertThat(operation.path("parameters").valueStream().map(value -> value.path("in").asString()).toList())
                    .containsOnly("path");
            JsonNode response = referencedSchema(schemas, successSchema(operation));
            assertThat(response.path("properties").path("dashboardVersionNumber").path("type").asString()).isEqualTo("string");
            if ("context".equals(endpoint)) {
                assertThat(operation.path("operationId").asString()).isEqualTo("getDashboardShareContext");
                assertExactRequiredSchema(response, "shareId", "dashboardId", "dashboardVersionId", "dashboardVersionNumber",
                        "expiresAt", "historyAnchorAt", "hostCompatibility", "variableScopes");
                assertExactRequiredSchema(referencedSchema(schemas, response.path("properties").path("variableScopes").path("items")),
                        "variableKey", "deviceIds");
            } else {
                assertThat(operation.path("operationId").asString()).isEqualTo("getDashboardShareSchema");
                assertExactRequiredSchema(response, "dashboardId", "dashboardVersionId", "dashboardVersionNumber", "schemaVersion",
                        "schemaDigestAlgorithm", "schemaDigest", "requiredComponents", "requiredResources", "schema");
            }
            for (String status : List.of("200", "400", "403", "404", "429", "503")) {
                assertThat(operation.path("responses").path(status).path("headers").path("Cache-Control")
                        .path("schema").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("no-store");
            }
        }
    }

    /** 五路匿名数据以能力头独立确权，history只接受新鲜锚点而不开放任意from/to。 */
    @Test
    void anonymousShareDataOperationsKeepBoundedIdentityAndWindowContracts() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode schemas = spec.path("components").path("schemas");
        String[][] endpoints = {
                {"/devices/snapshots/query", "post", "queryDashboardShareDeviceSnapshots"},
                {"/devices/current-values/query", "post", "queryDashboardShareCurrentValues"},
                {"/devices/catalog", "get", "getDashboardShareDeviceCatalog"},
                {"/devices/{deviceId}/properties/{propertyKey}/history", "get", "getDashboardSharePropertyHistory"},
                {"/alarms/query", "post", "queryDashboardShareAlarms"}
        };
        for (String[] endpoint : endpoints) {
            JsonNode operation = spec.path("paths").path("/api/v1/shares/{shareId}" + endpoint[0]).path(endpoint[1]);
            assertThat(operation.path("operationId").asString()).isEqualTo(endpoint[2]);
            assertThat(operation.path("security")).hasSize(1);
            assertThat(operation.path("security").get(0).propertyNames()).containsExactly("shareCapability");
            assertThat(operation.path("parameters").valueStream().filter(value -> "header".equals(value.path("in").asString())).toList())
                    .isEmpty();
            if ("post".equals(endpoint[1])) {
                assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
                assertThat(operation.path("requestBody").path("content").has("application/json")).isTrue();
                assertThat(operation.path("parameters").valueStream().filter(value -> "query".equals(value.path("in").asString())).toList())
                        .isEmpty();
            } else {
                assertThat(operation.has("requestBody")).isFalse();
                List<String> names = operation.path("parameters").valueStream()
                        .filter(value -> "query".equals(value.path("in").asString())).map(value -> value.path("name").asString()).toList();
                if (endpoint[0].endsWith("history")) {
                    assertThat(names).containsExactlyInAnyOrder("expectedModelVersionId", "windowPreset", "anchorAt", "granularity", "aggregation");
                    JsonNode response = referencedSchema(schemas, successSchema(operation));
                    assertExactRequiredSchema(response, "from", "to", "requestedGranularity", "actualGranularity", "aggregation", "points");
                    assertThat(response.path("properties").path("points").path("maxItems").asInt()).isEqualTo(2000);
                } else {
                    assertThat(names).containsExactlyInAnyOrder("variableKey", "cursor", "limit");
                    assertExactRequiredSchema(referencedSchema(schemas, successSchema(operation)), "items", "nextCursor", "hasMore");
                }
            }
            for (String status : List.of("200", "400", "403", "404", "429", "503")) {
                assertThat(operation.path("responses").path(status).path("headers").path("Cache-Control")
                        .path("schema").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("no-store");
            }
        }
    }

    /** 原始属性历史的成功分页不能被显式套餐错误声明覆盖。 */
    @Test void rawPropertyHistoryDeclaresTypedSuccessAlongsideQuotaFailure() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode operation = spec.path("paths")
                .path("/api/v1/projects/{projectId}/devices/{deviceId}/telemetry/property").path("get");
        JsonNode schemas = spec.path("components").path("schemas");
        assertThat(operation.path("responses").propertyNames()).contains("200", "503");
        JsonNode page = referencedSchema(schemas, successSchema(operation));
        assertThat(page.path("properties").propertyNames()).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        JsonNode point = referencedSchema(schemas, page.path("properties").path("items").path("items"));
        assertThat(point.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "deviceId", "propertyKey", "ts", "value", "dataType", "thingModelVersionId", "modelVersion", "quality");
        assertThat(point.path("properties").path("value").path("type").asString()).isNotEqualTo("string");
        assertThat(parameter(operation, "from", "query").path("schema").path("format").asString()).isEqualTo("date-time");
        assertThat(parameter(operation, "to", "query").path("schema").path("format").asString()).isEqualTo("date-time");
        assertThat(parameter(operation, "limit", "query").path("schema").path("maximum").asInt()).isEqualTo(500);
    }

    /** BE-001-C：事件历史精确读取、原事实投影及所有状态缓存控制随真实生成合同冻结。 */
    @Test void consoleDeviceEventsKeepClosedQueriesExactHistoryProjectionAndNoStore() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode paths = spec.path("paths"), schemas = spec.path("components").path("schemas");
        String collection = "/api/v1/projects/{projectId}/devices/{deviceId}/events";
        String detailPath = collection + "/{messageId}";
        JsonNode list = runtimeDataOperation(spec, new String[]{collection, "get", "listConsoleDeviceEvents"});
        JsonNode detail = runtimeDataOperation(spec, new String[]{detailPath, "get", "getConsoleDeviceEvent"});
        assertThat(paths.path(collection).propertyNames()).containsExactly("get");
        assertThat(paths.path(detailPath).propertyNames()).containsExactly("get");
        for (JsonNode operation : List.of(list, detail)) {
            assertThat(operation.path("security")).hasSize(1);
            assertThat(operation.path("security").get(0).propertyNames()).containsExactly("consoleAccessBearer");
            assertUuidPathParameter(operation, "projectId");
            assertUuidPathParameter(operation, "deviceId");
            assertThat(operation.has("requestBody")).isFalse();
            assertThat(operation.path("parameters").valueStream().filter(value -> "header".equals(value.path("in").asString()))
                    .map(value -> value.path("name").asString()).toList()).doesNotContain("Idempotency-Key", "X-Application-Key");
            assertThat(operation.path("responses").propertyNames()).contains("200", "400", "401", "403", "404", "429", "500", "503");
            operation.path("responses").propertyNames().forEach(status -> {
                JsonNode response = operation.path("responses").path(status);
                assertThat(response.path("headers").path("Cache-Control").path("schema").path("enum")
                        .valueStream().map(JsonNode::asString).toList()).as("事件历史%s应禁止缓存", status).containsExactly("no-store");
                if (!"200".equals(status)) assertThat(response.path("content").path("application/json").path("schema").path("$ref").asString())
                        .isEqualTo("#/components/schemas/ApiError");
            });
        }
        assertUuidPathParameter(detail, "messageId");
        assertThat(list.path("parameters").valueStream().filter(value -> "query".equals(value.path("in").asString()))
                .map(value -> value.path("name").asString()).toList())
                .containsExactlyInAnyOrder("eventKey", "level", "thingModelVersionId", "from", "to", "cursor", "limit");
        assertThat(detail.path("parameters").valueStream().filter(value -> "query".equals(value.path("in").asString())).toList()).isEmpty();
        for (String name : List.of("eventKey", "level", "thingModelVersionId", "from", "to", "cursor", "limit"))
            assertThat(parameter(list, name, "query").path("required").asBoolean()).isFalse();
        JsonNode limit = parameter(list, "limit", "query").path("schema");
        assertThat(limit.path("type").asString()).isEqualTo("integer");
        assertThat(limit.path("default").asInt()).isEqualTo(20);
        assertThat(limit.path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("maximum").asInt()).isEqualTo(100);
        JsonNode page = referencedSchema(schemas, successSchema(list));
        assertExactRequiredSchema(page, "items", "nextCursor", "windowFrom", "windowTo", "retentionDays");
        assertRuntimeNullable(page.path("properties").path("nextCursor"), "string");
        assertThat(page.path("properties").path("items").path("maxItems").asInt()).isEqualTo(100);
        JsonNode retention = page.path("properties").path("retentionDays");
        assertThat(retention.path("type").asString()).isEqualTo("integer");
        assertThat(retention.path("enum").valueStream().toList()).allMatch(JsonNode::isIntegralNumber);
        assertThat(retention.path("enum").valueStream().map(JsonNode::asInt).toList()).containsExactly(90);
        assertThat(retention.path("enum").valueStream().map(JsonNode::asString).toList()).containsExactly("90");
        assertThat(retention.path("minimum").asInt()).isEqualTo(90);
        assertThat(retention.path("maximum").asInt()).isEqualTo(90);
        for (String field : List.of("windowFrom", "windowTo")) {
            assertThat(page.path("properties").path(field).path("type").asString()).isEqualTo("string");
            assertThat(page.path("properties").path(field).path("format").asString()).isEqualTo("date-time");
        }
        JsonNode item = referencedSchema(schemas, page.path("properties").path("items").path("items"));
        assertExactRequiredSchema(item, "messageId", "deviceId", "deviceTypeId", "eventKey", "level", "thingModelVersionId",
                "modelVersion", "eligibility", "occurredAt", "receivedAt", "acceptedAt", "params", "paramsRedacted");
        assertThat(referencedSchema(schemas, successSchema(detail))).isEqualTo(item);
        for (String field : List.of("messageId", "deviceId", "deviceTypeId", "thingModelVersionId"))
            assertThat(item.path("properties").path(field).path("format").asString()).isEqualTo("uuid");
        for (String field : List.of("occurredAt", "receivedAt", "acceptedAt"))
            assertThat(item.path("properties").path(field).path("format").asString()).isEqualTo("date-time");
        assertThat(item.path("properties").path("level").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("INFO", "WARNING", "ERROR");
        assertThat(item.path("properties").path("eligibility").path("enum").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder("CURRENT", "HISTORY_ONLY");
        assertThat(item.path("properties").path("params").path("type").asString()).isEqualTo("object");
        assertThat(item.path("properties").path("paramsRedacted").path("type").asString()).isEqualTo("boolean");
    }

    /** 历史为有权限的低敏摘要分页，不将单条命令载荷复制进列表。 */
    @Test void consoleCommandHistoryHasExactProjectionAndAuthenticatedCursorPage() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode operation = spec.path("paths").path("/api/v1/projects/{projectId}/devices/{deviceId}/commands").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("listConsoleDeviceCommandHistory");
        assertThat(operation.path("security").get(0).has("consoleAccessBearer")).isTrue();
        assertThat(operation.path("parameters").valueStream().filter(v -> "query".equals(v.path("in").asString()))
                .map(v -> v.path("name").asString()).toList()).containsExactlyInAnyOrder("cursor", "limit");
        JsonNode summary = spec.path("components").path("schemas").path("DeviceCommandHistoryResponse");
        assertThat(summary.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id", "deviceId", "operationType", "commandKey", "status", "attemptCount", "maxAttempts", "acceptedAt", "completedAt");
        assertThat(summary.path("required").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder(
                "id", "deviceId", "operationType", "status", "attemptCount", "maxAttempts", "acceptedAt");
    }

    /** 设备终端用户只展示当前关系白名单，不暴露登录标识。 */
    @Test void consoleDeviceEndUsersHasMinimalAuthenticatedCursorContract() throws Exception {
        JsonNode spec = PRETTY.readTree(fetchSpec());
        JsonNode operation = spec.path("paths").path("/api/v1/projects/{projectId}/devices/{deviceId}/end-users").path("get");
        assertThat(operation.path("operationId").asString()).isEqualTo("listConsoleDeviceEndUsers");
        assertThat(operation.path("security").get(0).has("consoleAccessBearer")).isTrue();
        assertThat(operation.path("parameters").valueStream().filter(v -> "query".equals(v.path("in").asString()))
                .map(v -> v.path("name").asString()).toList()).containsExactlyInAnyOrder("cursor", "limit");
        JsonNode summary = spec.path("components").path("schemas").path("DeviceEndUserResponse");
        assertThat(summary.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id", "appUserId", "displayName", "relationRole", "createdAt");
        assertThat(summary.path("required").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder(
                "id", "appUserId", "relationRole", "createdAt");
    }

    /** 当前任务定义与历史目标为不同的只读、低敏分页合同。 */
    @Test void consoleDeviceTasksSeparateDefinitionsAndTargetFacts() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());
        for (String suffix : java.util.List.of("task-jobs","task-executions")) {
            JsonNode operation=spec.path("paths").path("/api/v1/projects/{projectId}/devices/{deviceId}/"+suffix).path("get");
            assertThat(operation.path("security").get(0).has("consoleAccessBearer")).isTrue();
            assertThat(operation.path("parameters").valueStream().filter(v->"query".equals(v.path("in").asString()))
                    .map(v->v.path("name").asString()).toList()).containsExactlyInAnyOrder("cursor","limit");
        }
        JsonNode definitions=spec.path("components").path("schemas").path("DeviceTaskJobResponse");
        assertThat(definitions.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","name","status","version","targetType","targetGroupId","commandKey","createdAt");
        JsonNode facts=spec.path("components").path("schemas").path("DeviceTaskExecutionResponse");
        assertThat(facts.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","jobId","triggerType","executionStatus","targetStatus","commandKey","commandId","startedAt","acceptedAt","completedAt");
        assertThat(facts.path("required").valueStream().map(JsonNode::asString).toList()).containsExactlyInAnyOrder(
                "id","jobId","triggerType","executionStatus","targetStatus","commandKey","startedAt");
    }

    /** 自动化设备目录不输出配置/载荷，运行与管理使用独立响应。 */
    @Test void consoleDeviceAutomationsExposeOnlyPublishedAndExecutionSummaries() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());
        for(String suffix:java.util.List.of("automations","automation-executions")) {
            var operation=spec.path("paths").path("/api/v1/projects/{projectId}/devices/{deviceId}/"+suffix).path("get");
            assertThat(operation.path("security").get(0).has("consoleAccessBearer")).isTrue();
            assertThat(operation.path("parameters").valueStream().filter(v->"query".equals(v.path("in").asString()))
                    .map(v->v.path("name").asString()).toList()).containsExactlyInAnyOrder("cursor","limit");
        }
        var definition=spec.path("components").path("schemas").path("DeviceAutomationResponse");
        assertThat(definition.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","name","status","activeVersionId","versionNumber","triggerType","usesConditionInput","hasDeviceAction","createdAt");
        var execution=spec.path("components").path("schemas").path("DeviceAutomationExecutionResponse");
        assertThat(execution.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","automationId","automationVersionId","triggerType","status","reasonCode","attemptCount","deviceActionRecordCount","occurredAt","acceptedAt","createdAt","completedAt");
        assertThat(execution.path("required").valueStream().map(JsonNode::asString).toList()).contains(
                "id","automationVersionId","status","deviceActionRecordCount","createdAt");
    }

    /** 场景设备目录不输出配置/载荷，运行与管理使用独立响应。 */
    @Test void consoleDeviceScenesExposeOnlyPublishedAndExecutionSummaries() throws Exception {
        JsonNode spec=PRETTY.readTree(fetchSpec());
        for(String suffix:java.util.List.of("scene-candidates","scene-executions")) {
            var operation=spec.path("paths").path("/api/v1/projects/{projectId}/devices/{deviceId}/"+suffix).path("get");
            assertThat(operation.path("security").get(0).has("consoleAccessBearer")).isTrue();
            assertThat(operation.path("parameters").valueStream().filter(v->"query".equals(v.path("in").asString()))
                    .map(v->v.path("name").asString()).toList()).containsExactlyInAnyOrder("cursor","limit");
        }
        var definition=spec.path("components").path("schemas").path("DeviceSceneResponse");
        assertThat(definition.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","name","status","activeVersionId","versionNumber","relationScope","usesConditionInput","hasDeviceAction","createdAt");
        var execution=spec.path("components").path("schemas").path("DeviceSceneExecutionResponse");
        assertThat(execution.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","sceneId","sceneVersionId","status","deviceActionRecordCount","occurredAt","createdAt","completedAt");
        assertThat(execution.path("required").valueStream().map(JsonNode::asString).toList()).contains(
                "id","sceneVersionId","status","deviceActionRecordCount","createdAt");
    }

    /** 消息规则候选、尝试日志、动作各自投影，不暴露脚本与输入。 */
    @Test void deviceMessageRuleContractsKeepThreePurposesAndLegacyBoundary() throws Exception {
        var spec=PRETTY.readTree(fetchSpec());
        for(String suffix:java.util.List.of("candidates","executions","actions")) {
            var op=spec.path("paths").path("/api/v1/projects/{projectId}/devices/{deviceId}/message-rule-"+suffix).path("get");
            assertThat(op.path("security").get(0).has("consoleAccessBearer")).isTrue();
            assertThat(op.path("parameters").valueStream().filter(v->"query".equals(v.path("in").asString()))
                    .map(v->v.path("name").asString()).toList()).containsExactlyInAnyOrder("cursor","limit");
        }
        var schemas=spec.path("components").path("schemas");
        assertThat(schemas.path("DeviceMessageRuleResponse").path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","name","status","activeVersionId","versionNumber","relationScope","hasDeviceAction","createdAt");
        assertThat(schemas.path("DeviceMessageRuleExecutionResponse").path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","ruleId","ruleVersionId","messageId","attempt","status","resultCode","durationMillis","inputBytes","outputBytes","createdAt");
        assertThat(schemas.path("DeviceMessageRuleActionResponse").path("properties").propertyNames()).containsExactlyInAnyOrder(
                "id","ruleId","ruleVersionId","messageId","operationType","status","commandId","failureCode","createdAt","completedAt");
    }

}
