package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.sql.*;
/** 显式双轴与Key谓词叠加普通RLS，不查询telemetry表。 */
@Repository
public class JdbcCommandReceiptRepository implements CommandReceiptRepository {
    private final JdbcTemplate jdbc;
    public JdbcCommandReceiptRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Optional<CommandReceipt> byKey(UUID tenant,UUID project,UUID key,String hash){
        return jdbc.query("SELECT * FROM integ_command_receipt WHERE tenant_id=? AND project_id=? AND key_id=? AND client_key_hash=?",this::map,tenant,project,key,hash).stream().findFirst();
    }
    @Override public Optional<CommandReceipt> byCommand(UUID tenant,UUID project,UUID key,UUID command){
        return jdbc.query("SELECT * FROM integ_command_receipt WHERE tenant_id=? AND project_id=? AND key_id=? AND command_id=?",this::map,tenant,project,key,command).stream().findFirst();
    }
    @Override public void insert(CommandReceipt r){
        int changed=jdbc.update("""
            INSERT INTO integ_command_receipt(tenant_id,project_id,project_generation,key_id,issuer_account_id,client_key_hash,command_id,device_id)
            VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,project_id,key_id,client_key_hash) DO NOTHING
            """,r.tenant(),r.project(),r.generation(),r.key(),r.issuer(),r.clientHash(),r.command(),r.device());
        if(changed==0&&!byKey(r.tenant(),r.project(),r.key(),r.clientHash()).orElseThrow().equals(r))throw new IllegalStateException("命令收据冲突");
    }
    private CommandReceipt map(ResultSet rs,int row)throws SQLException{return new CommandReceipt(rs.getObject("tenant_id",UUID.class),rs.getObject("project_id",UUID.class),
        rs.getLong("project_generation"),rs.getObject("key_id",UUID.class),rs.getObject("issuer_account_id",UUID.class),rs.getString("client_key_hash"),rs.getObject("command_id",UUID.class),rs.getObject("device_id",UUID.class));}
}
