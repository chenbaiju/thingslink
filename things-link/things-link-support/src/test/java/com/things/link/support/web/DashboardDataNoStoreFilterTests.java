package com.things.link.support.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 精确数据读取登记不放宽相邻写路径，认证失败仍不可缓存。 */
class DashboardDataNoStoreFilterTests {
    /** 六个POST及两个GET按真实方法分类，非根部署也须先于认证写入no-store。 */
    @ParameterizedTest
    @CsvSource({
            "POST,/api/v1/app/devices/snapshots/query,true",
            "POST,/api/v1/app/devices/current-values/query,true",
            "POST,/api/v1/app/alarms/query,true",
            "POST,/api/v1/projects/id/devices/snapshots/query,true",
            "POST,/api/v1/projects/id/devices/current-value-snapshots/query,true",
            "POST,/api/v1/projects/id/alarms/query,true",
            "GET,/api/v1/app/devices/catalog,true",
            "GET,/api/v1/projects/id/alarms/device-status,true",
            "GET,/api/v1/projects/id/devices/device/commands,true",
            "GET,/api/v1/projects/id/devices/device/end-users,true",
            "GET,/api/v1/projects/id/devices/device/task-jobs,true",
            "GET,/api/v1/projects/id/devices/device/automations,true",
            "GET,/api/v1/projects/id/devices/device/scene-candidates,true",
            "GET,/api/v1/projects/id/devices/device/message-rule-candidates,true",
            "GET,/api/v1/projects/id/devices/device/automation-executions,true",
            "GET,/api/v1/projects/id/devices/device/scene-executions,true",
            "GET,/api/v1/projects/id/devices/device/message-rule-executions,true",
            "GET,/api/v1/projects/id/devices/device/message-rule-actions,true",
            "POST,/api/v1/projects/id/devices/device/message-rule-actions,false",
            "GET,/api/v1/projects/id/devices/device/message-rule-actions/extra,false",
            "POST,/api/v1/projects/id/devices/device/automations,false",
            "POST,/api/v1/projects/id/devices/device/scene-candidates,false",
            "POST,/api/v1/projects/id/devices/device/message-rule-candidates,false",
            "GET,/api/v1/projects/id/devices/device/automations/extra,false",
            "GET,/api/v1/projects/id/devices/device/scene-candidates/extra,false",
            "GET,/api/v1/projects/id/devices/device/message-rule-candidates/extra,false",
            "GET,/api/v1/projects/id/devices/device/automation-executions/extra,false",
            "GET,/api/v1/projects/id/devices/device/scene-executions/extra,false",
            "GET,/api/v1/projects/id/devices/device/message-rule-executions/extra,false",
            "GET,/api/v1/projects/id/devices/device/task-executions,true",
            "POST,/api/v1/projects/id/devices/device/task-jobs,false",
            "GET,/api/v1/projects/id/devices/device/task-jobs/run,false",
            "GET,/api/v1/projects/id/devices/device/task-executions/extra,false",
            "POST,/api/v1/projects/id/devices/device/end-users,false",
            "GET,/api/v1/projects/id/devices/device/end-users/other,false",
            "GET,/api/v1/projects/id/devices/device/end-users/,false",
            "POST,/api/v1/projects/id/devices/device/commands,false",
            "GET,/api/v1/projects/id/devices/device/commands/command,false",
            "GET,/api/v1/projects/id/devices/device/commands/,false",
            "POST,/api/v1/projects/id/alarms/device-status,false",
            "GET,/api/v1/projects/id/alarms/device-status/extra,false",
            "GET,/api/v1/app/devices/id/properties/key/history/versioned,true",
            "PUT,/api/v1/app/devices/snapshots/query,false",
            "POST,/api/v1/app/devices/snapshots/query/extra,false",
            "POST,/api/v1/app/alarms/query/,false",
            "POST,/api/v1/projects/id/devices/current-values/query,false",
            "POST,/api/v1/projects/id/alarms/id/ack,false",
            "GET,/api/v1/app/devices/catalog/extra,false",
            "POST,/api/v1/shares/key/alarms/query,false"
    })
    void exactPathsAreClassifiedBeforeAuthentication(String method, String path, boolean expected) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/prefix" + path);
        request.setContextPath("/prefix");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DashboardDataNoStoreFilter().doFilter(request, response, (ignored, downstream) -> {
            assertThat(response.getHeader("Cache-Control")).isEqualTo(expected ? "no-store" : null);
            response.setStatus(401);
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(DashboardDataRequestPaths.isAppPostRead(method, path))
                .isEqualTo(expected && "POST".equals(method) && path.startsWith("/api/v1/app/"));
        assertThat(DashboardDataRequestPaths.isConsolePostRead(method, path))
                .isEqualTo(expected && "POST".equals(method) && path.startsWith("/api/v1/projects/"));
    }
}
