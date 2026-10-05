package com.things.link.integration.infrastructure;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
/** 模块自行管理的有界单次交换传输；不记录请求、响应或凭据。 */
final class RealtimeBrokerHttp implements AutoCloseable {
    private final URI base;private final String authorization;private final Set<Call> active=new HashSet<>();
    private OkHttpClient client;private boolean closed;
    RealtimeBrokerHttp(String baseUrl,String key,String secret){
        URI parsed=null;try{URI u=URI.create(baseUrl);if(Set.of("http","https").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null)parsed=u;}catch(RuntimeException invalid){}
        base=parsed;authorization=key==null||key.isBlank()||key.contains(":")||secret==null||secret.isBlank()?null:"Basic "+Base64.getEncoder().encodeToString((key+":"+secret).getBytes(StandardCharsets.UTF_8));
    }
    synchronized boolean configured(){return !closed&&base!=null&&authorization!=null;}
    Reply exchange(String method,String path,byte[] body,Duration budget,int limit){
        if(budget==null||budget.isNegative()||budget.isZero()||limit<0||limit>4*1024*1024||!path.startsWith("/api/v5/"))return new Reply(0,null);
        long nanos=budget.compareTo(Duration.ofSeconds(5))>0?TimeUnit.SECONDS.toNanos(5):budget.toNanos();final Call call;
        synchronized(this){
            if(!configured())return new Reply(0,null);
            if(client==null)client=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
            var request=new Request.Builder().url(base.resolve(path).toString()).header("Authorization",authorization)
                .method(method,body==null?null:RequestBody.create(body,MediaType.get("application/json"))).build();
            var exchanged=new AtomicBoolean();
            call=client.newBuilder().callTimeout(nanos,TimeUnit.NANOSECONDS).connectTimeout(Math.min(nanos,TimeUnit.SECONDS.toNanos(3)),TimeUnit.NANOSECONDS).readTimeout(nanos,TimeUnit.NANOSECONDS).writeTimeout(nanos,TimeUnit.NANOSECONDS)
                .addNetworkInterceptor(chain->{if(!exchanged.compareAndSet(false,true))throw new IOException("implicit exchange forbidden");var response=chain.proceed(chain.request());return response.code()==503?response.newBuilder().header("Retry-After","2147483647").build():response;}).build().newCall(request);
            active.add(call);
        }
        try(var response=call.execute()){
            byte[] bytes=response.body()==null?new byte[0]:response.body().byteStream().readNBytes(limit+1);
            return bytes.length>limit?new Reply(0,null):new Reply(response.code(),bytes);
        }catch(IOException failure){return new Reply(0,null);}finally{synchronized(this){active.remove(call);}}
    }
    synchronized void cancelActive(){active.forEach(Call::cancel);}
    public synchronized void close(){closed=true;cancelActive();if(client!=null){client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}}
    record Reply(int status,byte[] body){boolean accepted(){return status>=200&&status<300;}}
}
