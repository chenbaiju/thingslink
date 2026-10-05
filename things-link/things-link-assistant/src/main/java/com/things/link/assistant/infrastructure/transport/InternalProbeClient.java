package com.things.link.assistant.infrastructure.transport;

import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.ProbeLedger.Attempt;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** 单次使用原始 HTTP/1.1 连接，不使用 SDK、代理、重定向或隐式 POST 重试。 */
public final class InternalProbeClient implements ProbeTransport,AutoCloseable {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final URI origin;
    private final SSLContext context;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"agent-probe-deadline");t.setDaemon(true);return t;});
    /**
     * 装配固定内部 HTTPS 目标及双向 TLS 身份，不接收项目模型密钥。
     * @param origin 带明确端口的固定服务地址，不允许用户信息、查询或片段
     * @param identity 客户端 PKCS12 身份库路径
     * @param password 客户端身份库密码
     * @param trust PKCS12 信任库路径
     * @param trustPassword 信任库密码
     */
    public InternalProbeClient(URI origin,Path identity,char[] password,Path trust,char[] trustPassword){
        if(origin==null||!"https".equals(origin.getScheme())||origin.getHost()==null||origin.getRawUserInfo()!=null
            ||origin.getRawQuery()!=null||origin.getRawFragment()!=null||!(origin.getRawPath().isEmpty()||origin.getRawPath().equals("/"))
            ||origin.getPort()<1||origin.getPort()>65535) throw failure();
        this.origin=origin;context=InternalAgentHealthClient.loadContext(identity,password,trust,trustPassword);
    }
    /** 沿用接口契约；构造成功表示传输已装配，不证明供应商可用。{@inheritDoc} */
    @Override public boolean ready(){return true;}
    /**
     * 沿用接口契约；只发送固定探针元数据，凭据在本次请求头内单次交付。
     * 原始期限内仅建立一条连接；响应有界校验后关闭连接，失败返回固定异常。
     * {@inheritDoc}
     */
    @Override public ProbeResult execute(Attempt attempt,ProbeAuthorization authorization,byte[] credential){
        ProbeResult result=null;
        SSLSocket peer=null;ScheduledFuture<?> expiry=null;
        try {
            for(byte b:credential) if(b<33||b>126) throw failure();
            if(credential.length<1||credential.length>4096) throw failure();
            long remaining=Duration.between(Instant.now(),attempt.deadline()).toMillis();
            if(remaining<1||remaining>60000) throw failure();
            peer=(SSLSocket)context.getSocketFactory().createSocket();var socket=peer;
            expiry=timer.schedule(()->{try{socket.close();}catch(java.io.IOException ignored){}},remaining,TimeUnit.MILLISECONDS);
            var parameters=new SSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setProtocols(new String[]{"TLSv1.3","TLSv1.2"});peer.setSSLParameters(parameters);
            peer.connect(new InetSocketAddress(origin.getHost(),origin.getPort()),(int)Math.min(5000,remaining));
            peer.setSoTimeout((int)Math.min(5000,remaining));peer.startHandshake();
            peer.setSoTimeout((int)Math.max(1,Duration.between(Instant.now(),attempt.deadline()).toMillis()));
            byte[] body=JSON.writeValueAsBytes(Map.of("attemptId",attempt.id().toString(),"sampleIndex",attempt.sampleIndex(),
                "manifestSha256",authorization.manifestSha256(),"deadlineEpochMillis",attempt.deadline().toEpochMilli()));
            var output=peer.getOutputStream();
            output.write(("POST /internal/model-probe HTTP/1.1\r\nHost: "+origin.getHost()+":"+origin.getPort()+
                "\r\nContent-Type: application/json\r\nAccept: application/json\r\nConnection: close\r\nContent-Length: "+body.length+
                "\r\nX-Model-Credential: ").getBytes(StandardCharsets.US_ASCII));
            output.write(credential);output.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
            var input=peer.getInputStream();byte[] raw=input.readNBytes(12289);
            if(raw.length>12288) throw failure();
            int split=-1;for(int i=0;i<raw.length-3;i++) if(raw[i]==13&&raw[i+1]==10&&raw[i+2]==13&&raw[i+3]==10){split=i;break;}
            if(split<0||split>4096) throw failure();
            String headers=new String(raw,0,split,StandardCharsets.US_ASCII);var lines=headers.split("\r\n");
            if(!lines[0].startsWith("HTTP/1.1 200 ")) throw failure();
            var fields=new HashMap<String,String>();
            for(int i=1;i<lines.length;i++){
                int colon=lines[i].indexOf(':');if(colon<1)throw failure();
                if(fields.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim())!=null)throw failure();
            }
            if(fields.containsKey("transfer-encoding")||!"application/json".equals(fields.get("content-type"))
                ||Integer.parseInt(fields.get("content-length"))!=raw.length-split-4)throw failure();
            result=validate(JSON.readTree(Arrays.copyOfRange(raw,split+4,raw.length)),attempt,authorization);
        } catch(Exception ignored){result=null;}
        finally {if(peer!=null)try{peer.close();}catch(Exception ignored){}if(expiry!=null)expiry.cancel(false);}
        if(result==null)throw failure();return result;
    }
    /**
     * 校验封闭响应字段、样本绑定、用量范围与对账等式，不接受生成正文。
     * @param n 待验证的内部服务响应树
     * @param a 当前持久机会，限定样本序号
     * @param g 冻结授权，限定样本清单摘要
     * @return 已验证的分类及用量；计数不一致仍为成功调用，但不授予业务资格
     */
    private static ProbeResult validate(JsonNode n,Attempt a,ProbeAuthorization g){
        exact(n,Set.of("sampleIndex","manifestSha256","outcome","category","usage"));
        if(integer(n,"sampleIndex",1,3)!=a.sampleIndex()||!text(n,"manifestSha256").equals(g.manifestSha256()))throw failure();
        String outcome=text(n,"outcome"),category=text(n,"category");var u=n.get("usage");
        if(!outcome.equals("SUCCEEDED")){
            if(!u.isNull() || !(outcome.equals("FAILED")&&Set.of("SUPPLIER_AUTH","SUPPLIER_BALANCE","SUPPLIER_RATE_LIMIT","SUPPLIER_REJECTED","INVALID_USAGE").contains(category)
                ||outcome.equals("UNKNOWN")&&category.equals("TRANSPORT_UNKNOWN")))throw failure();
            return new ProbeResult(a.sampleIndex(),g.manifestSha256(),outcome,category,null);
        }
        exact(u,Set.of("localInputTokens","promptTokens","completionTokens","totalTokens","cacheHitTokens","cacheMissTokens","delta","finishReason",
            "implementationSha256","assetsSha256","requestSha256","messagesSha256","backendFingerprintSha256"));
        int local=integer(u,"localInputTokens",1,4096),prompt=integer(u,"promptTokens",1,4096),completion=integer(u,"completionTokens",0,128),
            total=integer(u,"totalTokens",1,4224),hit=integer(u,"cacheHitTokens",0,4096),miss=integer(u,"cacheMissTokens",0,4096),delta=integer(u,"delta",-4095,4095);
        if(total!=prompt+completion||hit+miss!=prompt||delta!=prompt-local||!category.equals(delta==0?"COUNT_MATCH":"COUNT_MISMATCH"))throw failure();
        String finish=text(u,"finishReason");if(!Set.of("stop","length").contains(finish))throw failure();
        var usage=new ProbeResult.Usage(local,prompt,completion,total,hit,miss,delta,finish,sha(u,"implementationSha256"),sha(u,"assetsSha256"),
            sha(u,"requestSha256"),sha(u,"messagesSha256"),sha(u,"backendFingerprintSha256"));
        return new ProbeResult(a.sampleIndex(),g.manifestSha256(),outcome,category,usage);
    }
    /** 要求对象字段集合与封闭契约完全一致，拒绝额外或缺失字段。 */
    private static void exact(JsonNode n,Set<String> keys){if(n==null||!n.isObject())throw failure();var actual=new HashSet<String>();n.fieldNames().forEachRemaining(actual::add);if(!keys.equals(actual))throw failure();}
    /** 读取有界整数，拒绝浮点、溢出及越界值，不执行类型转换。 */
    private static int integer(JsonNode n,String name,int min,int max){var v=n.get(name);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<min||v.intValue()>max)throw failure();return v.intValue();}
    /** 读取文本字段，不将其他类型强制转换为字符串。 */
    private static String text(JsonNode n,String name){var v=n.get(name);if(v==null||!v.isTextual())throw failure();return v.textValue();}
    /** 要求摘要为六十四位小写十六进制文本。 */
    private static String sha(JsonNode n,String name){String v=text(n,name);if(!v.matches("[a-f0-9]{64}"))throw failure();return v;}
    /** 统一失败异常，不附带响应正文、凭据、路径或底层异常。 */
    private static IllegalStateException failure(){return new IllegalStateException("INTERNAL_PROBE_FAILED");}
    /** 停止期限调度器，释放客户端生命周期资源。 */
    @Override public void close(){timer.shutdownNow();}
    @Override public String toString(){return "InternalProbeClient[REDACTED]";}
}
