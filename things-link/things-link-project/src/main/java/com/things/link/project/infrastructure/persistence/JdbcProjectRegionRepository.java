package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.ProjectRegion;
import com.things.link.project.domain.ProjectRegionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC 的项目区域目录仓储。
 */
@Repository
public class JdbcProjectRegionRepository implements ProjectRegionRepository {

    /** 区域目录的行映射。字段来自平台维护表，不带租户或项目隔离条件。 */
    private static final RowMapper<ProjectRegion> MAPPER = (rs, rowNum) ->
            new ProjectRegion(
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("area_code"),
                    rs.getString("area_name"),
                    rs.getBoolean("enabled"),
                    rs.getBoolean("project_creation_enabled"),
                    rs.getInt("display_order"));

    /** JDBC 访问入口。区域目录不走 RLS，因此所有过滤条件都写在 SQL 里。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 JDBC 区域目录仓储。
     *
     * @param jdbcTemplate JDBC 访问入口
     */
    public JdbcProjectRegionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 列出可见区域。
     *
     * @return 区域目录
     */
    @Override
    public List<ProjectRegion> findVisible() {
        return jdbcTemplate.query("""
                SELECT code, name, area_code, area_name, enabled,
                       project_creation_enabled, display_order
                  FROM sys_project_region
                 WHERE enabled = true
                 ORDER BY display_order, code
                """, MAPPER);
    }

    /**
     * 查找允许创建项目的区域。
     *
     * @param code 可用区编码
     * @return 可创建时返回目录项
     */
    @Override
    public Optional<ProjectRegion> findProjectCreatable(String code) {
        return jdbcTemplate.query("""
                        SELECT code, name, area_code, area_name, enabled,
                               project_creation_enabled, display_order
                          FROM sys_project_region
                         WHERE code = ?
                           AND enabled = true
                           AND project_creation_enabled = true
                        """, MAPPER, code)
                .stream().findFirst();
    }

}
