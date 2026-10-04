package com.things.link.ota.application;

import com.things.link.device.application.DeviceService;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.page.CursorPage;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备维度作业只读集合：成员即可读取，不要求活动部署权限。
 *
 * <p>与固件维度历史集合（{@code OtaUploadService.history}/{@code OtaPublicationService.history}）
 * 同一授权口径：{@code ota:read} 由 {@link ProjectService#requireRoleInProject(UUID)} 判定，
 * 空历史返回空页而不是 404。
 *
 * <p>父资源不是本域事实，因此不读设备表：调用设备域公开控制面详情端口
 * {@link DeviceService#detail(UUID, UUID)}，设备不存在、已删除或属于其他项目统一是
 * 设备域的 404/30020；OTA 侧不复制该错误码，避免两处登记分叉。这与 telemetry 的
 * 消息日志设备守卫是同一收敛做法（{@code MessageLogApiAuthorization.requireDeviceRead}）。
 */
@Service
public class OtaDeviceJobService {
    /** 当前项目成员与角色。 */ private final ProjectService projects;
    /** 设备域公开控制面详情端口，统一设备不存在错误码。 */ private final DeviceService devices;
    /** 作业运行白名单投影。 */ private final OtaCampaignRepository repository;

    /** 显式注入跨域公开端口，禁止在 OTA 中读取设备表。 */
    public OtaDeviceJobService(ProjectService projects, DeviceService devices, OtaCampaignRepository repository) {
        this.projects = projects; this.devices = devices; this.repository = repository;
    }

    /** 当前成员读取设备全部作业，最新在前；空历史与父设备不存在必须区分。
     * @param project 精确项目
     * @param device 精确设备身份
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 按身份倒序的作业摘要游标页
     */
    @Transactional(readOnly = true, timeout = 5)
    public CursorPage<OtaCampaignRepository.JobSummary> history(UUID project, UUID device, String cursor, int limit) {
        projects.requireRoleInProject(project);
        devices.detail(project, device);
        return repository.jobsByDevice(project, device, cursor, limit);
    }
}
