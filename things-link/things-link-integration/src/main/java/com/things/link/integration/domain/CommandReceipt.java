package com.things.link.integration.domain;
import java.util.UUID;
/** 公开命令归因与恢复事实，不持久客户端业务键或秘密。 */
public record CommandReceipt(UUID tenant,UUID project,long generation,UUID key,UUID issuer,String clientHash,UUID command,UUID device){}
