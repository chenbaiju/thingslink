package com.things.link.project.domain;

import com.things.link.project.application.RuntimeQuotaPolicyCommand;
import com.things.link.project.application.QuotaPolicyTemplateChanged;

import java.util.Optional;
import java.util.UUID;

/**
 * 可复用配额模板运行时字段的 CAS 更新端口。
 */
public interface RuntimeQuotaPolicyRepository {

    /**
     * 仅在策略版本匹配时更新阈值，并返回所有引用租户的失效事件。
     *
     * @param policyId 策略模板 ID
     * @param expectedPolicyVersion 调用方读取时看到的模板版本
     * @param command 新运行时阈值
     * @return 成功后的模板事件；CAS 失败时为空
     */
    Optional<QuotaPolicyTemplateChanged> update(UUID policyId, long expectedPolicyVersion,
                                                RuntimeQuotaPolicyCommand command);
}
