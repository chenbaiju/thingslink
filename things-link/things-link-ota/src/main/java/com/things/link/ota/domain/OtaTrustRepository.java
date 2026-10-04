package com.things.link.ota.domain;

import com.things.link.shared.page.CursorPage;
import java.util.Optional;
import java.util.UUID;

/** 签名信任域持久边界，不负责密码学资格。 */
public interface OtaTrustRepository {
    /** 串行导入身份，最终唯一性仍由数据库约束保证。 */
    void lockImport(UUID tenantId,UUID projectId,String trustDomain);
    /** 当前域及精确历史包联合读取；共享锁和排他锁不能同时选择。 */
    Optional<OtaTrustState> find(UUID projectId,String trustDomain,boolean lockExclusive,boolean lockShared);
    /** 当前指针与首包在同一调用方事务创建。 */
    void create(OtaTrustState state);
    /** 锁内CAS替换当前指针并追加不可变包，错误修订不留下历史孤儿。 */
    boolean replace(long expectedRevision,OtaTrustState state);

    /**
     * 项目内全部信任域的只读游标集合，按域名升序，{@code LIMIT limit+1}决定是否续页。
     *
     * <p>只做读取：不取锁、不改变任何域或密钥状态。{@code trust_domain}是全局主键，
     * 因此在项目内天然唯一，单个域名列即可构成确定性键集，不需要并列键。
     * 结果带当前包的规范字节，供调用方从<b>已登记</b>字节解析ACTIVE发布键摘要；
     * 仓储本身不做密码学判断，也不把私钥材料或状态变更入口带进读取路径。
     *
     * @param projectId 精确项目
     * @param cursor 上一页返回的不透明位置，首页为空
     * @param limit 单页上限，1到100
     * @return 按域名升序的当前域游标页；无域返回空页而不是错误
     */
    CursorPage<OtaTrustState> domains(UUID projectId,String cursor,int limit);
}
