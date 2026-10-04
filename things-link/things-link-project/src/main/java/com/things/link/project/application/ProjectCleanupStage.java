package com.things.link.project.application;

/** ADR0076：先引用者后被引用者的固定清理顺序，缺失步骤不能视为完成。 */
public enum ProjectCleanupStage {
    /** 等待导出对象及元数据安全收束。 */ WAIT_EXPORT,
    /** 任务目标、执行与定义。 */ TASK,
    /** 消息规则、场景与交付。 */ RULE,
    /** 告警投递、事件与配置。 */ ALARM,
    /** 项目App关系与能力。 */ ENDUSER,
    /** 看板与WebApp应用发布事实。 */ DASHBOARD,
    /** OTA固件草稿及创建恢复身份。 */ OTA,
    /** 公开集成凭据；不可变操作独立保留。 */ INTEGRATION,
    /** 遥测、命令与摄入事实。 */ TELEMETRY,
    /** 设备及模型。 */ DEVICE,
    /** 项目登录会话。 */ IAM,
    /** 平台投递及幂等事实。 */ SUPPORT,
    /** 项目成员等末序事实。 */ PROJECT,
    /** 全部领域后原子封存墓碑。 */ FINALIZE;

    /** @return 下一阶段；终态由最终编排专门处理，不允许越过FINALIZE */
    public ProjectCleanupStage next() {
        if (this == FINALIZE) {
            throw new IllegalStateException("最终墓碑必须由完整编排收束");
        }
        return values()[ordinal() + 1];
    }
}
