package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.DashboardPublicHostContentPort;
import com.things.link.dashboard.application.publication.DashboardPublicHostUnavailableException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** ADR0149公开壳路由与有限并发；事务把当前选择准入保持到同代字节读取结束。 */
@Service
public class DashboardPublicHostService {
    /** 元数据闭集与ADR0149一致，禁止任意JSON旁置文件暴露。 */
    private static final Set<String> METADATA = Set.of("host-candidate.json", "source-receipt.json", "precache.json", "manifest.webmanifest");
    /** 限制完整ZIP/目录复验并发，等待也有截止，不作为云容量承诺。 */
    private final Semaphore permits = new Semaphore(4, true);
    /** 历史摘要只依赖受管内容，不借数据库故障扩大历史资源不可用面。 */
    private final DashboardPublicHostContentPort content;
    /** 独立代理确保current获得Spring事务，而不是自调用绕过准入。 */
    private final DashboardCurrentPublicHostReader current;
    /** @param content 完整核验后的公开字节端口 @param current 当前选择事务读取 */
    public DashboardPublicHostService(DashboardPublicHostContentPort content, DashboardCurrentPublicHostReader current) {
        this.content = content; this.current = current;
    }

    /** @param path 未解码的完整公开路径 @param query 原query @return 白名单文件或未找到 */
    public Optional<Content> read(String path, String query) {
        if (query != null && !query.isEmpty()) return Optional.empty();
        String file;
        String digest = null;
        boolean asset = false;
        boolean immutable = false;
        if (path.equals("/app/") || path.equals("/app/index.html") || path.matches("/app/app_[a-f0-9]{32}")
                || path.matches("/app/share/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            file = "index.html";
        } else if (path.startsWith("/app/releases/")) {
            var matcher = java.util.regex.Pattern.compile("/app/releases/([a-f0-9]{64})/(.+)").matcher(path);
            if (!matcher.matches() || !publicFile(matcher.group(2))) return Optional.empty();
            digest = matcher.group(1); file = matcher.group(2); immutable = true;
        } else if (path.startsWith("/app/assets/") && assetFile(path.substring(5))) {
            file = path.substring(5); asset = true; immutable = true;
        } else if (path.startsWith("/app/") && METADATA.contains(path.substring(5))) {
            file = path.substring(5);
        } else return Optional.empty();
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) throw new DashboardPublicHostUnavailableException();
            Optional<byte[]> bytes = digest != null ? content.readRelease(digest, file)
                    : asset ? content.readAsset(file) : current.read(file);
            boolean fixed = immutable;
            return bytes.map(value -> new Content(file, value, fixed));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new DashboardPublicHostUnavailableException();
        } catch (RuntimeException unavailable) {
            throw new DashboardPublicHostUnavailableException();
        } finally { if (acquired) permits.release(); }
    }
    /** 摘要目录只交付实际构建白名单；ZIP、源映射及任意层级均排除。 */
    private static boolean publicFile(String value) { return METADATA.contains(value) || Set.of("index.html", "sw.js").contains(value) || assetFile(value); }
    /** 与受管ZIP的扁平assets规则一致，不解码路径或允许目录遍历。 */
    private static boolean assetFile(String value) { return value.matches("assets/[A-Za-z0-9_-]+\\.(js|css|png|svg|woff2)"); }
    /** @param file 白名单文件名 @param bytes 已验证内容 @param immutable 是否精确不可变地址 */
    public record Content(String file, byte[] bytes, boolean immutable) {
        /** 防御复制，避免控制器/下游改写同次快照。 */
        public Content { bytes = bytes.clone(); }
        /** @return 独立响应数组 */
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
