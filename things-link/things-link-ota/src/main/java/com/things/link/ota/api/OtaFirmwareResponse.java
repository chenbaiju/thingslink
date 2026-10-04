package com.things.link.ota.api;

import com.things.link.ota.domain.OtaFirmware;
import java.time.Instant;
import java.util.UUID;

/**
 * 固件公开白名单，不包含租户、账号、签名配置或对象地址。
 * @param id 固件ID
 * @param projectId 项目ID
 * @param deviceTypeId 设备类型ID
 * @param thingModelVersionId 物模型版本ID
 * @param productKey 产品标识
 * @param firmwareVersion 原样展示版本
 * @param schemaDigestAlgorithm 模型摘要算法
 * @param schemaDigest 模型摘要
 * @param schemaProfile 模型合同
 * @param status 草稿、发布过程、READY或软终态
 * @param revision 十进制修订字符串
 * @param createdAt 微秒精度创建时间
 * @param cancelledAt 取消时间，草稿为空
 */
public record OtaFirmwareResponse(UUID id, UUID projectId, UUID deviceTypeId, UUID thingModelVersionId,
                                  String productKey, String firmwareVersion, String schemaDigestAlgorithm,
                                  String schemaDigest, String schemaProfile, String status, String revision,
                                  Instant createdAt, Instant cancelledAt) {
    /** 显式投影公开字段，禁止序列化内部实体。 */
    public static OtaFirmwareResponse from(OtaFirmware f) {
        return new OtaFirmwareResponse(f.id(), f.projectId(), f.deviceTypeId(), f.thingModelVersionId(),
                f.productKey(), f.firmwareVersion(), f.schemaDigestAlgorithm(), f.schemaDigest(),
                f.schemaProfile(), f.status(), Long.toString(f.revision()), f.createdAt(), f.cancelledAt());
    }
}
