package com.things.link.bootstrap.enduser;

import com.things.link.shared.id.Uuid7;
import com.things.link.bootstrap.fixture.ThingsLinkNativeTls;
import com.things.link.testing.OwnedTestContainers;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import com.things.link.testing.tls.TestTlsMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 双端原生App、真实HTTPS及独占数据库旅程；默认不运行，不触碰开发库和厂商通道。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@EnabledIfSystemProperty(named = "thingslink.native.verify", matches = "true")
@OwnedTestContainers({"DATABASE", "REDIS"})
class ThingsLinkNativeJourneyTests {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("thingslink_native_journey").withUsername("thingslink").withPassword("thingslink");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final String PASSWORD = "Native-Journey-Password-93";
    private static final String NEW_PASSWORD = "Native-Journey-Changed-94";
    private static final Path ROOT = root();
    private static final String HOST = host();
    private static final ThingsLinkNativeTls TLS = tls();
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate application;
    @Autowired private PasswordEncoder passwords;
    static { DATABASE.start(); REDIS.start(); }

    @Test void nativePublicFunctionsOverVerifiedTls() throws Exception {
        assertThat(application.queryForObject("SELECT current_user",String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()",String.class)).isEqualTo("thingslink_native_journey");
        String targets=System.getProperty("thingslink.native.devices", "");
        assertThat(targets).as("必须显式指定目标设备ID").isNotBlank();
        var owner=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),"thingslink","thingslink"));
        owner.update("UPDATE sys_quota_policy SET rest_api_write_rate_per_second=1000000,rest_api_write_rate_per_minute=60000000");
        Path evidence=ROOT.resolve(!HOST.equals("127.0.0.1")?"logs/thingslink/TL-08-B":Boolean.getBoolean("thingslink.native.navigation")?"logs/thingslink/TL-07-A/native":"logs/thingslink/TL-08-A");Files.createDirectories(evidence);
        for(String device:targets.split(",")) {
            boolean android=device.startsWith("emulator-");
            String platform=android?"android":"ios";
            var fixture=seed(owner,platform);
            Path defines=evidence.resolve("defines-"+platform+".json");
            var values=new java.util.HashMap<String,String>(Map.of("THINGSX_TLS_BASE_URL","https://"+HOST+":"+port,
                    "THINGSX_TLS_CERT",Base64.getEncoder().encodeToString(Files.readAllBytes(TLS.rootCertificate())),
                    "THINGSX_PROJECT_KEY",fixture.key(),"THINGSX_USERNAME",fixture.username(),
                    "THINGSX_PASSWORD",PASSWORD,"THINGSX_NEW_PASSWORD",NEW_PASSWORD,
                    "THINGSX_STORAGE_KEY","thingsx.journey."+fixture.user()));
            values.put("THINGSX_TLS_HOST", HOST);
            boolean navigation=Boolean.getBoolean("thingslink.native.navigation");
            if(navigation) {
                var target=seedNavigation(owner,fixture);
                values.put("THINGSX_BACKEND_ID","56f43e3e-97d8-438d-82c1-b401c8201731");
                values.put("THINGSX_TENANT_ID",fixture.tenant().toString());
                values.put("THINGSX_USER_ID",fixture.user().toString());
                values.put("THINGSX_SOURCE_PROJECT",fixture.project().toString());
                values.put("THINGSX_SOURCE_ALARM",fixture.alarm().toString());
                values.put("THINGSX_TARGET_PROJECT",target.project().toString());
                values.put("THINGSX_TARGET_ALARM",target.alarm().toString());
            }
            Files.writeString(defines,JsonMapper.builder().build().writeValueAsString(values));
            Files.setPosixFilePermissions(defines,PosixFilePermissions.fromString("rw-------"));
            String adb=System.getProperty("thingslink.native.adb",Path.of(System.getProperty("user.home"),"Library/Android/sdk/platform-tools/adb").toString());
            boolean reversed=false;
            try {
                if(android){run(List.of(adb,"-s",device,"reverse","--no-rebind","tcp:"+port,"tcp:"+port),ROOT,evidence.resolve("adb-reverse.log"),30,Map.of());reversed=true;}
                String flutter=System.getProperty("thingslink.native.flutter","flutter");
                String target=navigation?"--target=integration_test/project_navigation_journey_test.dart":"--target=integration_test/platform_journey_test.dart";
                boolean profile=Boolean.getBoolean("thingslink.native.profile");
                // Xcode启动回退可能遗留构建目录；在Flutter读取构建设置前先统一生成配置，避免安装旧入口产物。
                if (!android && profile) run(List.of(flutter,"build","ios","--profile","--config-only","--no-pub",target,"--dart-define-from-file="+defines),
                        ROOT.resolve("things-link-flutter"),evidence.resolve("ios-config.log"),120,Map.of());
                var command=new ArrayList<>(List.of(flutter,"drive",
                        "--driver=test_driver/integration_test.dart",target,
                        "-d",device,"--no-pub","--dart-define-from-file="+defines));
                if (profile) command.add("--profile");
                run(command,ROOT.resolve("things-link-flutter"),evidence.resolve(platform+".log"),420,
                        Map.of("THINGSX_TEST_ARTIFACTS",evidence.resolve(platform).toString()));
                assertThat(owner.queryForObject("SELECT app_push_enabled FROM app_notification_preference WHERE tenant_id=? AND app_user_id=?",Boolean.class,fixture.tenant(),fixture.user())).isFalse();
                assertThat(passwords.matches(NEW_PASSWORD,owner.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,fixture.user()))).isTrue();
                assertThat(owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id=? AND revoked_at IS NULL",Integer.class,fixture.user())).isZero();
                System.out.println("THINGSX_NATIVE_"+platform.toUpperCase()+"_PASS: TLS, account, devices, alarms, preferences, password, logout");
            } finally {
                Files.deleteIfExists(defines);
                if(reversed)run(List.of(adb,"-s",device,"reverse","--remove","tcp:"+port),ROOT,evidence.resolve("adb-remove.log"),30,Map.of());
            }
        }
        assertThat(application.queryForObject("SELECT count(*) FROM app_user",Integer.class)).isZero();
    }

    private Fixture seed(JdbcTemplate db,String platform) {
        UUID tenant=Uuid7.generate(),project=Uuid7.generate(),user=Uuid7.generate(),account=Uuid7.generate(),type=Uuid7.generate();
        String key="native_"+project.toString().replace("-","");String username="native_"+user.toString().replace("-","");
        db.update("INSERT INTO sys_tenant(id,name) VALUES(?,'原生闭环测试')",tenant);
        db.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES(?,?,'unused-test-hash','夹具管理员',now())",account,account+"@example.test");
        db.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES(?,?,?)",Uuid7.generate(),tenant,account);
        db.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,?,?)",project,tenant,"原生公共功能-"+platform,key);
        db.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'OWNER')",Uuid7.generate(),project,account);
        db.update("INSERT INTO app_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,'原生测试账号',?)",user,tenant,username,passwords.encode(PASSWORD));
        db.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES(?,?,?,?,'OBSERVER')",Uuid7.generate(),tenant,project,user);
        db.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES(?,?,?,'public_devices','公共设备类型','STANDARD','DIRECT','PUBLISHED')",type,tenant,project);
        UUID sensor=Uuid7.generate();
        for(int i=0;i<3;i++) {
            UUID device=i==0?sensor:Uuid7.generate();
            db.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status,last_data_report_at) VALUES(?,?,?,?,?,?,?,CASE WHEN ?=0 THEN now() ELSE NULL END)",
                    device,tenant,project,type,"native_device_"+i,new String[]{"实验室温感","走廊照明","未授权设备"}[i],i==0?"ONLINE":"OFFLINE",i);
            if(i<2)db.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role) VALUES(?,?,?,?,?,'READ_ONLY')",Uuid7.generate(),tenant,project,user,device);
        }
        UUID alarm=Uuid7.generate(),rule=Uuid7.generate();
        db.update("INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES(?,?,?,'公共温度告警','TEMPERATURE',?,'temperature','GT',30,'LT',25,'MAJOR')",rule,tenant,project,sensor);
        db.update("INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,condition_state,first_condition_at,activated_at,last_received_at,last_value) VALUES(?,?,?,?,'DEVICE',?,'TEMPERATURE','MAJOR','ACTIVE',now(),now(),now(),31)",alarm,tenant,project,rule,sensor);
        db.update("INSERT INTO app_project_notification_contact(tenant_id,project_id,app_user_id,voice_number,sms_number,revision) VALUES(?,?,?,'+8613800000001','+8613800000002',1)",tenant,project,user);
        return new Fixture(tenant,user,key,username,project,alarm);
    }
    private record Fixture(UUID tenant,UUID user,String key,String username,UUID project,UUID alarm){}
    private record Target(UUID project,UUID alarm){}
    private Target seedNavigation(JdbcTemplate db,Fixture f) {
        UUID project=Uuid7.generate(),type=Uuid7.generate(),device=Uuid7.generate(),rule=Uuid7.generate(),alarm=Uuid7.generate();
        db.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES(?,?,'导航项目B',?)",project,f.tenant(),"target_"+project.toString().replace("-",""));
        db.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES(?,?,?,?,'OBSERVER')",Uuid7.generate(),f.tenant(),project,f.user());
        db.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES(?,?,?,'nav_sensor','公共温感','STANDARD','DIRECT','PUBLISHED')",type,f.tenant(),project);
        db.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES(?,?,?,?,'nav_sensor_b','项目B温感','ONLINE')",device,f.tenant(),project,type);
        db.update("INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role) VALUES(?,?,?,?,?,'READ_ONLY')",Uuid7.generate(),f.tenant(),project,f.user(),device);
        db.update("INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES(?,?,?,'导航告警B','NAVIGATION_B',?,'temperature','GT',30,'LT',25,'MAJOR')",rule,f.tenant(),project,device);
        db.update("INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,condition_state,first_condition_at,activated_at,last_received_at,last_value) VALUES(?,?,?,?,'DEVICE',?,'NAVIGATION_B','MAJOR','ACTIVE',now(),now(),now(),31)",alarm,f.tenant(),project,rule,device);
        return new Target(project,alarm);
    }
    private static void run(List<String> command,Path directory,Path log,int seconds,Map<String,String> environment)throws Exception {
        var builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().putAll(environment);
        Process process=builder.start();
        try{assertThat(process.waitFor(seconds,TimeUnit.SECONDS)).as("旅程有限截止，诊断%s",log).isTrue();assertThat(process.exitValue()).as("旅程结果，诊断%s",log).isZero();}
        finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}}
    }
    private static Path root(){try{return TestTlsMaterial.checkoutRoot(Path.of(System.getProperty("user.dir")));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String host() {
        String host = System.getProperty("thingslink.native.host", "127.0.0.1");
        ThingsLinkNativeTls.validateHost(host);
        try {
            if (java.net.NetworkInterface.getByInetAddress(java.net.InetAddress.getByName(host)) == null)
                throw new IllegalArgumentException("指定测试地址不属于本机接口");
        } catch (java.io.IOException e) { throw new IllegalStateException("无法验证本机测试接口", e); }
        return host;
    }
    private static ThingsLinkNativeTls tls(){try{return ThingsLinkNativeTls.create(ROOT.resolve(HOST.equals("127.0.0.1")?"logs/thingslink/TL-08-A":"logs/thingslink/TL-08-B"),HOST);}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);registry.add("spring.datasource.username",()->"thingslink_app");registry.add("spring.datasource.password",()->"thingslink");
        registry.add("spring.flyway.url",DATABASE::getJdbcUrl);registry.add("spring.flyway.user",DATABASE::getUsername);registry.add("spring.flyway.password",DATABASE::getPassword);registry.add("spring.flyway.placeholders.app_role_password",()->"thingslink");
        registry.add("spring.data.redis.host",REDIS::getHost);registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("things-link.app-navigation.backend-instance-id",()->"56f43e3e-97d8-438d-82c1-b401c8201731");
        registry.add("server.address",()->HOST);registry.add("server.ssl.enabled",()->true);
        registry.add("server.ssl.certificate",()->TLS.serverCertificate().toString());registry.add("server.ssl.certificate-private-key",()->TLS.serverKey().toString());
        registry.add("things-link.outbox.publisher.enabled",()->false);registry.add("things-link.notification.retry.enabled",()->false);registry.add("spring.kafka.listener.auto-startup",()->false);
    }
}
