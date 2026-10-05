package com.things.link.assistant.application;

import com.things.link.assistant.domain.ProbeLedger.Attempt;
/** 单次合成探针传输端口；实现不得缓存凭据或自动重试付费请求。 */
public interface ProbeTransport {
    /**
     * 检查传输装配是否启用，不发起网络请求，也不证明供应商可用。
     * @return 传输已装配且允许尝试时为真
     */
    boolean ready();
    /**
     * 在既有认领期限内同步执行一次合成探针；返回前关闭本次调用连接。
     * @param attempt 已持久认领的机会，包含原始截止时间
     * @param authorization 服务端冻结的批次授权，不接受客户端自建授权
     * @param credential 仅供本次同步调用的凭据字节，不得保存或放入返回值
     * @return 已验证的固定分类及用量，不包含模型正文
     */
    ProbeResult execute(Attempt attempt,ProbeAuthorization authorization,byte[] credential);
}
