package com.things.link.export.application;

import com.things.link.alarm.application.AlarmExportSource;
import com.things.link.device.application.DeviceExportSource;
import com.things.link.export.domain.ProjectExportClaim;
import com.things.link.project.application.ProjectExportSource;
import com.things.link.support.audit.AuditExportSource;
import com.things.link.telemetry.application.PropertyPointExportSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ADR0075 决策1和4：ZIP格式、逐字段白名单、摘要与本地清理必须保持确定。 */
class ProjectExportArchiveGeneratorTests {

    /** 每例独占临时根目录，失败后可以直接证明没有残留。 */
    @TempDir
    private Path temporaryRoot;

    /** Jackson测试配置加载Java时间模块，与Boot生产映射器保持相同语义。 */
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    /** 七个数据集、manifest v1、逐文件行数与摘要必须与实际ZIP字节一致。 */
    @Test
    void writesSevenWhitelistedDatasetsAndVerifiableManifest() throws Exception {
        Instant snapshotAt = Instant.parse("2026-09-04T12:34:56Z");
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID alarmId = UUID.randomUUID();
        ProjectExportClaim claim = new ProjectExportClaim(
                UUID.randomUUID(), tenantId, projectId, 7, accountId, 1, UUID.randomUUID());
        Sources sources = sources(claim, snapshotAt, deviceId, alarmId);
        ProjectExportArchiveGenerator generator = generator(sources);

        Path workingDirectory;
        try (GeneratedProjectExport generated = generator.generate(claim, () -> false)) {
            workingDirectory = generated.workingDirectory();
            assertThat(Files.exists(generated.archive())).isTrue();
            assertThat(Files.size(generated.archive())).isEqualTo(generated.archiveSize());
            assertThat(sha256(Files.readAllBytes(generated.archive()))).isEqualTo(generated.archiveSha256());

            try (ZipFile zip = new ZipFile(generated.archive().toFile(), StandardCharsets.UTF_8)) {
                assertThat(zip.stream().map(ZipEntry::getName).toList()).containsExactly(
                        "manifest.json", "project.json", "members.jsonl", "devices.jsonl",
                        "property-points.jsonl", "alarm-instances.jsonl", "alarm-events.jsonl",
                        "audit-logs.jsonl");
                assertExactFieldSets(zip);
                assertManifest(zip, claim, snapshotAt);
            }
        }
        assertThat(Files.exists(workingDirectory)).isFalse();
        assertThat(directoryEmpty()).isTrue();
    }

    /** 任一领域在流式生成中失败时，已产生的数据文件和独占工作目录必须立即清理。 */
    @Test
    void removesPartialFilesWhenAStreamingSourceFails() throws Exception {
        Instant snapshotAt = Instant.parse("2026-09-04T12:34:56Z");
        ProjectExportClaim claim = new ProjectExportClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), 1, UUID.randomUUID());
        Sources sources = sources(claim, snapshotAt, UUID.randomUUID(), UUID.randomUUID());
        doAnswer(invocation -> {
            DeviceExportSource.DeviceSink sink = invocation.getArgument(2);
            sink.accept(device(UUID.randomUUID(), snapshotAt));
            throw new IllegalStateException("stream interrupted");
        }).when(sources.devices()).streamDevices(any(), any(), any());

        assertThatThrownBy(() -> generator(sources).generate(claim, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stream interrupted");
        assertThat(directoryEmpty()).isTrue();
    }

    /** 构造完整的单行领域端口，使格式测试不依赖数据库夹具细节。 */
    private Sources sources(ProjectExportClaim claim, Instant at, UUID deviceId, UUID alarmId) {
        ProjectExportSource project = mock(ProjectExportSource.class);
        DeviceExportSource devices = mock(DeviceExportSource.class);
        PropertyPointExportSource points = mock(PropertyPointExportSource.class);
        AlarmExportSource alarms = mock(AlarmExportSource.class);
        AuditExportSource audits = mock(AuditExportSource.class);
        when(project.lockSnapshot(claim.tenantId(), claim.projectId(), claim.projectGeneration()))
                .thenReturn(new ProjectExportSource.ProjectExportProject(
                        claim.projectId(), "删除项目", "sh-1", "Asia/Shanghai", "DELETING",
                        at.minusSeconds(300), at.minusSeconds(100), at.minusSeconds(50), at));
        doAnswer(invocation -> {
            ProjectExportSource.MemberSink sink = invocation.getArgument(2);
            sink.accept(new ProjectExportSource.ProjectExportMember(
                    UUID.randomUUID(), claim.requesterAccountId(), "OWNER", "ACTIVE",
                    at.minusSeconds(200), at.minusSeconds(100)));
            return 1L;
        }).when(project).streamMembers(any(), any(), any());
        doAnswer(invocation -> {
            DeviceExportSource.DeviceSink sink = invocation.getArgument(2);
            sink.accept(device(deviceId, at));
            return 1L;
        }).when(devices).streamDevices(any(), any(), any());
        doAnswer(invocation -> {
            PropertyPointExportSource.PropertyPointSink sink = invocation.getArgument(2);
            sink.accept(new PropertyPointExportSource.PropertyPointExportRow(
                    deviceId, "temperature", at.minusSeconds(20), UUID.randomUUID(), "DOUBLE",
                    UUID.randomUUID(), "v1", 23.5, null, null,
                    json.readTree("{\"unit\":\"celsius\"}"), (short) 0));
            return 1L;
        }).when(points).streamPropertyPoints(any(), any(), any());
        doAnswer(invocation -> {
            AlarmExportSource.InstanceSink sink = invocation.getArgument(2);
            sink.accept(new AlarmExportSource.AlarmInstanceExportRow(
                    alarmId, UUID.randomUUID(), "DEVICE", deviceId, "temperature-high", "WARNING",
                    "ACTIVE", "UNACKNOWLEDGED", null, at.minusSeconds(20), null,
                    at.minusSeconds(19), null, null, null, at.minusSeconds(10), at.minusSeconds(20),
                    23.5, 1, at.minusSeconds(20), at.minusSeconds(10)));
            return 1L;
        }).when(alarms).streamInstances(any(), any(), any());
        doAnswer(invocation -> {
            AlarmExportSource.EventSink sink = invocation.getArgument(2);
            sink.accept(new AlarmExportSource.AlarmEventExportRow(
                    UUID.randomUUID(), alarmId, "ACTIVATED", UUID.randomUUID(), "trace-safe", 23.5,
                    at.minusSeconds(20), at.minusSeconds(19), null, "ACTIVE", "UNACKNOWLEDGED", null));
            return 1L;
        }).when(alarms).streamEvents(any(), any(), any());
        doAnswer(invocation -> {
            AuditExportSource.AuditSink sink = invocation.getArgument(2);
            sink.accept(new AuditExportSource.AuditExportRow(
                    UUID.randomUUID(), claim.requesterAccountId(), "project_export", claim.jobId(),
                    "project.export.requested", "trace-safe", json.readTree("{\"safe\":true}"), at));
            return 1L;
        }).when(audits).streamAuditLogs(any(), any(), any());
        return new Sources(project, devices, points, alarms, audits);
    }

    /** 创建设备白名单行。 */
    private static DeviceExportSource.DeviceExportRow device(UUID id, Instant at) {
        return new DeviceExportSource.DeviceExportRow(
                id, UUID.randomUUID(), null, "device-001", "温度计", "安全描述", "ONLINE",
                "机房A", at.minusSeconds(3), at.minusSeconds(200));
    }

    /** 创建使用测试临时根目录的真实生成器。 */
    private ProjectExportArchiveGenerator generator(Sources sources) {
        return new ProjectExportArchiveGenerator(
                sources.project(), sources.devices(), sources.points(), sources.alarms(),
                sources.audits(), json, temporaryRoot.toString());
    }

    /** 校验每个实际JSON对象只有ADR0075允许字段。 */
    private void assertExactFieldSets(ZipFile zip) throws Exception {
        assertThat(fields(json.readTree(read(zip, "project.json")))).containsExactlyInAnyOrder(
                "id", "name", "region", "timezone", "status", "createdAt", "updatedAt", "deletedAt");
        assertThat(fields(firstLine(zip, "members.jsonl"))).containsExactlyInAnyOrder(
                "id", "accountId", "role", "status", "createdAt", "updatedAt");
        assertThat(fields(firstLine(zip, "devices.jsonl"))).containsExactlyInAnyOrder(
                "id", "deviceTypeId", "gatewayId", "deviceKey", "name", "description", "status",
                "location", "lastOnlineAt", "createdAt");
        assertThat(fields(firstLine(zip, "property-points.jsonl"))).containsExactlyInAnyOrder(
                "deviceId", "propertyKey", "ts", "messageId", "dataType", "thingModelVersionId",
                "modelVersion", "valueDouble", "valueText", "valueBool", "valueJson", "quality");
        assertThat(fields(firstLine(zip, "alarm-instances.jsonl"))).containsExactlyInAnyOrder(
                "id", "ruleId", "originatorType", "originatorId", "alarmType", "severity",
                "conditionState", "ackState", "clearReason", "firstConditionAt", "recoveryConditionAt",
                "activatedAt", "clearedAt", "acknowledgedAt", "acknowledgedBy", "lastReceivedAt",
                "lastOccurredAt", "lastValue", "version", "createdAt", "updatedAt");
        assertThat(fields(firstLine(zip, "alarm-events.jsonl"))).containsExactlyInAnyOrder(
                "id", "instanceId", "eventType", "sourceMessageId", "traceId", "value", "occurredAt",
                "receivedAt", "actorId", "conditionState", "ackState", "clearReason");
        assertThat(fields(firstLine(zip, "audit-logs.jsonl"))).containsExactlyInAnyOrder(
                "id", "actorAccountId", "targetType", "targetId", "action", "traceId", "details", "createdAt");
    }

    /** manifest逐文件行数、字节数和摘要必须由ZIP中实际未压缩字节复算得到。 */
    private void assertManifest(ZipFile zip, ProjectExportClaim claim, Instant snapshotAt) throws Exception {
        JsonNode manifest = json.readTree(read(zip, "manifest.json"));
        assertThat(fields(manifest)).containsExactlyInAnyOrder(
                "formatVersion", "exportId", "projectId", "projectGeneration", "snapshotAt", "files",
                "totalUncompressedBytes");
        assertThat(manifest.get("formatVersion").asInt()).isEqualTo(1);
        assertThat(manifest.get("exportId").asString()).isEqualTo(claim.jobId().toString());
        assertThat(manifest.get("projectId").asString()).isEqualTo(claim.projectId().toString());
        assertThat(manifest.get("projectGeneration").asLong()).isEqualTo(7);
        assertThat(Instant.parse(manifest.get("snapshotAt").asString())).isEqualTo(snapshotAt);

        long total = 0;
        List<String> paths = new ArrayList<>();
        for (JsonNode file : manifest.get("files")) {
            String path = file.get("path").asString();
            byte[] content = read(zip, path);
            paths.add(path);
            total += content.length;
            assertThat(file.get("rowCount").asLong()).isEqualTo(1);
            assertThat(file.get("uncompressedBytes").asLong()).isEqualTo(content.length);
            assertThat(file.get("sha256").asString()).isEqualTo(sha256(content));
        }
        assertThat(paths).containsExactly(
                "project.json", "members.jsonl", "devices.jsonl", "property-points.jsonl",
                "alarm-instances.jsonl", "alarm-events.jsonl", "audit-logs.jsonl");
        assertThat(manifest.get("totalUncompressedBytes").asLong()).isEqualTo(total);
    }

    /** 读取JSONL第一条非空记录。 */
    private JsonNode firstLine(ZipFile zip, String name) throws Exception {
        String value = new String(read(zip, name), StandardCharsets.UTF_8);
        return json.readTree(value.lines().filter(line -> !line.isBlank()).findFirst().orElseThrow());
    }

    /** 读取ZIP条目完整未压缩字节。 */
    private static byte[] read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertThat(entry).isNotNull();
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    /** 提取JSON对象字段集合。 */
    private static Set<String> fields(JsonNode node) {
        Set<String> names = new java.util.LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** 计算小写SHA-256。 */
    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** 关闭目录流后判断生成器是否留下任何尝试文件。 */
    private boolean directoryEmpty() throws Exception {
        try (var entries = Files.list(temporaryRoot)) {
            return entries.findAny().isEmpty();
        }
    }

    /** 一组被测领域端口。 */
    private record Sources(ProjectExportSource project, DeviceExportSource devices,
                           PropertyPointExportSource points, AlarmExportSource alarms,
                           AuditExportSource audits) {
    }
}
