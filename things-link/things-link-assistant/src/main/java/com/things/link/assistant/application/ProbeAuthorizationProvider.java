package com.things.link.assistant.application;

import java.util.Optional;

/** 缺省空授权；不是允许浏览器创建任意付费批次的入口。 */
public interface ProbeAuthorizationProvider {
    /**
     * 查询服务端预先冻结的授权，不授予或重置付费次数。
     * @param id 待匹配的授权标识
     * @return 匹配的授权；未配置或标识不符时为空
     */
    Optional<ProbeAuthorization> find(String id);
    /**
     * 获取服务端当前唯一授权，缺省禁用探针。
     * @return 当前冻结授权；未配置时为空
     */
    default Optional<ProbeAuthorization> current() { return Optional.empty(); }
}
