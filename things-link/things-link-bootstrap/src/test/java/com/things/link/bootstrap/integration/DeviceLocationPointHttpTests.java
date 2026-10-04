package com.things.link.bootstrap.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** PS-026a：真实HTTP身份、PG空间类型、事务CAS及生命周期，不用模拟仓储。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceLocationPointHttpTests extends ApiKeyHttpFixture {
    UUID device;
    @BeforeEach void device() {
        device=UUID.randomUUID();
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,location) VALUES(?,?,?,'geo','geo','机房A')",device,tenant,project);
    }
    @AfterEach void clearDevices() { owner.update("DELETE FROM dev_device WHERE project_id=?",project); }
    String path() { return "/api/v1/projects/"+project+"/devices/"+device+"/location-point"; }
    java.net.http.HttpResponse<String> put(String body) throws Exception { return request("PUT",path(),body,token(),Map.of()); }
    String body(double lon,double lat,String version) { return "{\"longitude\":"+lon+",\"latitude\":"+lat+",\"version\":\""+version+"\"}"; }
    long audits() { return owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='DEVICE_LOCATION_POINT_CHANGED'",Long.class,project); }

    @Test void emptySetSpatialQueryClearAndOldClientCompatibility() throws Exception {
        var read=request("GET",path(),null,token(),Map.of()); assertThat(read.statusCode()).isEqualTo(200);
        var initial=json.readTree(read.body()); assertThat(initial.get("longitude").isNull()).isTrue();
        assertThat(initial.get("latitude").isNull()).isTrue(); assertThat(initial.get("version").asString()).isEqualTo("0");
        var saved=put(body(121.4737,31.2304,"0")); assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).get("longitude").asDouble()).isEqualTo(121.4737);
        assertThat(json.readTree(saved.body()).get("version").asString()).isEqualTo("1");
        assertThat(owner.queryForObject("SELECT ST_DWithin(location_point,ST_SetSRID(ST_MakePoint(121.4737,31.2304),4326)::geography,1) FROM dev_device WHERE id=?",Boolean.class,device)).isTrue();
        assertThat(owner.queryForObject("SELECT ST_DWithin(location_point,ST_SetSRID(ST_MakePoint(0,0),4326)::geography,1) FROM dev_device WHERE id=?",Boolean.class,device)).isFalse();
        var old=request("PUT",path().replace("/location-point",""),"{\"name\":\"old client\",\"location\":\"机房B\"}",token(),Map.of());
        assertThat(old.statusCode()).as(old.body()).isEqualTo(200);
        var after=json.readTree(request("GET",path(),null,token(),Map.of()).body()); assertThat(after.get("longitude").asDouble()).isEqualTo(121.4737);
        assertThat(after.get("version").asString()).isEqualTo("1");
        assertThat(put("{\"longitude\":null,\"latitude\":null,\"version\":\"1\"}").statusCode()).isEqualTo(200);
        assertThat(owner.queryForObject("SELECT location_point IS NULL AND location_point_version=2 AND location='机房B' FROM dev_device WHERE id=?",Boolean.class,device)).isTrue();
        assertThat(audits()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"{\"longitude\":181,\"latitude\":0,\"version\":\"0\"}","{\"longitude\":0,\"latitude\":-91,\"version\":\"0\"}","{\"longitude\":0,\"version\":\"0\"}","{\"longitude\":null,\"latitude\":1,\"version\":\"0\"}","{\"longitude\":0,\"latitude\":0}","{\"version\":\"9223372036854775808\"}"})
    void malformedNeverMutates(String body) throws Exception {
        var result=put(body); assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
        assertThat(owner.queryForObject("SELECT location_point IS NULL AND location_point_version=0 FROM dev_device WHERE id=?",Boolean.class,device)).isTrue(); assertThat(audits()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"{\"longitude\":\"12\",\"latitude\":0,\"version\":\"0\"}","{\"version\":0}","{\"longitude\":true,\"latitude\":0,\"version\":\"0\"}","{\"longitude\":1e400,\"latitude\":0,\"version\":\"0\"}"})
    void knownFieldTypesCannotBeCoerced(String input) throws Exception {
        assertThat(put(input).statusCode()).isEqualTo(400); assertThat(audits()).isZero();
    }
    @Test void extremesAndLargeVersionAreLossless() throws Exception {
        owner.update("UPDATE dev_device SET location_point_version=9007199254740993 WHERE id=?",device);
        var saved=put(body(-180,90,"9007199254740993")); assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).get("version").asString()).isEqualTo("9007199254740994");
        assertThat(put(body(180,-90,"9007199254740994")).statusCode()).isEqualTo(200);
        owner.update("UPDATE dev_device SET location_point_version=9223372036854775807 WHERE id=?",device);
        assertThat(put(body(0,0,"9223372036854775807")).statusCode()).isEqualTo(409);
        assertThat(audits()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"OWNER","ADMIN","OPERATOR","VIEWER"})
    void currentRoleControlsWrites(String role) throws Exception {
        owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",role,project,account);
        assertThat(request("GET",path(),null,token(),Map.of()).statusCode()).isEqualTo(200);
        assertThat(put(body(0,0,"0")).statusCode()).isEqualTo(role.equals("OWNER")||role.equals("ADMIN")?200:403);
    }
    @Test void projectForeignDeviceDeletedDeviceAndRemovedMembershipFailClosed() throws Exception {
        String jwt=token();
        assertThat(request("GET",path().replace(project.toString(),UUID.randomUUID().toString()),null,jwt,Map.of()).statusCode()).isEqualTo(404);
        assertThat(request("GET",path().replace(device.toString(),UUID.randomUUID().toString()),null,jwt,Map.of()).statusCode()).isEqualTo(404);
        owner.update("UPDATE dev_device SET deleted_at=now() WHERE id=?",device);
        assertThat(put(body(0,0,"0")).statusCode()).isEqualTo(404);
        assertThat(request("GET",path(),null,jwt,Map.of()).statusCode()).isEqualTo(404);
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",project,account);
        assertThat(request("GET",path(),null,jwt,Map.of()).statusCode()).isEqualTo(401); assertThat(audits()).isZero();
    }
    @Test void archivedProjectRemainsReadableButRejectsMutation() throws Exception {
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",project);
        assertThat(request("GET",path(),null,token(),Map.of()).statusCode()).isEqualTo(200);
        var rejected=put(body(0,0,"0")); assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(json.readTree(rejected.body()).get("code").asInt()).isEqualTo(50017); assertThat(audits()).isZero();
    }
    @Test void foreignProjectDeviceCannotBeReadOrModifiedInCurrentScope() throws Exception {
        UUID otherProject=UUID.randomUUID(),otherDevice=UUID.randomUUID();
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'foreign',?)",otherProject,tenant,"p"+otherProject.toString().replace("-",""));
        try {
            owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name) VALUES(?,?,?,'foreign','foreign')",otherDevice,tenant,otherProject);
            String foreignPath=path().replace(device.toString(),otherDevice.toString());
            assertThat(request("GET",foreignPath,null,token(),Map.of()).statusCode()).isEqualTo(404);
            assertThat(request("PUT",foreignPath,body(1,2,"0"),token(),Map.of()).statusCode()).isEqualTo(404);
            assertThat(owner.queryForObject("SELECT location_point IS NULL AND location_point_version=0 FROM dev_device WHERE id=?",Boolean.class,otherDevice)).isTrue();
        } finally { owner.update("DELETE FROM dev_device WHERE project_id=?",otherProject);owner.update("DELETE FROM sys_project WHERE id=?",otherProject); }
    }
    @Test void coordinatesCannotBypassConsoleAuthentication() throws Exception {
        assertThat(request("GET",path(),null,null,Map.of()).statusCode()).isEqualTo(401);
        assertThat(request("PUT",path(),body(1,2,"0"),"invalid-device-secret",Map.of()).statusCode()).isEqualTo(401);
        assertThat(audits()).isZero();
    }
    @Test void concurrentCasHasOneWinnerAndOneAudit() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
            var calls=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++) calls.add(pool.submit(()->{ready.countDown();assertThat(go.await(10,TimeUnit.SECONDS)).isTrue();return put(body(1,2,"0")).statusCode();}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(List.of(calls.get(0).get(20,TimeUnit.SECONDS),calls.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(audits()).isEqualTo(1);
    }
}
