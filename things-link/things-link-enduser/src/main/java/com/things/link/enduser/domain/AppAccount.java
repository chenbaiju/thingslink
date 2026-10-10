package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 本人可读的账户资料，不含认证秘密。
 * @param id 终端用户标识
 * @param username 用户名
 * @param displayName 可空显示名称
 * @param createdAt 创建时间
 * @param projectRole 当前项目角色
 * @param passwordChangeAllowed 当前项目是否允许写入；实际改密仍重新仲裁
 */
public record AppAccount(UUID id, String username, String displayName, Instant createdAt,
                         EndUserRole projectRole, boolean passwordChangeAllowed) { }
