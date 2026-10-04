package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.DashboardPublicHostContentPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** 当前入口需要数据库准入；历史摘要交付不创建无必要的数据库事务。 */
@Service
public class DashboardCurrentPublicHostReader {
    /** 事务读取选择与同代内容的唯一端口，锁保持至方法提交。 */
    private final DashboardPublicHostContentPort content;
    /** @param content 同次完整核验的内容端口 */
    public DashboardCurrentPublicHostReader(DashboardPublicHostContentPort content) { this.content = content; }
    /** @param file 白名单当前文件 @return 同一准入代次的实际字节 */
    @Transactional(readOnly = true)
    public Optional<byte[]> read(String file) { return content.readCurrent(file); }
}
