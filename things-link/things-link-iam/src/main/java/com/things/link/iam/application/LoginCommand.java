package com.things.link.iam.application;

/**
 * 登录入参。
 *
 * @param email    邮箱
 * @param password 明文口令。<b>不得出现在任何日志中</b> —— 因此本记录刻意不重写
 *                 {@code toString()}，record 的默认实现会打印全部字段，
 *                 任何直接打印本对象的代码都会泄露口令。见 {@link #toString()}
 */
public record LoginCommand(String email, String password) {

    /**
     * 覆盖 record 的默认实现，避免口令随日志泄露。
     *
     * <p>record 默认的 toString 会打印所有字段。只要有一处
     * {@code log.debug("cmd={}", command)} 就会把明文口令写进日志文件，
     * 而日志通常保留很久、备份到多处、还可能被送去集中式日志系统。
     */
    @Override
    public String toString() {
        return "LoginCommand[email=%s, password=***]".formatted(email);
    }

}
