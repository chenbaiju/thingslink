package com.things.link.project.application;

import com.things.link.project.domain.ProjectRegion;
import com.things.link.project.domain.ProjectRegionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目区域目录用例。
 *
 * <p>当前只提供只读消费接口。区域开放与关闭属于平台基础设施管理，不应出现在
 * 普通后台管理平台里；后续做平台运营后台时，再在更高权限的运营面补维护入口。
 */
@Service
public class ProjectRegionService {

    /** 区域目录仓储。它是平台级目录，不带当前租户或当前项目过滤。 */
    private final ProjectRegionRepository projectRegionRepository;

    /**
     * 创建项目区域用例。
     *
     * @param projectRegionRepository 区域目录仓储
     */
    public ProjectRegionService(ProjectRegionRepository projectRegionRepository) {
        this.projectRegionRepository = projectRegionRepository;
    }

    /**
     * 列出可见区域目录。
     *
     * @return 区域目录
     */
    @Transactional(readOnly = true)
    public List<ProjectRegion> listVisible() {
        return projectRegionRepository.findVisible();
    }

}
