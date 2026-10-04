package com.things.link.support.notification.delivery;

import com.things.link.shared.net.SafeOutboundHost;
import jakarta.annotation.PreDestroy;
import okhttp3.*;
import okio.BufferedSink;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Public Webhook transport. No ambient proxy, DNS re-resolution, credentials or hidden second POST. */
@Component
public class PinnedWebhookTransport implements AutoCloseable {
    public static final int MAX_BODY_BYTES=262144;
    public static final int MAX_RESPONSE_BYTES=65536;
    private static final long BUDGET_NANOS=TimeUnit.SECONDS.toNanos(5);
    private static final Set<String> HEADERS=Set.of("X-ThingsLink-Delivery-Id","X-ThingsLink-Timestamp",
        "X-ThingsLink-Nonce","X-ThingsLink-Signature","X-ThingsLink-Key-Id");
    private final Dns resolver;
    private final Supplier<OkHttpClient.Builder> clients;
    private final ThreadPoolExecutor dnsWorkers=new ThreadPoolExecutor(0,4,60,TimeUnit.SECONDS,
        new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("tc-webhook-dns-",0).factory(),new ThreadPoolExecutor.AbortPolicy());

    public PinnedWebhookTransport(){this(Dns.SYSTEM,OkHttpClient.Builder::new);}
    /** Package-local fixture injection; production exposes no private-address or TLS bypass option. */
    PinnedWebhookTransport(Dns resolver,Supplier<OkHttpClient.Builder> clients){this.resolver=resolver;this.clients=clients;}

    public Result post(URI target,Map<String,String> headers,byte[] body){
        long start=System.nanoTime();Future<List<InetAddress>> lookup=null;Call call=null;Response response=null;ConnectionPool pool=null;
        try {
            if(target==null||!"https".equalsIgnoreCase(target.getScheme())||target.getHost()==null||target.getUserInfo()!=null
                ||target.getRawQuery()!=null||target.getRawFragment()!=null||target.toASCIIString().length()>2048
                ||target.getPort()==0||target.getPort()>65535||body==null||body.length>MAX_BODY_BYTES
                ||headers==null||!HEADERS.equals(headers.keySet()))return result(start,null,Failure.INVALID_TARGET);
            SafeOutboundHost.requireNoForbiddenLiteral(target);
            String host=target.getHost();if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
            String expectedHost=host;
            lookup=dnsWorkers.submit(()->resolver.lookup(expectedHost));
            var resolved=lookup.get(remaining(start),TimeUnit.NANOSECONDS);
            if(resolved==null||resolved.isEmpty()||resolved.size()>16||resolved.stream().anyMatch(a->a==null||SafeOutboundHost.isForbidden(a)))
                return result(start,null,Failure.INVALID_TARGET);
            InetAddress pinned=resolved.getFirst();
            pool=new ConnectionPool(0,1,TimeUnit.SECONDS);
            var client=clients.get().proxy(Proxy.NO_PROXY).proxyAuthenticator(okhttp3.Authenticator.NONE).authenticator(okhttp3.Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES).cache(null).connectionPool(pool).protocols(List.of(Protocol.HTTP_1_1))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .addNetworkInterceptor(chain->chain.proceed(chain.request()).newBuilder().removeHeader("Retry-After").build())
                .dns(name->{if(!name.equalsIgnoreCase(expectedHost))throw new UnknownHostException("Unexpected webhook host");return List.of(pinned);})
                .callTimeout(remaining(start),TimeUnit.NANOSECONDS).connectTimeout(5,TimeUnit.SECONDS)
                .readTimeout(5,TimeUnit.SECONDS).writeTimeout(5,TimeUnit.SECONDS).build();
            RequestBody requestBody=new RequestBody(){
                public MediaType contentType(){return MediaType.get("application/json; charset=utf-8");}
                public long contentLength(){return body.length;}
                public boolean isOneShot(){return true;}
                public void writeTo(BufferedSink sink)throws IOException{sink.write(body);}
            };
            var request=new Request.Builder().url(target.toString()).header("Accept-Encoding","identity").header("Connection","close").post(requestBody);
            headers.forEach(request::header);
            call=client.newCall(request.build());call.timeout().timeout(remaining(start),TimeUnit.NANOSECONDS);response=call.execute();int status=response.code();
            var stream=response.body().byteStream();byte[] buffer=new byte[8192];int left=MAX_RESPONSE_BYTES;
            while(left>0){int read=stream.read(buffer,0,Math.min(left,buffer.length));if(read<0)break;if(read>0)left-=read;}
            return result(start,status,Failure.NONE);
        } catch(IllegalArgumentException failure){return result(start,null,Failure.INVALID_TARGET);
        } catch(RejectedExecutionException failure){return result(start,null,Failure.UNAVAILABLE);
        } catch(TimeoutException|java.io.InterruptedIOException failure){return result(start,null,Failure.TIMEOUT);
        } catch(InterruptedException failure){Thread.currentThread().interrupt();return result(start,null,Failure.UNAVAILABLE);
        } catch(ExecutionException|IOException failure){return result(start,null,Failure.NETWORK_ERROR);
        } finally {
            if(lookup!=null)lookup.cancel(true);
            // Cancel before close: closing an oversized HTTP/1 body must not silently drain it for reuse.
            if(call!=null)call.cancel();if(response!=null)response.close();if(pool!=null)pool.evictAll();
        }
    }
    private static long remaining(long start)throws TimeoutException{long value=BUDGET_NANOS-(System.nanoTime()-start);if(value<=0)throw new TimeoutException();return value;}
    private static Result result(long start,Integer status,Failure failure){return new Result(status,failure,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
    @Override @PreDestroy public void close(){dnsWorkers.shutdownNow();}
    public enum Failure {NONE,INVALID_TARGET,NETWORK_ERROR,TIMEOUT,UNAVAILABLE}
    public record Result(Integer httpStatus,Failure failure,long elapsedMillis) {}
}
