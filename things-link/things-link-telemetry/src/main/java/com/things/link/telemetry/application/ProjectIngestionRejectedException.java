package com.things.link.telemetry.application;

/**
 * 标准遥测首次摄入没有当前项目写许可，必须回滚同事务的 inbox 仲裁。
 *
 * <p>ADR0065 保留 processed 消费者的有限重试与确认死信合同，因此不能继承会被消费者归类为
 * 不可重试的 BusinessException。数据库异常继续保留原类型，本异常只用于确定的许可 false。</p>
 */
public final class ProjectIngestionRejectedException extends RuntimeException {

    /** 只描述当前写许可拒绝，不暴露消息载荷、项目存在性或承诺永久撤销。 */
    public ProjectIngestionRejectedException() {
        super("当前项目不允许首次摄入遥测");
    }
}
