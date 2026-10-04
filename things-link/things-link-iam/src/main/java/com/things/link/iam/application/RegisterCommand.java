package com.things.link.iam.application;

/**
 * 注册入参。
 *
 * @param email       邮箱，即登录名。全局唯一（不区分大小写）
 * @param password    明文口令，只在本次调用中存在，落库前必然经过哈希
 * @param displayName 显示名；为空时由邮箱推导
 */
public record RegisterCommand(String email, String password, String displayName) {

    /**
     * 屏蔽明文口令。
     *
     * <p>record 的默认 {@code toString()} 会打印全部字段，一处
     * {@code log.debug("{}", command)} 就把明文口令写进了日志。{@code LoginCommand}
     * 与 {@code IssuedSession} 出于同样的理由重写了它 —— 凡是持有凭据的记录类型，
     * 这一步都不能省。
     */
    @Override
    public String toString() {
        return "RegisterCommand[email=" + email + ", password=***, displayName=" + displayName + "]";
    }

}
