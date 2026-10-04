package com.things.link.simulator.application.ota;

import java.time.Duration;

/**
 * 取 artifact 字节的设备侧端口。
 *
 * <p>生产实现是 {@code HttpRangeArtifactSource}（{@code java.net.http.HttpClient} + 短时授权地址）；
 * 测试实现可以直接返回内存分片。端口只负责「取字节并如实报告服务器观察到的结果」，
 * <b>不负责校验摘要</b>：摘要是状态机的安全裁决（ADR0131 的 {@code VERIFYING} 语义），
 * 如果让传输层顺便判摘要，就会出现「传输成功」与「镜像可信」被混为一谈的风险。</p>
 */
public interface OtaArtifactSource {

    /**
     * 从 {@code startOffset} 开始取回一段 artifact 字节。
     *
     * @param uri 短期下载地址（平台授权响应里的 {@code downloadUrl}）
     * @param startOffset 本次请求的起始偏移，0 表示从头开始
     * @param expectedLength 预期 artifact 总长度；服务器声明的总长必须与它一致
     * @param budget 本次请求允许消耗的时间预算，必须为非负非零
     * @return 收到的字节与服务器观察到的范围信息
     * @throws OtaArtifactException 服务器拒绝续传、artifact 超限、长度不符、超时或传输失败时
     */
    Chunk fetch(String uri, long startOffset, long expectedLength, Duration budget);

    /**
     * 一次取字节的结果。
     *
     * <p>{@code contentRangeAccepted} 必须如实反映服务器是否真的按 {@code Range} 返回了
     * {@code 206 Content-Range}：服务器忽略 Range 而回 200 时，这意味着它把整个对象从 0 发回，
     * 设备不能把这个响应当成「从 offset 续传成功」，否则会把 0 起始的字节写进续传位置，造成静默损坏。</p>
     *
     * @param payload 收到的原始字节（可能是完整对象的尾部，也可能被实现按分片上限截断）
     * @param contentRangeAccepted 服务器是否返回了以请求偏移开始的 206 Content-Range
     * @param contentRangeStart 服务器声明的起始偏移；没有 Content-Range 时为 0
     * @param totalLength 服务器声明的 artifact 总长度；未知时为 -1
     */
    record Chunk(byte[] payload, boolean contentRangeAccepted, long contentRangeStart, long totalLength) {

        /** 防御性复制，避免调用方修改传输缓冲区。 */
        public Chunk {
            if (payload == null) {
                throw new IllegalArgumentException("分片载荷不能为空");
            }
            payload = payload.clone();
            if (contentRangeStart < 0L) {
                throw new IllegalArgumentException("Content-Range 起始偏移不能为负数");
            }
            if (totalLength < -1L) {
                throw new IllegalArgumentException("artifact 总长度不能小于 -1");
            }
        }

        /**
         * 返回载荷的独立副本。
         *
         * @return 与本对象无关的字节数组
         */
        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
