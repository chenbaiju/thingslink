package com.things.link.ota.application;

/** 不可导出供应商签名单次受控入口；实现必须把取消回调接到真实网络调用并在退出时注销。 */
@FunctionalInterface
public interface OtaControlledReleaseSigner {
    /** 只有真实供应商调用结束才可返回或抛错；取消信号不代表物理完成，禁止退回无预算签名器。 */
    OtaReleaseSigner.Response sign(OtaReleaseSigner.Request request, OtaSigningControl control);
}
