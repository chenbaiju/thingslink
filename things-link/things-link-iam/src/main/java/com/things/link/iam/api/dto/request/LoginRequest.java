package com.things.link.iam.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求体。
 *
 * <p>本类型是 HTTP 契约的一部分，<b>禁止被其他模块引用</b>（架构文档 10.4 规则 4）。
 *
 * @param email    邮箱
 * @param password 明文口令
 */
@Schema(description = "登录请求")
public record LoginRequest(

        @Schema(description = "邮箱", example = "owner@example.com")
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        // 只校验非空与长度上限，**不在这里校验口令复杂度**：
        // 登录校验的是「这个口令对不对」，不是「这个口令够不够强」。
        // 对已有账号做复杂度校验，会让规则升级后的老用户直接登不进来。
        // 复杂度规则属于注册与改密接口。
        //
        // 上限存在的理由是防御性的：bcrypt 对超长输入的计算成本随长度增长，
        // 不设限等于给了一个廉价的 CPU 耗尽入口
        @Schema(description = "口令")
        @NotBlank(message = "口令不能为空")
        @Size(max = 128, message = "口令长度不能超过 128")
        String password) {

    /**
     * 覆盖默认实现，避免口令随日志泄露。
     *
     * <p>参数校验失败时 Spring 可能把整个请求对象写进日志。record 默认的
     * toString 会打印全部字段，那样明文口令就进了日志文件。
     */
    @Override
    public String toString() {
        return "LoginRequest[email=%s, password=***]".formatted(email);
    }

}
