package com.things.link.dashboard.application.publication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 应用发布候选的宿主格式、范围、Schema、组件、资源与安全失败边界测试。 */
@DisplayName("应用发布宿主资格")
class ApplicationPublicationQualificationServiceTests {

    /** 候选项目ID，用于确认错误信息不泄漏业务身份。 */
    private static final UUID PROJECT_ID = uuid(2);
    /** 候选看板ID，用于确认错误信息不泄漏引用身份。 */
    private static final UUID DASHBOARD_ID = uuid(11);
    /** 内置资源摘要。 */
    private static final String RESOURCE_DIGEST = "d".repeat(64);

    /** 完整受管宿主事实命中时返回不可公开伪造的临时资格值。 */
    @Test
    @DisplayName("宿主全部事实匹配时取得临时资格")
    void exactManagedHostFactsProduceQualifiedCandidate() throws NoSuchMethodException {
        DashboardHostQualificationPort host = mock(DashboardHostQualificationPort.class);
        ApplicationPublicationQualificationService service =
                new ApplicationPublicationQualificationService(host);
        ApplicationPublicationCandidate candidate = candidate();
        when(host.current()).thenReturn(Optional.of(hostDescriptor()));

        QualifiedApplicationPublicationCandidate qualified = service.qualify(candidate);

        assertThat(qualified.candidate()).isSameAs(candidate);
        assertThat(QualifiedApplicationPublicationCandidate.class.getConstructors()).isEmpty();
        assertThat(Modifier.isPublic(ApplicationPublicationQualificationService.class
                .getDeclaredMethod("qualify", ApplicationPublicationCandidate.class)
                .getModifiers())).isFalse();
        verify(host).current();
    }

    /** 宿主缺失或端口异常必须失败关闭，不能用静态Schema注册表回退成功。 */
    @Test
    @DisplayName("宿主缺失失败关闭且端口异常原样传播")
    void missingHostFailsClosedAndPortFailurePropagates() {
        DashboardHostQualificationPort missing = mock(DashboardHostQualificationPort.class);
        ApplicationPublicationQualificationService missingService =
                new ApplicationPublicationQualificationService(missing);

        assertHostFailure(() -> missingService.qualify(candidate()));

        DashboardHostQualificationPort failed = mock(DashboardHostQualificationPort.class);
        ApplicationPublicationQualificationService failedService =
                new ApplicationPublicationQualificationService(failed);
        RuntimeException unavailable = new RuntimeException("host authority unavailable");
        when(failed.current()).thenThrow(unavailable);
        assertThatThrownBy(() -> failedService.qualify(candidate())).isSameAs(unavailable);
    }

    /** 格式、应用格式与Schema集合任一错配均使用同一安全失败。 */
    @Test
    @DisplayName("宿主格式应用格式和Schema错配统一拒绝")
    void descriptorFormatApplicationFormatAndSchemaMismatchesFailClosed() {
        List<DashboardHostQualificationDescriptor> invalid = List.of(
                descriptor("tc.webapp-host/v2", "1.5.0",
                        Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                        Map.of(kind(), "1.0.0"), Map.of("empty_state", RESOURCE_DIGEST)),
                descriptor("tc.webapp-host/v1", "1.5.0",
                        Set.of("tc.application/v2"), Set.of("tc.dashboard/v1"),
                        Map.of(kind(), "1.0.0"), Map.of("empty_state", RESOURCE_DIGEST)),
                descriptor("tc.webapp-host/v1", "1.5.0",
                        Set.of("tc.application/v1"), Set.of("tc.dashboard/v2"),
                        Map.of(kind(), "1.0.0"), Map.of("empty_state", RESOURCE_DIGEST)));

        for (DashboardHostQualificationDescriptor descriptor : invalid) {
            assertHostFailure(() -> qualify(descriptor));
        }
    }

    /** SemVer使用数值半开区间，低于下界、等于上界、字典序陷阱和非法段均拒绝。 */
    @Test
    @DisplayName("宿主版本按数值半开区间判断")
    void hostVersionUsesNumericHalfOpenRange() {
        for (String version : List.of("0.9.9", "2.0.0", "10.0.0", "1.01.0", "1.70000.0")) {
            assertHostFailure(() -> qualify(descriptor(
                    "tc.webapp-host/v1", version,
                    Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                    Map.of(kind(), "1.0.0"), Map.of("empty_state", RESOURCE_DIGEST))));
        }
        assertThat(qualify(descriptor(
                "tc.webapp-host/v1", "1.10.0",
                Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(kind(), "1.0.0"), Map.of("empty_state", RESOURCE_DIGEST))).candidate())
                .isNotNull();
    }

    /** 范围内宿主仍须精确具备聚合后的组件版本和资源摘要。 */
    @Test
    @DisplayName("组件和资源缺失或错配均失败关闭")
    void componentAndResourceMismatchesFailClosed() {
        List<DashboardHostQualificationDescriptor> invalid = List.of(
                descriptor("tc.webapp-host/v1", "1.5.0",
                        Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                        Map.of(), Map.of("empty_state", RESOURCE_DIGEST)),
                descriptor("tc.webapp-host/v1", "1.5.0",
                        Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                        Map.of(kind(), "1.0.1"), Map.of("empty_state", RESOURCE_DIGEST)),
                descriptor("tc.webapp-host/v1", "1.5.0",
                        Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                        Map.of(kind(), "1.0.0"), Map.of()),
                descriptor("tc.webapp-host/v1", "1.5.0",
                        Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                        Map.of(kind(), "1.0.0"), Map.of("empty_state", "e".repeat(64))));

        for (DashboardHostQualificationDescriptor descriptor : invalid) {
            assertHostFailure(() -> qualify(descriptor));
        }
    }

    /** 所有宿主错配只返回稳定内部原因，不回显候选或宿主的实际身份。 */
    @Test
    @DisplayName("宿主资格失败不泄漏应用看板或项目身份")
    void hostFailureDoesNotLeakCandidateIdentities() {
        DashboardHostQualificationPort host = mock(DashboardHostQualificationPort.class);
        when(host.current()).thenReturn(Optional.empty());
        ApplicationPublicationQualificationService service =
                new ApplicationPublicationQualificationService(host);

        assertThatThrownBy(() -> service.qualify(candidate()))
                .isInstanceOfSatisfying(ApplicationPublicationQualificationException.class, failure -> {
                    assertThat(failure.reason())
                            .isEqualTo(ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE);
                    assertThat(failure.getMessage())
                            .doesNotContain(PROJECT_ID.toString(), DASHBOARD_ID.toString(), RESOURCE_DIGEST);
                });
    }

    /** 使用指定宿主描述符执行一次资格判断。 */
    private static QualifiedApplicationPublicationCandidate qualify(
            DashboardHostQualificationDescriptor descriptor) {
        DashboardHostQualificationPort host = mock(DashboardHostQualificationPort.class);
        when(host.current()).thenReturn(Optional.of(descriptor));
        return new ApplicationPublicationQualificationService(host).qualify(candidate());
    }

    /** 构造完整匹配候选的宿主描述符。 */
    private static DashboardHostQualificationDescriptor hostDescriptor() {
        return descriptor(
                "tc.webapp-host/v1", "1.5.0",
                Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(kind(), "1.0.0"), Map.of("empty_state", RESOURCE_DIGEST));
    }

    /** 构造可精确改变每类宿主事实的描述符。 */
    private static DashboardHostQualificationDescriptor descriptor(
            String formatVersion, String hostVersion,
            Set<String> applicationFormats, Set<String> schemas,
            Map<DashboardPublicationEligibilityRequirement.ComponentKind, String> components,
            Map<String, String> resources) {
        return new DashboardHostQualificationDescriptor(
                formatVersion, hostVersion, applicationFormats, schemas, components, resources);
    }

    /** 构造一份需要TEXT和一个内置资源的最小应用候选。 */
    private static ApplicationPublicationCandidate candidate() {
        return new ApplicationPublicationCandidate(
                uuid(3), uuid(1), PROJECT_ID, 7,
                JsonMapper.builder().build().createObjectNode().put("formatVersion", "tc.application/v1"),
                "{}", PostgreSqlApplicationSnapshotCanonicalForm.DIGEST_ALGORITHM, "a".repeat(64),
                "1.0.0", "2.0.0",
                List.of(new ApplicationVersionDashboardReference(0, DASHBOARD_ID, uuid(21))),
                List.of("tc.dashboard/v1"),
                List.of(new DashboardRequiredComponent("TEXT", "1.0.0")),
                List.of(new DashboardRequiredResource("empty_state", RESOURCE_DIGEST)));
    }

    /** @return TEXT宿主组件枚举 */
    private static DashboardPublicationEligibilityRequirement.ComponentKind kind() {
        return DashboardPublicationEligibilityRequirement.ComponentKind.TEXT;
    }

    /** 断言统一、安全的宿主失败原因。 */
    private static void assertHostFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ApplicationPublicationQualificationException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(ApplicationPublicationQualificationException.Reason.HOST_UNAVAILABLE));
    }

    /** 构造可读确定UUID。 */
    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }
}
