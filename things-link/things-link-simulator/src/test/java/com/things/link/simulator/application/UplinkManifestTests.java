package com.things.link.simulator.application;

import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 A4-0 事实一致性对账链（设计冻结 §6）的 manifest 行为：分类型隔离、截断重开、重投重复、
 * 批量子 messageId、信封数与逻辑 messageId 数的区分，以及落盘失败只标记无效、不冒充发布失败。
 */
class UplinkManifestTests {

    /** 归档目录；每个用例独立。 */
    @TempDir
    Path dir;

    /** 四种类型必须写到各自文件，不能混进同一份日志或同一个计数。 */
    @Test
    void separatesTypesIntoOwnFilesAndCounters() throws Exception {
        UplinkManifest manifest = new UplinkManifest();
        manifest.open(dir);
        UUID property = Uuid7.generate();
        UUID command = Uuid7.generate();
        UUID config = Uuid7.generate();
        UUID batch = Uuid7.generate();
        manifest.recordInitiated(UplinkManifest.Type.PROPERTY_REPORT);
        manifest.recordConfirmed(UplinkManifest.Type.PROPERTY_REPORT, property);
        manifest.recordConfirmed(UplinkManifest.Type.COMMAND_REPLY, command);
        manifest.recordConfirmed(UplinkManifest.Type.CONFIG_REPLY, config);
        manifest.recordConfirmed(UplinkManifest.Type.BATCH_REPORT, batch);
        manifest.close();

        assertThat(Files.readAllLines(dir.resolve("property_report.log"))).containsExactly(property.toString());
        assertThat(Files.readAllLines(dir.resolve("command_reply.log"))).containsExactly(command.toString());
        assertThat(Files.readAllLines(dir.resolve("config_reply.log"))).containsExactly(config.toString());
        assertThat(Files.readAllLines(dir.resolve("batch_report.log"))).containsExactly(batch.toString());
        assertThat(manifest.countersOf(UplinkManifest.Type.PROPERTY_REPORT).confirmed()).isEqualTo(1);
        assertThat(manifest.countersOf(UplinkManifest.Type.COMMAND_REPLY).confirmed()).isEqualTo(1);
        assertThat(manifest.countersOf(UplinkManifest.Type.CONFIG_REPLY).confirmed()).isEqualTo(1);
        assertThat(manifest.countersOf(UplinkManifest.Type.BATCH_REPORT).confirmed()).isEqualTo(1);
    }

    /** 每次 open 代表一轮全新运行：上一轮内容必须被截断，计数也归零。 */
    @Test
    void truncatesPreviousRoundOnReopen() throws Exception {
        UplinkManifest manifest = new UplinkManifest();
        manifest.open(dir);
        manifest.recordConfirmed(UplinkManifest.Type.PROPERTY_REPORT, Uuid7.generate());
        manifest.close();

        manifest.open(dir);
        manifest.close();

        assertThat(Files.readAllLines(dir.resolve("property_report.log"))).isEmpty();
        assertThat(manifest.countersOf(UplinkManifest.Type.PROPERTY_REPORT).confirmed()).isZero();
    }

    /** QoS 1 命令重投会重复同一 messageId：信封数记 2、逻辑 messageId 数记 2、原始日志保留两行。 */
    @Test
    void keepsDuplicateMessageIdsForCommandReplayInRawLog() throws Exception {
        UplinkManifest manifest = new UplinkManifest();
        manifest.open(dir);
        UUID replayed = Uuid7.generate();
        manifest.recordInitiated(UplinkManifest.Type.COMMAND_REPLY);
        manifest.recordConfirmed(UplinkManifest.Type.COMMAND_REPLY, replayed);
        manifest.recordInitiated(UplinkManifest.Type.COMMAND_REPLY);
        manifest.recordConfirmed(UplinkManifest.Type.COMMAND_REPLY, replayed);
        manifest.close();

        UplinkManifest.Counters counters = manifest.countersOf(UplinkManifest.Type.COMMAND_REPLY);
        assertThat(counters.initiated()).isEqualTo(2);
        assertThat(counters.confirmed()).isEqualTo(2);
        assertThat(counters.confirmedMessages()).isEqualTo(2);
        // 原始日志如实保留两次重投；离线唯一集合由操作者 sort -u 得到（这里不去重）。
        assertThat(Files.readAllLines(dir.resolve("command_reply.log")))
                .containsExactly(replayed.toString(), replayed.toString());
    }

    /** 批量上报一个 PUBACK 信封携带多个子 messageId：信封数 1、逻辑 messageId 数 N、文件 N 行。 */
    @Test
    void countsBatchEnvelopeSeparatelyFromSubMessageIds() throws Exception {
        UplinkManifest manifest = new UplinkManifest();
        manifest.open(dir);
        UUID first = Uuid7.generate();
        UUID second = Uuid7.generate();
        UUID third = Uuid7.generate();
        manifest.recordInitiated(UplinkManifest.Type.BATCH_REPORT);
        manifest.recordConfirmed(UplinkManifest.Type.BATCH_REPORT, first, second, third);
        manifest.close();

        UplinkManifest.Counters counters = manifest.countersOf(UplinkManifest.Type.BATCH_REPORT);
        assertThat(counters.confirmed()).isEqualTo(1);
        assertThat(counters.confirmedMessages()).isEqualTo(3);
        assertThat(Files.readAllLines(dir.resolve("batch_report.log")))
                .containsExactly(first.toString(), second.toString(), third.toString());
    }

    /** 发起数必须等于确认数加失败数；确认与失败都不凭空产生或消失。 */
    @Test
    void reconcilesInitiatedConfirmedAndFailed() {
        UplinkManifest manifest = new UplinkManifest();
        manifest.open(dir);
        UUID confirmed = Uuid7.generate();
        manifest.recordInitiated(UplinkManifest.Type.PROPERTY_REPORT);
        manifest.recordInitiated(UplinkManifest.Type.PROPERTY_REPORT);
        manifest.recordInitiated(UplinkManifest.Type.PROPERTY_REPORT);
        manifest.recordConfirmed(UplinkManifest.Type.PROPERTY_REPORT, confirmed);
        manifest.recordFailed(UplinkManifest.Type.PROPERTY_REPORT);
        manifest.recordFailed(UplinkManifest.Type.PROPERTY_REPORT);

        UplinkManifest.Counters counters = manifest.countersOf(UplinkManifest.Type.PROPERTY_REPORT);
        assertThat(counters.initiated()).isEqualTo(3);
        assertThat(counters.confirmed()).isEqualTo(1);
        assertThat(counters.confirmedMessages()).isEqualTo(1);
        assertThat(counters.failed()).isEqualTo(2);
        assertThat(counters.initiated()).isEqualTo(counters.confirmed() + counters.failed());
    }

    /** 打开失败（父路径是文件）必须只标记无效并保留原因，不抛异常，且内存计数仍可继续累加。 */
    @Test
    void marksUnhealthyOnOpenFailureInsteadOfThrowing() throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Path badDir = blocker.resolve("manifest");

        UplinkManifest manifest = new UplinkManifest();
        manifest.open(badDir);

        assertThat(manifest.healthy()).isFalse();
        assertThat(manifest.failureReason()).isNotNull().contains("打开");
        // PUBACK 是事实：即使归档打不开，确认计数仍须成立，不能把磁盘失败当成发布失败。
        manifest.recordConfirmed(UplinkManifest.Type.PROPERTY_REPORT, Uuid7.generate());
        assertThat(manifest.countersOf(UplinkManifest.Type.PROPERTY_REPORT).confirmed()).isEqualTo(1);
        assertThat(manifest.countersOf(UplinkManifest.Type.PROPERTY_REPORT).failed()).isZero();
    }

    /** 从未 open 时 recordConfirmed 只计数不落盘，且不抛异常，供无归档的冒烟场景。 */
    @Test
    void countsWithoutArchiveWhenNeverOpened() {
        UplinkManifest manifest = new UplinkManifest();
        manifest.recordInitiated(UplinkManifest.Type.PROPERTY_REPORT);
        manifest.recordConfirmed(UplinkManifest.Type.PROPERTY_REPORT, Uuid7.generate());

        assertThat(manifest.countersOf(UplinkManifest.Type.PROPERTY_REPORT).confirmed()).isEqualTo(1);
        assertThat(manifest.healthy()).isTrue();
    }

    /** 正常打开、写入、关闭后 manifest 保持健康且无失败原因。 */
    @Test
    void staysHealthyThroughNormalLifecycle() {
        UplinkManifest manifest = new UplinkManifest();
        manifest.open(dir);
        manifest.recordConfirmed(UplinkManifest.Type.PROPERTY_REPORT, Uuid7.generate());
        manifest.close();

        assertThat(manifest.healthy()).isTrue();
        assertThat(manifest.failureReason()).isNull();
    }

    /** 关闭后迟到确认仍保留 PUBACK 事实，但必须把最终证据标记无效，不能静默形成计数/文件漂移。 */
    @Test
    void invalidatesArchiveWhenConfirmationArrivesAfterClose() throws Exception {
        UplinkManifest manifest = new UplinkManifest();
        manifest.open(dir);
        manifest.close();

        manifest.recordConfirmed(UplinkManifest.Type.PROPERTY_REPORT, Uuid7.generate());
        assertThat(manifest.countersOf(UplinkManifest.Type.PROPERTY_REPORT).confirmed()).isEqualTo(1);
        assertThat(manifest.healthy()).isFalse();
        assertThat(manifest.failureReason()).contains("关闭后").contains("PROPERTY_REPORT");
        assertThat(Files.readAllLines(dir.resolve("property_report.log"))).isEmpty();
    }

    /** 生命周期收敛超时可主动否决本轮证据，并保留首个原因供资格报告定位。 */
    @Test
    void supportsExplicitInvalidationWithoutOverwritingFirstReason() {
        UplinkManifest manifest = new UplinkManifest();
        manifest.open(dir);

        manifest.invalidate("入站任务未收敛");
        manifest.invalidate("后续次要错误");

        assertThat(manifest.healthy()).isFalse();
        assertThat(manifest.failureReason()).isEqualTo("入站任务未收敛");
    }
}
