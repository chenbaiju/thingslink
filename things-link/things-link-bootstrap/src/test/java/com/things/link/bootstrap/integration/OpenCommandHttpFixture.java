package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.telemetry.application.PublicCommandIdentity;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 命令真实HTTP/事务及并发资格共享夹具；不继承其他类的测试方法。 */
abstract class OpenCommandHttpFixture extends OpenDeviceHttpFixture {
    @Autowired OpenCommandService service;
    @Autowired ApiKeyAuthenticationService authentication;
    @Autowired TransactionLocalRlsScope scope;
    UUID definition;ApiKeyPrincipal principal;
    @Override String key(){return keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("control",List.of("device:control"),List.of("127.0.0.1/32"),Instant.now().plusSeconds(3600))).secret();}
    @BeforeEach void command(){
        definition=Uuid7.generate();
        owner.update("INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?,?,?,?,'start','启动','{}','{}',30)",definition,tenant,project,type);
        principal=authentication.authenticate(secret,"127.0.0.1");
    }
    @AfterEach void removeCommands(){
        for(String table:List.of("integ_command_receipt","sys_outbox_event","ts_device_command_claim","ts_device_command_attempt","ts_device_command","dev_access_binding","dev_command_definition"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);
    }
    int count(String table){return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project);}
    java.net.http.HttpResponse<String> submit(String key,String business,String input)throws Exception{
        return request("POST","/api/open/v1/devices/"+device+"/commands","{\"commandKey\":\"start\",\"input\":"+input+"}",null,Map.of("X-Api-Key",key,"Idempotency-Key",business));
    }
}
