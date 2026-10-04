package com.things.link.integration.domain;
import java.util.Optional;
import java.util.UUID;
/** 与原命令同事务，普通APP只允许创建/读取收据。 */
public interface CommandReceiptRepository {
    Optional<CommandReceipt> byKey(UUID tenant,UUID project,UUID key,String hash);
    Optional<CommandReceipt> byCommand(UUID tenant,UUID project,UUID key,UUID command);
    void insert(CommandReceipt receipt);
}
