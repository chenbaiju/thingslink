package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * App 改密请求体。
 *
 * <p>改密必须提交原口令（60008 独立错误）：调用者已持有有效访问令牌、身份已确认，
 * 但「换口令」这一动作本身必须证明旧口令的持有者本人发起，否则一个临时拿到令牌的人
 * 就能把账号永久锁成自己的。成功后会撤销该终端用户的<b>全部</b>会话（含调用者自己）。
 *
 * @param oldPassword 原口令
 * @param newPassword 新口令
 */
@Schema(description = "App 改密请求")
public record AppChangePasswordRequest(

        @Schema(description = "原口令")
        @NotBlank(message = "原口令不能为空")
        @Size(max = 128, message = "口令长度不能超过 128")
        String oldPassword,

        // 最短长度由应用服务校验（MIN_PASSWORD_LENGTH=8，与预置账号一致），
        // 这里只挡超长输入（bcrypt 计算成本随长度增长的防御上限）
        @Schema(description = "新口令（最短 8 位）")
        @NotBlank(message = "新口令不能为空")
        @Size(max = 128, message = "口令长度不能超过 128")
        String newPassword) {

    @Override
    public String toString() {
        return "AppChangePasswordRequest[oldPassword=***, newPassword=***]";
    }

}
