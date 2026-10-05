package com.things.link.rule.api.controller;
import com.things.link.rule.application.RuleManagementAccess;
import com.things.link.rule.application.RuleNodeCatalog;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;
/** 仅向管理角色提供真实节点的用途及配置目录。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "规则节点目录", description = "读取消息规则可配置的节点类型")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/rule-node-types")
public class RuleNodeCatalogController {
    /** 用途目录。 */
    private final RuleNodeCatalog catalog;
    /** 当前项目授权。 */
    private final RuleManagementAccess access;
    /** 装配目录与授权。 */
    public RuleNodeCatalogController(RuleNodeCatalog catalog, RuleManagementAccess access) { this.catalog = catalog; this.access = access; }
    /**
     * 返回允许的条件和动作Schema；目录不构成写授权。
     *
     * @param projectId 接口指定的项目标识
     * @return 当前接口的操作结果，响应结构见 {@code RuleNodeCatalog.Catalog}
     */
    @GetMapping @Operation(operationId="getRuleNodeCatalog", summary="查询规则节点配置目录", description = "返回允许的条件和动作Schema；目录不构成写授权。")
    public RuleNodeCatalog.Catalog get(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId) { access.read(projectId, false); return catalog.view(); }
}
