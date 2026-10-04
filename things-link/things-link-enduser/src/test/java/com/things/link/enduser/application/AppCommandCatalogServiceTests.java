package com.things.link.enduser.application;

import com.things.link.device.application.AppCommandCatalogDataPlaneService;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** App授权必须发生在跨域目录读取之前。 */
class AppCommandCatalogServiceTests {
    /** 原有App授权端口。 */
    private final AppDeviceAccessService access = mock(AppDeviceAccessService.class);
    /** 独立device公开投影。 */
    private final AppCommandCatalogDataPlaneService data = mock(AppCommandCatalogDataPlaneService.class);
    /** 被测编排。 */
    private final AppCommandCatalogService service = new AppCommandCatalogService(access, data);
    /** 已验证项目。 */
    private final UUID project = UUID.randomUUID();
    /** 已验证App用户。 */
    private final UUID user = UUID.randomUUID();
    /** 请求设备。 */
    private final UUID device = UUID.randomUUID();

    /** READ_ONLY拒绝不能触碰设备域目录。 */
    @Test void authorizationFailurePreventsProjection() {
        doThrow(new BusinessException(EndUserErrorCode.END_USER_DEVICE_CONTROL_FORBIDDEN))
                .when(access).requireCommandCatalogAccess(project, user, device);
        assertThatThrownBy(() -> service.list(project, user, device)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(data);
    }
    /** 空目录合法但必须先通过完整授权。 */
    @Test void authorizedEmptyCatalogPreservesOrdering() {
        when(data.list(project, device)).thenReturn(Optional.of(List.of()));
        assertThat(service.list(project, user, device)).isEmpty();
        var order = inOrder(access, data);
        order.verify(access).requireCommandCatalogAccess(project, user, device);
        order.verify(data).list(project, device);
    }
    /** 授权后设备已消失也不能返回假空目录。 */
    @Test void missingDeviceRemainsInvisible() {
        when(data.list(project, device)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(project, user, device)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(60010));
    }
}
