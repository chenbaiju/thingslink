package com.things.link.iam.application;

import com.things.link.iam.domain.Permission;

import java.util.List;

/**
 * 菜单树节点。
 *
 * <p>形状刻意与控制台前端的 {@code AppRouteRecord} 一致（{@code things-link-console/
 * src/types/router/index.ts}）—— 后端下发的菜单会被前端直接注册成 Vue Router 路由，
 * 中间没有转换层。多加一层字段名映射只会让「后端加了字段但前端没生效」这类问题
 * 变得更难查。
 *
 * <h2>title 下发的是 i18n key，不是中文文案</h2>
 * 例如 {@code menus.dashboard.overview}。翻译文件在前端，后端不持有任何语言版本的
 * 文案；下发中文的话，前端切语言时菜单会是唯一不跟着变的部分。
 *
 * @param name               路由名，前端用于编程式跳转，全局唯一
 * @param path               路由路径。一级菜单以 {@code /} 开头，子级<b>不能</b>以
 *                           {@code /} 开头，前端会拼接父路径
 * @param component          前端组件路径，相对 {@code src/views}，不含 {@code .vue}
 * @param meta               菜单元信息
 * @param requiredPermission 访问本节点所需的权限点；{@code null} 表示所有登录用户可见
 * @param children           子菜单；{@code null} 表示这是叶子节点
 */
public record MenuItem(
        String name,
        String path,
        String component,
        Meta meta,
        Permission requiredPermission,
        List<MenuItem> children) {

    /**
     * 菜单元信息。字段名与前端 {@code RouteMeta} 逐字对应。
     *
     * <p>布尔字段用包装类型而非 {@code boolean}：{@code null} 表示「未设置」，
     * 序列化时会被省略。用基本类型的话每个节点都会带上一串 {@code false}，
     * 前端行为不变但响应体会明显变大，且看不出哪些是刻意配置的。
     *
     * @param title     标题的 i18n key
     * @param icon      图标名（iconify 格式，如 {@code ri:pie-chart-line}）
     * @param keepAlive 是否缓存组件实例
     * @param fixedTab  是否固定标签页（不可关闭）
     * @param isHide    是否在菜单中隐藏（路由仍然注册，可直接访问）
     * @param isHideTab 是否不生成标签页
     * @param isFullPage 是否全屏页（不套布局）
     * @param authList  本页面上的按钮级权限点
     */
    public record Meta(
            String title,
            String icon,
            Boolean keepAlive,
            Boolean fixedTab,
            Boolean isHide,
            Boolean isHideTab,
            Boolean isFullPage,
            List<AuthPoint> authList) {
    }

    /**
     * 按钮级权限点。
     *
     * <p>前端 {@code useAuth().hasAuth('...')} 按 {@code authMark} 匹配。
     *
     * <p><b>它只决定按钮显不显示，不构成授权</b>：架构文档 7.2 要求 Controller 与
     * 领域服务都必须自行校验。把按钮藏起来拦不住任何人 —— 直接调接口即可。
     *
     * @param title      按钮说明，用于将来的角色配置界面
     * @param permission 对应的权限点
     */
    public record AuthPoint(String title, Permission permission) {

        /**
         * 权限点标识。
         *
         * <p>存在的意义是让 {@code api} 层不必引用 {@code domain} 包的
         * {@link Permission}：跨层直接取枚举会把领域类型泄进 HTTP 契约，
         * 之后给枚举改名就变成了破坏性接口变更（架构文档 10.3）。
         *
         * @return 形如 {@code dashboard:read} 的字符串
         */
        public String code() {
            return permission.code();
        }
    }

}
