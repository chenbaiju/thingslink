package com.things.link.enduser.api.controller;
import com.things.link.enduser.api.dto.request.UpdateEndUserNotificationContactRequest;
import com.things.link.enduser.api.dto.response.EndUserNotificationContactResponse;
import com.things.link.enduser.api.support.EndUserNotificationContactRequestParser;
import com.things.link.enduser.application.EndUserNotificationContactService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Console当前项目接收号码，读写均由用例层复验OWNER/ADMIN。 */
@RestController
@RequestMapping(value="/api/v1/projects/{projectId}/end-users/{appUserId}/notification-contact",produces="application/json")
@Tag(name="终端用户接收号码",description="项目管理员维护当前项目终端用户电话与短信接收配置")
@SecurityRequirement(name="consoleAccessBearer")
public class EndUserNotificationContactController {
    private final EndUserNotificationContactService service;
    private final EndUserNotificationContactRequestParser parser;
    /**
     * @param service 项目联系配置用例
     * @param parser 封闭原始请求解析器
     */
    public EndUserNotificationContactController(EndUserNotificationContactService service,EndUserNotificationContactRequestParser parser){this.service=service;this.parser=parser;}
    /**
     * 读取本项目已分配账号接收号码，普通成员不能查看。
     * @param projectId 当前项目
     * @param appUserId 本项目已分配角色的终端用户
     * @return 配置及版本；缺行为空号码、零版本，未分配或跨范围统一404
     */
    @GetMapping
    @Operation(operationId="getEndUserNotificationContact",summary="读取终端用户接收号码",description="仅当前项目OWNER/ADMIN可读，ARCHIVED保留读取；不展示共享账号在其他项目的号码。")
    @ApiResponses({
        @ApiResponse(responseCode="200",description="当前项目接收配置"),
        @ApiResponse(responseCode="401",description="未认证",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="403",description="管理权限不足",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="404",description="项目或目标不可见",content=@Content(schema=@Schema(implementation=ApiError.class)))
    })
    public EndUserNotificationContactResponse read(@PathVariable UUID projectId,@PathVariable UUID appUserId){return EndUserNotificationContactResponse.from(service.read(projectId,appUserId));}
    /**
     * 按读取版本保存本项目号码，不发送通知或验证消息。
     * @param projectId 当前项目
     * @param appUserId 本项目已分配角色的终端用户
     * @param body 含两个可空号码及expectedRevision的封闭原始信封
     * @return 保存后的配置，旧版本409，项目只读403；不更改其他项目或账号通知偏好
     */
    @PutMapping(consumes="application/json")
    @Operation(operationId="updateEndUserNotificationContact",summary="更新终端用户接收号码",description="仅OWNER/ADMIN在ACTIVE项目写入；用户锁内按expectedRevision进行CAS，旧版本60062，不产生真实电话或短信。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=UpdateEndUserNotificationContactRequest.class)))
    @ApiResponses({
        @ApiResponse(responseCode="200",description="接收配置已保存或未变化"),
        @ApiResponse(responseCode="400",description="参数不合法",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="401",description="未认证",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="403",description="权限不足或项目只读",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="404",description="项目或目标不可见",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="409",description="配置版本或公共幂等冲突",content=@Content(schema=@Schema(implementation=ApiError.class)))
    })
    public EndUserNotificationContactResponse update(@PathVariable UUID projectId,@PathVariable UUID appUserId,@RequestBody byte[] body){
        var request=parser.parse(body);
        return EndUserNotificationContactResponse.from(service.update(projectId,appUserId,request.voiceNumber(),request.smsNumber(),Long.parseLong(request.expectedRevision())));
    }
}
