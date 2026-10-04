package com.things.link.project.domain;

import java.util.UUID;

/**
 * 租户仓储契约。
 *
 * <p>接口在领域层、实现在 {@code infrastructure} 层，依赖方向由此反转
 * （架构文档 10.3）。
 */
public interface TenantRepository {

    /**
     * 新建一个租户。
     *
     * @param id   租户 ID（UUIDv7，由调用方生成）
     * @param name 租户名称
     */
    void create(UUID id, String name);

}
