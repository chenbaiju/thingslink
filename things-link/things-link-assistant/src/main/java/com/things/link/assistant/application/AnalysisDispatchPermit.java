package com.things.link.assistant.application;

import java.util.UUID;

/** 仅供平台内部一次执行认领和停止后释放，绝不成为HTTP响应。 */
public record AnalysisDispatchPermit(AnalysisCallView call, UUID token) {
    public AnalysisDispatchPermit {
        if (call == null || token == null) throw new IllegalArgumentException("missing analysis permit");
    }
    @Override public String toString() { return "AnalysisDispatchPermit[id=" + call.id() + ",token=REDACTED]"; }
}
