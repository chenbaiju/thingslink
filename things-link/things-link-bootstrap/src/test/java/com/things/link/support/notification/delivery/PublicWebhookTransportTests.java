package com.things.link.support.notification.delivery;

import com.sun.net.httpserver.*;
import com.things.link.integration.application.WebhookDeliveryState.Outcome;
import com.things.link.integration.application.WebhookSigningKeys;
import com.things.link.integration.domain.WebhookSubscription;
import com.things.link.integration.infrastructure.webhook.PublicWebhookSender;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Real TLS and HTTP with test-only socket routing. Production DNS/IP/TLS checks remain enabled. */
class PublicWebhookTransportTests {
    @TempDir static Path temp;
    static SSLContext tls;static X509TrustManager trust;
    HttpsServer server;ExecutorService serverWorkers;CountDownLatch release=new CountDownLatch(1);
    List<InetSocketAddress> routes=new CopyOnWriteArrayList<>();List<Captured> received=new CopyOnWriteArrayList<>();
    volatile int responseStatus=200;volatile String retryAfter;volatile String location;volatile boolean delayed,limitedBody;
    List<PinnedWebhookTransport> transports=new ArrayList<>();
    @BeforeAll static void certificate()throws Exception{
        Path store=temp.resolve("test.p12");var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","30","-dname","CN=receiver.example.com","-ext","SAN=dns:receiver.example.com","-storetype","PKCS12","-keystore",store.toString(),"-storepass","test-only-password","-keypass","test-only-password").redirectErrorStream(true).redirectOutput(temp.resolve("keytool.log").toFile()).start();
        assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        var keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,"test-only-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"test-only-password".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);trust=(X509TrustManager)tm.getTrustManagers()[0];tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
    }
    @BeforeEach void start()throws Exception{
        server=HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        serverWorkers=Executors.newFixedThreadPool(8,Thread.ofPlatform().daemon().factory());server.setExecutor(serverWorkers);
        server.createContext("/",exchange->{try{var https=(HttpsExchange)exchange;byte[] body=exchange.getRequestBody().readAllBytes();received.add(new Captured(exchange.getRequestHeaders(),body,((ExtendedSSLSession)https.getSSLSession()).getRequestedServerNames()));
            if(delayed)release.await(10,TimeUnit.SECONDS);if(retryAfter!=null)exchange.getResponseHeaders().add("Retry-After",retryAfter);if(location!=null)exchange.getResponseHeaders().add("Location",location);
            if(limitedBody){exchange.sendResponseHeaders(responseStatus,1000000);exchange.getResponseBody().write(new byte[65536]);exchange.getResponseBody().flush();release.await(10,TimeUnit.SECONDS);}else exchange.sendResponseHeaders(responseStatus,-1);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
    }
    @AfterEach void stop(){release.countDown();transports.forEach(PinnedWebhookTransport::close);server.stop(0);serverWorkers.shutdownNow();}
    OkHttpClient.Builder routed(boolean trusted){var b=new OkHttpClient.Builder().socketFactory(new RoutingSockets());if(trusted)b.sslSocketFactory(tls.getSocketFactory(),trust);return b;}
    PinnedWebhookTransport transport(Dns dns,boolean trusted){var t=new PinnedWebhookTransport(dns,()->routed(trusted));transports.add(t);return t;}
    PinnedWebhookTransport transport(){return transport(host->List.of(InetAddress.getByAddress(new byte[]{8,8,8,8})),true);}
    URI target(){return URI.create("https://receiver.example.com:"+server.getAddress().getPort()+"/hook");}
    Map<String,String> headers(){return Map.of("X-ThingsLink-Delivery-Id",UUID.randomUUID().toString(),"X-ThingsLink-Timestamp","1","X-ThingsLink-Nonce",UUID.randomUUID().toString(),"X-ThingsLink-Signature","v1=test","X-ThingsLink-Key-Id","a");}
    WebhookSubscription subscription(String key){UUID id=UUID.randomUUID(),tenant=UUID.randomUUID(),project=UUID.randomUUID(),account=UUID.randomUUID();return new WebhookSubscription(id,tenant,project,0,account,Instant.now(),1,"ACTIVE",Instant.now(),"test",target().toString(),List.of("device.online"),List.of(),account,key,UUID.randomUUID());}
    WebhookSigningKeys keys(){return new WebhookSigningKeys(JsonMapper.builder().build(),true,"{\"a\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\"}","a");}
    @Test void exactSignedBodyHostSniAndNewNonceUseOnePinnedResolution()throws Exception{
        var lookups=new AtomicInteger();var t=transport(host->lookups.incrementAndGet()==1?List.of(InetAddress.getByAddress(new byte[]{8,8,8,8})):List.of(InetAddress.getLoopbackAddress()),true);var key=keys();var sub=subscription("a");var sender=new PublicWebhookSender(key,t);UUID id=UUID.randomUUID();String event="{\"valueJson\":\"9007199254740993\",\"text\":\"中文\"}";
        assertThat(sender.send(sub,id,event).outcome()).isEqualTo(Outcome.SUCCEEDED);assertThat(lookups).hasValue(1);assertThat(routes).hasSize(1);assertThat(routes.getFirst().getAddress().getHostAddress()).isEqualTo("8.8.8.8");assertThat(received).hasSize(1);
        var wire=received.getFirst();assertThat(new String(wire.body(),StandardCharsets.UTF_8)).isEqualTo("{\"deliveryId\":\""+id+"\",\"event\":"+event+"}");assertThat(wire.headers().getFirst("Host")).isEqualTo("receiver.example.com:"+server.getAddress().getPort());assertThat(wire.sni()).extracting(Object::toString).anyMatch(v->v.contains("receiver.example.com"));
        byte[] secret=key.derive("a",sub.tenant(),sub.project(),sub.id(),sub.signingGeneration());var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));String canonical=wire.headers().getFirst("X-ThingsLink-Timestamp")+"\n"+wire.headers().getFirst("X-ThingsLink-Nonce")+"\n"+id+"\n"+new String(wire.body(),StandardCharsets.UTF_8);assertThat(wire.headers().getFirst("X-ThingsLink-Signature")).isEqualTo("v1="+HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        // A new attempt re-resolves and rejects rebinding instead of reusing an old pooled connection.
        assertThat(sender.send(sub,id,event).reason()).isEqualTo("INVALID_TARGET");assertThat(received).hasSize(1);
        var secondSender=new PublicWebhookSender(key,transport());assertThat(secondSender.send(sub,id,event).outcome()).isEqualTo(Outcome.SUCCEEDED);assertThat(received.get(1).body()).isEqualTo(wire.body());assertThat(received.get(1).headers().getFirst("X-ThingsLink-Nonce")).isNotEqualTo(wire.headers().getFirst("X-ThingsLink-Nonce"));
    }
    @Test void responseClassificationDoesNotRedirectOrTransparentlyRetry(){var sender=new PublicWebhookSender(keys(),transport());var sub=subscription("a");int count=0;for(int status:List.of(200,204,301,307,400,408,429,500,503)){responseStatus=status;retryAfter="0";location="https://127.0.0.1/private";var result=sender.send(sub,UUID.randomUUID(),"{}");assertThat(result.httpStatus()).isEqualTo(status);assertThat(result.outcome()).isEqualTo(status<300?Outcome.SUCCEEDED:status==408||status==429||status>=500?Outcome.RETRYABLE:Outcome.PERMANENT);assertThat(received).hasSize(++count);}}
    @Test void remoteRetryAfterCannotCrashParsingOrExpandBudget(){responseStatus=503;retryAfter="999999999999999999999999999999999";var sender=new PublicWebhookSender(keys(),transport());assertThat(sender.send(subscription("a"),UUID.randomUUID(),"{}").outcome()).isEqualTo(Outcome.RETRYABLE);assertThat(received).hasSize(1);}
    @Test void oversizedReplyStopsAt64KiBEvenWhenPeerNeverFinishes(){limitedBody=true;var result=transport().post(target(),headers(),"{}".getBytes(StandardCharsets.UTF_8));assertThat(result.failure()).isEqualTo(PinnedWebhookTransport.Failure.NONE);assertThat(result.httpStatus()).isEqualTo(200);assertThat(result.elapsedMillis()).isLessThan(3000);assertThat(received).hasSize(1);}
    @Test void delayedHttpHeadersConsumeAtMostFiveSeconds(){delayed=true;var result=transport().post(target(),headers(),new byte[0]);assertThat(result.failure()).isEqualTo(PinnedWebhookTransport.Failure.TIMEOUT);assertThat(result.elapsedMillis()).isBetween(4500L,6500L);assertThat(received).hasSize(1);}
    @Test void dnsAndHttpShareOneDeadline(){delayed=true;var result=transport(host->{try{new CountDownLatch(1).await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new UnknownHostException();}return List.of(InetAddress.getByAddress(new byte[]{8,8,8,8}));},true).post(target(),headers(),new byte[0]);assertThat(result.failure()).isEqualTo(PinnedWebhookTransport.Failure.TIMEOUT);assertThat(result.elapsedMillis()).isBetween(4500L,6500L);}
    @Test void blockedUninterruptibleDnsHasFourWorkersAndNoWaitingQueue()throws Exception{var entered=new CountDownLatch(4);var unblock=new CountDownLatch(1);var t=transport(host->{entered.countDown();while(unblock.getCount()>0){try{unblock.await();}catch(InterruptedException ignored){/* Simulate a native resolver that ignores cancellation. */}}return List.of(InetAddress.getByAddress(new byte[]{8,8,8,8}));},true);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var pending=new ArrayList<Future<PinnedWebhookTransport.Result>>();for(int i=0;i<4;i++)pending.add(pool.submit(()->t.post(target(),headers(),new byte[0])));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();var refused=t.post(target(),headers(),new byte[0]);assertThat(refused.failure()).isEqualTo(PinnedWebhookTransport.Failure.UNAVAILABLE);assertThat(refused.elapsedMillis()).isLessThan(1000);for(var f:pending)assertThat(f.get(7,TimeUnit.SECONDS).failure()).isEqualTo(PinnedWebhookTransport.Failure.TIMEOUT);assertThat(routes).isEmpty();}finally{unblock.countDown();}}
    @Test void mixedPrivateDnsAnswerIsRejectedBeforeAnySocket(){var t=transport(host->List.of(InetAddress.getByAddress(new byte[]{8,8,8,8}),InetAddress.getLoopbackAddress()),true);assertThat(t.post(target(),headers(),new byte[0]).failure()).isEqualTo(PinnedWebhookTransport.Failure.INVALID_TARGET);assertThat(routes).isEmpty();assertThat(received).isEmpty();}
    @Test void invalidTargetsCredentialsAndPayloadNeverResolve(){var calls=new AtomicInteger();var t=transport(host->{calls.incrementAndGet();return List.of(InetAddress.getLoopbackAddress());},true);for(String url:List.of("http://receiver.example.com/","https://127.0.0.1/","https://u:p@receiver.example.com/","https://receiver.example.com/?token=x","https://receiver.example.com/#x"))assertThat(t.post(URI.create(url),headers(),new byte[0]).failure()).isEqualTo(PinnedWebhookTransport.Failure.INVALID_TARGET);assertThat(t.post(target(),headers(),new byte[262145]).failure()).isEqualTo(PinnedWebhookTransport.Failure.INVALID_TARGET);assertThat(t.post(target(),Map.of("Authorization","secret"),new byte[0]).failure()).isEqualTo(PinnedWebhookTransport.Failure.INVALID_TARGET);assertThat(calls).hasValue(0);assertThat(routes).isEmpty();}
    @Test void certificateTrustAndOriginalHostnameAreStillRequired(){var untrusted=transport(host->List.of(InetAddress.getByAddress(new byte[]{8,8,8,8})),false);assertThat(untrusted.post(target(),headers(),new byte[0]).failure()).isEqualTo(PinnedWebhookTransport.Failure.NETWORK_ERROR);assertThat(transport().post(URI.create(target().toString().replace("receiver.example.com","other.example.com")),headers(),new byte[0]).failure()).isEqualTo(PinnedWebhookTransport.Failure.NETWORK_ERROR);assertThat(received).isEmpty();}
    @Test void missingKeyVersionNeverSendsAndShutdownRejectsNewLookup(){var t=transport();var result=new PublicWebhookSender(keys(),t).send(subscription("missing"),UUID.randomUUID(),"{}");assertThat(result.reason()).isEqualTo("KEY_UNAVAILABLE");assertThat(result.outcome()).isEqualTo(Outcome.PERMANENT);assertThat(routes).isEmpty();t.close();assertThat(t.post(target(),headers(),new byte[0]).failure()).isEqualTo(PinnedWebhookTransport.Failure.UNAVAILABLE);}
    record Captured(com.sun.net.httpserver.Headers headers,byte[] body,List<SNIServerName> sni){}
    final class RoutingSockets extends SocketFactory {
        public Socket createSocket(){return new Socket(){@Override public void connect(SocketAddress endpoint,int timeout)throws IOException{routes.add((InetSocketAddress)endpoint);super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getAddress().getPort()),timeout);}};}
        public Socket createSocket(String host,int port)throws IOException{throw new IOException("Unexpected resolver path");}
        public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{throw new IOException("Unexpected resolver path");}
        public Socket createSocket(InetAddress host,int port)throws IOException{throw new IOException("Unexpected resolver path");}
        public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{throw new IOException("Unexpected resolver path");}
    }
}
