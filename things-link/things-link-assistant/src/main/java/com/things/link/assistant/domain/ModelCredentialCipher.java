package com.things.link.assistant.domain;
/** 加密器没有项目密文或解密结果缓存。 */
public interface ModelCredentialCipher {
    /**
     * 按凭据身份及版本加密项目秘密，不缓存明文。
     * @param identity 含归属、凭据标识及版本的可信记录
     * @param secret 本次提交的项目秘密，不得记录到日志
     * @return 禁用状态的新密文记录，启用须另行执行受权流程
     */
    ModelCredential encrypt(ModelCredential identity, String secret);
    /**
     * 验证密文可解密且身份绑定有效，不向调用方返回明文。
     * @param credential 待验证的密文记录
     */
    void verify(ModelCredential credential);
    /**
     * 仅回调内单次交付；回调必须同步完成且不得保存字节。返回值不得含凭据。
     * @param credential 已经重新鉴权及核验版本的密文记录
     * @param callback 同步消费明文字节的单次调用，退出后字节被清零
     * @param <T> 不携带秘密的业务结果类型
     * @return 回调生成的非秘密结果
     */
    <T> T deliver(ModelCredential credential, java.util.function.Function<byte[],T> callback);
}
