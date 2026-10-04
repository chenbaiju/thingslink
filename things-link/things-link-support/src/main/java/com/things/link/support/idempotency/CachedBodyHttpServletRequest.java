package com.things.link.support.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 把请求体完整读入内存，并允许<b>重复读取</b>的请求包装器。
 *
 * <h2>为什么不用 Spring 的 ContentCachingRequestWrapper</h2>
 * 那个类是为「事后日志」设计的：它在下游读取请求体的同时旁路缓存一份，供处理完成
 * <b>之后</b>取用。它<b>不支持重放</b> —— 一旦流被读完，下游的
 * {@code @RequestBody} 就拿不到数据了。
 *
 * <p>幂等需要的顺序恰好相反：必须<b>先</b>读完请求体算出哈希、据此决定是否执行业务，
 * <b>然后</b>还要把完整的请求体交给 Controller。这个类做的就是这件事。
 *
 * <p>踩过的坑：直接用 ContentCachingRequestWrapper 预读会让所有带请求体的写接口
 * 返回 400（Required request body is missing），而错误信息完全指不到真正的原因。
 *
 * <h2>内存占用</h2>
 * 整个请求体驻留内存，因此架构文档 8.3 要求由前置过滤器按真实读取字节数执行 1 MiB 硬上限。
 * 有限构造器会多读一个字节识别 chunked 越界并直接拒绝，绝不会把部分请求体交给 Controller。
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    /**
     * @param request 原始请求。构造时其输入流会被完整读取
     * @throws IOException 读取请求体失败
     */
    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    /**
     * 读取有限请求体；多读一个字节才能识别 chunked 请求是否越界，不能只信客户端 Content-Length。
     *
     * @param request 原始请求
     * @param maximumBytes 最大允许字节数
     * @throws IOException 网络读取失败
     * @throws RequestBodyTooLargeException 实际请求体超过上限
     */
    public CachedBodyHttpServletRequest(HttpServletRequest request, int maximumBytes) throws IOException {
        super(request);
        byte[] prefix = request.getInputStream().readNBytes(maximumBytes + 1);
        if (prefix.length > maximumBytes) {
            throw new RequestBodyTooLargeException();
        }
        this.body = prefix;
    }

    /**
     * @return 请求体字节。调用方不得修改返回的数组
     */
    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                // 数据已全部在内存里，永远就绪
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // 异步读取对内存中的数据没有意义。抛异常而不是静默忽略：
                // 静默忽略会让调用方以为注册成功、然后一直等不到回调
                throw new UnsupportedOperationException("请求体已缓存在内存中，不支持异步读取");
            }

            @Override
            public int read() {
                return source.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset charset = getCharacterEncoding() != null
                ? Charset.forName(getCharacterEncoding())
                : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

}
