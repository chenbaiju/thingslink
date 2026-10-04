package com.things.link.bootstrap.integration;
import com.things.link.integration.application.ApiKeyManagementService;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 公开设备与历史/告警验收共用真实项目、模型与设备事实。 */
abstract class OpenDeviceHttpFixture extends ApiKeyHttpFixture {
    @Autowired ApiKeyManagementService keys;
    UUID type,model,device,unbound;
    String secret;
    @BeforeEach void seedDevices(){
        type=Uuid7.generate();model=Uuid7.generate();device=Uuid7.generate();unbound=Uuid7.generate();
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind) VALUES (?,?,?,?,'公开类型','STANDARD','DIRECT')",type,tenant,project,"t"+type.toString().replace("-",""));
        String snapshot=modelSnapshot();
        owner.update("""
            INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
            VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
            """,model,tenant,project,type,snapshot,snapshot);
        insertDevice(device,model);insertDevice(unbound,null);
        owner.update("INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,reported_model_version) VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb)",
            device,tenant,project,"{\"value\":9007199254740993}","{\"value\":\"2026-09-20T00:00:00Z\"}","{\"value\":\""+model+"\"}");
        secret=key();
    }
    String modelSnapshot(){return "{\"properties\":{\"value\":{\"dataType\":\"NUMBER\"},\"empty\":{\"dataType\":\"NUMBER\"}},\"events\":{\"changed\":{}},\"commands\":{\"start\":{\"large\":9007199254740993,\"precise\":1.12345678901234567890123456789}}}";}
    void insertDevice(UUID id,UUID version){owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,thing_model_version_id,status) VALUES (?,?,?,?,?,'公开设备',?,'ONLINE')",id,tenant,project,type,"d"+id.toString().replace("-",""),version);}
    String key(){return keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("read",List.of("device:read"),List.of("127.0.0.1/32"),Instant.now().plusSeconds(3600))).secret();}
    @AfterEach void removeDevices(){
        owner.update("DELETE FROM dev_shadow WHERE project_id=?",project);
        owner.update("DELETE FROM dev_device WHERE project_id=?",project);
        owner.update("DELETE FROM dev_thing_model_version WHERE project_id=?",project);
        owner.update("DELETE FROM dev_type WHERE project_id=?",project);
    }
    java.net.http.HttpResponse<String> get(String path,String credential)throws Exception{return request("GET","/api/open/v1"+path,null,null,Map.of("X-Api-Key",credential));}
}
