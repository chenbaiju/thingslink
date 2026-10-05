package com.things.link.assistant.infrastructure.transport;

/** 合成标准输入的离线请求编码证明；不读取项目、凭据或配置，不联网。 */
class ModelRequestBindingProbe {
    public static void main(String[] args) throws Exception {
        System.out.write(ModelRequestFingerprint.encode(System.in.readNBytes(16385)));
    }
}
