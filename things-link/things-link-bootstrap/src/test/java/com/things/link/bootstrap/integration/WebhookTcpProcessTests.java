package com.things.link.bootstrap.integration;

import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import tools.jackson.databind.JsonNode;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.File;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** ADR0191：真实TLS与独立生产进程共享PG；强杀跳过finally，由存活实例自然过期。 */
@OwnedTestContainers({"KAFKA"})
class WebhookTcpProcessTests extends WebhookFixture {
    /** 隔离Broker避免子进程访问本机开发消息总线。 */
    private static final KafkaContainer KAFKA=new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    static { KAFKA.start(); }
    /** 父实例和子实例必须使用同一个隔离Broker。 */
    @DynamicPropertySource static void broker(DynamicPropertyRegistry r){r.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);}
    /** 仅使用仓库测试证书，客户端仍验证主机名。 */
    static final Path TLS=Path.of("..","things-link-ingestion","src","test","resources","tls").toAbsolutePath().normalize();
    /** 一次性测试设备密钥，不代表生产凭据。 */
    static final String SECRET="a2".repeat(32);
    /** 保留每个子进程，失败路径同样执行清理。 */
    final List<Process> processes=new ArrayList<>();
    /** 仅删除本轮生成的私有classpath。 */
    final List<Path> classpaths=new ArrayList<>();
    /** 保留本轮PID、周期与事件证据。 */
    Path run;
    /** 绑定心跳故意与实际下发值不同，防止把配置猜成协商事实。 */
    @BeforeEach void seedTcp()throws Exception{
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,heartbeat_seconds) VALUES(?,?,?,'TCP',300)",device,tenant,project);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'tcp-process')",tenant,project,device,SECRET);
        try(Admin admin=Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))){
            if(!admin.listTopics().names().get(10,TimeUnit.SECONDS).contains("tc.device.downlink"))admin.createTopics(List.of(new NewTopic("tc.device.downlink",3,(short)1))).all().get(15,TimeUnit.SECONDS);
        }
        run=Path.of("..","..","logs","verify","webhook-tcp-process-"+device).toAbsolutePath().normalize();Files.createDirectories(run);
    }
    /** 先停止进程再清理独占数据，保留原日志与强杀证据。 */
    @AfterEach void cleanup()throws Exception{
        for(Process process:processes){if(process.isAlive()){process.getOutputStream().close();if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}}}
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).execute(s->{owner.queryForList("SELECT id FROM sys_project WHERE id=? FOR UPDATE",project);for(String table:List.of("dev_connection","dev_credential","dev_access_binding","sys_outbox_event"))owner.update("DELETE FROM "+table+" WHERE project_id=?",project);return null;});
        for(Path cp:classpaths)try(var paths=Files.walk(cp)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    /** 按持锁边沿修订读来源，事务开始时间不代表提交顺序。 */
    List<PublicWebhookSource> sources(){return owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' ORDER BY id",String.class,project).stream().map(s->json.readValue(s,PublicWebhookSource.class)).sorted(Comparator.comparingLong(s->Long.parseLong(json.readTree(s.eventText()).path("payload").path("presenceRevision").asString()))).toList();}
    /** 验证实际下发周期、强杀后的自然三周期关闭与新进程重连。 */
    @Test void actualTlsHeartbeatSurvivesOwnerKillAndNewProcessReconnection()throws Exception{
        Node first=start("owner-a",10);
        UUID row;
        try(SSLSocket socket=authenticate(first,10000)){
            row=owner.queryForObject("SELECT id FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL",UUID.class,device);
            assertThat(owner.queryForObject("SELECT heartbeat_interval_millis FROM dev_connection WHERE id=?",Long.class,row)).isEqualTo(10000L);
            assertThat(owner.queryForObject("SELECT owner_instance FROM dev_connection WHERE id=?",String.class,row)).isEqualTo(first.runtime());
            assertThat(sources()).hasSize(1);
            socket.getOutputStream().write(DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.HEARTBEAT,new byte[0]));socket.getOutputStream().flush();
            assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame().type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
            Instant last=owner.queryForObject("SELECT last_seen_at FROM dev_connection WHERE id=?",java.sql.Timestamp.class,row).toInstant();
            first.process().destroyForcibly();assertThat(first.process().waitFor(5,TimeUnit.SECONDS)).isTrue();
            assertThat(owner.queryForObject("SELECT disconnected_at IS NULL FROM dev_connection WHERE id=?",Boolean.class,row)).isTrue();
            await().atMost(Duration.ofSeconds(45)).untilAsserted(()->{assertThat(sources()).hasSize(2);assertThat(owner.queryForObject("SELECT disconnect_reason FROM dev_connection WHERE id=?",String.class,row)).isEqualTo("heartbeat_timeout");});
            assertThat(sources().getLast().event().occurredAt()).isEqualTo(last.plusSeconds(30));
            Files.writeString(run.resolve("kill-evidence.json"),json.writeValueAsString(Map.of("parentPid",ProcessHandle.current().pid(),"ownerPid",first.process().pid(),"ownerDead",!first.process().isAlive(),"session",row,"actualIntervalMillis",10000,"lastSeenAt",last.toString(),"offlineAt",sources().getLast().event().occurredAt().toString(),"sourceIds",sources().stream().map(s->s.event().eventId()).toList())));
        }
        Node second=start("owner-b",20);
        try(SSLSocket socket=authenticate(second,20000)){
            assertThat(sources()).hasSize(3);
            assertThat(owner.queryForObject("SELECT heartbeat_interval_millis FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL",Long.class,device)).isEqualTo(20000L);
            assertThat(owner.queryForObject("SELECT generation FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL",Long.class,device)).isEqualTo(2);
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(sources()).hasSize(4));
        assertThat(sources().stream().map(s->s.event().eventType())).containsExactly("device.online","device.offline","device.online","device.offline");
    }
    /** 隔离生产classpath，只装载控制启动器，避免父测试配置污染子进程。 */
    Node start(String name,int seconds)throws Exception{
        Path dir=Files.createDirectory(run.resolve(name)),cp=Files.createDirectories(dir.resolve("classpath/com/things/link/bootstrap/device/access/fixture"));classpaths.add(dir.resolve("classpath"));
        try(var files=Files.list(Path.of("target/test-classes/com/things/link/bootstrap/device/access/fixture"))){for(Path file:files.filter(p->p.getFileName().toString().startsWith("TcpNodeProcess")).toList())Files.copy(file,cp.resolve(file.getFileName()));}
        Files.copy(Path.of("src/test/resources/application-test.yml"),dir.resolve("classpath/application-test.yml"));
        var entries=new ArrayList<String>();entries.add(dir.resolve("classpath").toString());for(String entry:System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(File.pathSeparator))if(!entry.replace('\\', '/').contains("/target/test-classes"))entries.add(entry);
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx384m","-Dtcp.fixture.directory="+dir,"-cp",String.join(File.pathSeparator,entries),"com.things.link.bootstrap.device.access.fixture.TcpNodeProcess",
            "--server.port=0","--spring.flyway.enabled=false","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+APP_ROLE,"--spring.datasource.password="+APP_ROLE_PASSWORD,
            "--spring.data.redis.host="+REDIS.getHost(),"--spring.data.redis.port="+REDIS.getMappedPort(6379),"--spring.kafka.bootstrap-servers="+KAFKA.getBootstrapServers(),"--spring.kafka.admin.auto-create=false","--spring.kafka.listener.auto-startup=false",
            "--things-link.access.tcp.enabled=true","--things-link.access.tcp.port=0","--things-link.access.tcp.heartbeat-seconds="+seconds,
            "--things-link.access.tls.certificate=file:"+TLS.resolve("device-access-test-cert.pem"),"--things-link.access.tls.private-key=file:"+TLS.resolve("device-access-test-key.pem"),
            "--things-link.outbox.publisher.enabled=false","--things-link.ingestion.emqx-api.base-url=http://127.0.0.1:9","--things-link.ingestion.emqx-api.api-key=test","--things-link.ingestion.emqx-api.api-secret=test-secret",
            "--things-link.integration.webhook.enabled=true","--things-link.integration.webhook.current-signing-key-id=a"));
        ProcessBuilder child = new ProcessBuilder(command);
        child.environment().put("THINGS_LINK_INTEGRATION_WEBHOOK_SIGNING_KEYS_JSON", "{\"a\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\"}");
        Process process = child.redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();processes.add(process);
        await().atMost(Duration.ofSeconds(60)).until(()->Files.exists(dir.resolve("ready.json"))||!process.isAlive());assertThat(process.isAlive()).as("see child log %s",dir.resolve("node.log")).isTrue();
        JsonNode ready=json.readTree(Files.readString(dir.resolve("ready.json")));assertThat(ready.path("pid").asLong()).isNotEqualTo(ProcessHandle.current().pid());return new Node(process,ready.path("port").asInt(),ready.path("runtime").asString());
    }
    /** 验证证书与AUTH_RESPONSE实际周期，失败时关闭套接字。 */
    SSLSocket authenticate(Node node,long interval)throws Exception{
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);try(var input=Files.newInputStream(TLS.resolve("device-access-test-cert.pem"))){trust.setCertificateEntry("test",CertificateFactory.getInstance("X.509").generateCertificate(input));}
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);var ssl=SSLContext.getInstance("TLS");ssl.init(null,managers.getTrustManagers(),null);
        SSLSocket socket=(SSLSocket)ssl.getSocketFactory().createSocket("localhost",node.port());var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);socket.setSoTimeout(10000);
        try{socket.startHandshake();socket.getOutputStream().write(DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.AUTH_REQUEST,json.writeValueAsBytes(Map.of("projectKey",owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?",String.class,project),"deviceKey",owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?",String.class,device),"secret",SECRET))));socket.getOutputStream().flush();var frame=new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame();assertThat(frame.type()).isEqualTo(DeviceAccessTcpFrameType.AUTH_RESPONSE);var body=json.readTree(frame.payload());assertThat(body.path("status").asString()).isEqualTo("OK");assertThat(body.path("heartbeatIntervalMillis").asLong()).isEqualTo(interval);return socket;}catch(Throwable failure){socket.close();throw failure;}
    }
    /** 记录进程和生产运行身份，证明owner不在父JVM内。 */
    record Node(Process process,int port,String runtime){}
}
