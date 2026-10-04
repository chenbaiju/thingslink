package com.things.link.project.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建项目请求。
 *
 * <p>区域必须显式选择。官方快速上手把「创建项目」作为登录后的第一步，且创建时
 * 需要选区域；更重要的是区域一经创建不可变更（架构文档 7.1），设备接入域名、
 * MQTT 地址与数据存储位置都会从它派生。这里宁愿当前只有一个可选项，也不能把
 * 这个不可逆选择藏成服务端默认值。
 *
 * @param name 项目名称。不要求唯一，重名是正常的
 * @param region 区域编码。一经创建不可变更
 * @param timezone IANA 项目时区；省略时使用大陆产品默认值 {@code Asia/Shanghai}
 */
@Schema(description = "创建项目请求")
public record CreateProjectRequest(

        @Schema(description = "项目名称", example = "厂区环境监测")
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 128, message = "项目名称过长")
        String name,

        @Schema(description = "区域编码，创建后不可变更", example = "sh-1")
        @NotBlank(message = "请选择项目区域")
        @Pattern(regexp = "^[a-z]{2}-[0-9]+$", message = "项目区域编码格式不正确")
        String region,

        @Schema(description = "IANA 项目时区；省略时默认 Asia/Shanghai", example = "Asia/Shanghai")
        @Size(max = 64, message = "项目时区标识过长")
        String timezone) {
}
