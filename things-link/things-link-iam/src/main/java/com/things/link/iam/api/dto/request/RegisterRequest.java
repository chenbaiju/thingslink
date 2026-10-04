package com.things.link.iam.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>按 ADR 0008，注册的语义是<b>创建租户 + 初始账号</b>，不是「加一个用户」。
 *
 * @param email       邮箱，即登录名。全局唯一（不区分大小写）
 * @param password    明文口令
 * @param displayName 显示名，可空；为空时由邮箱推导
 */
@Schema(description = "注册请求")
public record RegisterRequest(

        @Schema(description = "邮箱", example = "owner@example.com")
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "邮箱过长")
        String email,

        /*
         * 长度下限同时写在这里和 RegistrationService 里，看似重复，其实职责不同：
         * 这里是**参数校验**，能一次性返回逐项明细（10001）；服务层那道是**不变式**，
         * 保证任何调用路径（将来的管理员改密、重置口令）都绕不过去。
         *
         * 只留注解的话，以后新增一个不走这个 DTO 的入口就会漏掉；
         * 只留服务层的话，用户拿不到「哪个字段错了」的明细。
         */
        @Schema(description = "口令，10-128 个字符", minLength = 10, maxLength = 128)
        @NotBlank(message = "口令不能为空")
        @Size(min = 10, max = 128, message = "口令长度需在 10 到 128 个字符之间")
        String password,

        @Schema(description = "显示名，留空则由邮箱推导", example = "张三")
        @Size(max = 64, message = "显示名过长")
        String displayName) {

    /**
     * 屏蔽明文口令，理由同 {@code LoginRequest}：record 默认实现会打印全部字段。
     */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***, displayName=" + displayName + "]";
    }

}
