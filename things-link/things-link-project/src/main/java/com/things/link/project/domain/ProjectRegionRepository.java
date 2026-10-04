package com.things.link.project.domain;

import java.util.List;
import java.util.Optional;

/**
 * 项目区域目录仓储。
 *
 * <p>接口放在领域层，是为了让创建项目的业务规则依赖「区域目录」这个概念，
 * 而不是依赖 JDBC 或某个配置文件。未来平台运营后台改成写表，本层契约也不必变。
 */
public interface ProjectRegionRepository {

    /**
     * 列出前端可见的区域目录。
     *
     * @return 按后端维护顺序排列的区域
     */
    List<ProjectRegion> findVisible();

    /**
     * 查找允许新建项目选择的区域。
     *
     * @param code 可用区编码
     * @return 可创建时返回目录项，否则为空
     */
    Optional<ProjectRegion> findProjectCreatable(String code);

}
