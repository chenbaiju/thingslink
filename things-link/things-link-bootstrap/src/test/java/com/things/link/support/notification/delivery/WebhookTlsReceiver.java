package com.things.link.support.notification.delivery;

import com.sun.net.httpserver.*;
import okhttp3.OkHttpClient;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only socket routing preserves the production public-IP and TLS identity checks. */
public final class WebhookTlsReceiver implements AutoCloseable {
    private final Path temp;
    private final HttpsServer server;
    private final ExecutorService executor=Executors.newFixedThreadPool(4,Thread.ofPlatform().daemon().factory());
    private final SSLContext tls;
    private final X509TrustManager trust;
    public final List<Received> received=new CopyOnWriteArrayList<>();
    public volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);
    public volatile int status=200;
    public WebhookTlsReceiver()throws Exception{
        temp=Files.createTempDirectory("webhook-tls-");Path store=temp.resolve("test.p12");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","30","-dname","CN=receiver.example.com","-ext","SAN=dns:receiver.example.com","-storetype","PKCS12","-keystore",store.toString(),"-storepass","test-only-password","-keypass","test-only-password").redirectErrorStream(true).redirectOutput(temp.resolve("keytool.log").toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Test certificate creation failed");
        var keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,"test-only-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"test-only-password".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);trust=(X509TrustManager)tm.getTrustManagers()[0];tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        server=HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));server.setExecutor(executor);
        server.createContext("/",exchange->{try{var wire=new Received(exchange.getRequestHeaders(),exchange.getRequestBody().readAllBytes());received.add(wire);persist(wire);entered.countDown();release.await(8,TimeUnit.SECONDS);exchange.sendResponseHeaders(status,-1);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
    }
    private synchronized void persist(Received wire)throws IOException{
        Path receipt=temp.resolve(UUID.fromString(wire.headers().getFirst("X-ThingsLink-Delivery-Id"))+".receipt");
        if(Files.exists(receipt)){if(!Arrays.equals(Files.readAllBytes(receipt),wire.body()))throw new IOException("Delivery identity conflict");return;}
        try(var file=java.nio.channels.FileChannel.open(receipt,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var bytes=java.nio.ByteBuffer.wrap(wire.body());while(bytes.hasRemaining())file.write(bytes);file.force(true);
        }
    }
    public long acceptedEffects()throws IOException{try(var paths=Files.list(temp)){return paths.filter(p->p.toString().endsWith(".receipt")).count();}}
    public String target(){return "https://receiver.example.com:"+server.getAddress().getPort()+"/events";}
    public void reset(){try(var paths=Files.list(temp)){for(var path:paths.filter(p->p.toString().endsWith(".receipt")).toList())Files.delete(path);}catch(IOException e){throw new IllegalStateException(e);}release.countDown();received.clear();entered=new CountDownLatch(1);release=new CountDownLatch(0);status=200;}
    public PinnedWebhookTransport transport(){return new PinnedWebhookTransport(host->List.of(InetAddress.getByAddress(new byte[]{8,8,8,8})),()->new OkHttpClient.Builder().socketFactory(new RoutingSockets()).sslSocketFactory(tls.getSocketFactory(),trust));}
    public record Received(Headers headers,byte[] body){}
    @Override public void close()throws Exception{release.countDown();server.stop(0);executor.shutdownNow();try(var paths=Files.walk(temp)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private final class RoutingSockets extends SocketFactory {
        public Socket createSocket(){return new Socket(){@Override public void connect(SocketAddress endpoint,int timeout)throws IOException{if(!((InetSocketAddress)endpoint).getAddress().getHostAddress().equals("8.8.8.8"))throw new IOException("Unexpected pinned route");super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getAddress().getPort()),timeout);}};}
        public Socket createSocket(String h,int p)throws IOException{throw new IOException("Unexpected resolver path");}
        public Socket createSocket(String h,int p,InetAddress l,int q)throws IOException{throw new IOException("Unexpected resolver path");}
        public Socket createSocket(InetAddress h,int p)throws IOException{throw new IOException("Unexpected resolver path");}
        public Socket createSocket(InetAddress h,int p,InetAddress l,int q)throws IOException{throw new IOException("Unexpected resolver path");}
    }
}
