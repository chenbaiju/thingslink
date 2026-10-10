package com.things.link.bootstrap.fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 原生旅程专属CA及短期指定本地地址服务器证书，不改写共享证书或安装系统信任。 */
public record ThingsLinkNativeTls(Path directory, Path rootCertificate, Path serverCertificate, Path serverKey) {
    public static ThingsLinkNativeTls create(Path evidence) throws Exception {
        return create(evidence, "127.0.0.1");
    }
    public static ThingsLinkNativeTls create(Path evidence, String host) throws Exception {
        validateHost(host);
        Files.createDirectories(evidence);
        Path directory=Files.createTempDirectory(evidence,"native-tls-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        String password=UUID.randomUUID().toString();
        Path ca=directory.resolve("ca.p12"),server=directory.resolve("server.p12"),root=directory.resolve("root.pem"),certificate=directory.resolve("server.pem"),key=directory.resolve("server-key.pem"),csr=directory.resolve("server.csr");
        keytool(directory,password,"-genkeypair","-alias","ca","-keyalg","EC","-groupname","secp256r1","-dname","CN=ThingsX Native Test CA","-ext","BC:critical=ca:true,pathlen:0","-ext","KU:critical=keyCertSign,cRLSign","-startdate","-1d","-validity","365","-storetype","PKCS12","-keystore",ca.toString(),"-noprompt");
        keytool(directory,password,"-exportcert","-alias","ca","-keystore",ca.toString(),"-rfc","-file",root.toString());
        keytool(directory,password,"-genkeypair","-alias","server","-keyalg","EC","-groupname","secp256r1","-dname","CN=localhost","-validity","30","-storetype","PKCS12","-keystore",server.toString(),"-noprompt");
        keytool(directory,password,"-certreq","-alias","server","-keystore",server.toString(),"-file",csr.toString());
        keytool(directory,password,"-gencert","-alias","ca","-keystore",ca.toString(),"-infile",csr.toString(),"-outfile",certificate.toString(),"-rfc","-startdate","-1d","-validity","30","-ext","BC:critical=ca:false","-ext","SAN=IP:"+host,"-ext","KU:critical=digitalSignature","-ext","EKU=serverAuth");
        var store=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(server)){store.load(input,password.toCharArray());}
        Files.writeString(key,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(store.getKey("server",password.toCharArray()).getEncoded())+"\n-----END PRIVATE KEY-----\n");
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));
        var factory=CertificateFactory.getInstance("X.509");
        X509Certificate rootCert,leaf;
        try(var input=Files.newInputStream(root)){rootCert=(X509Certificate)factory.generateCertificate(input);}
        try(var input=Files.newInputStream(certificate)){leaf=(X509Certificate)factory.generateCertificate(input);}
        rootCert.checkValidity();leaf.checkValidity();leaf.verify(rootCert.getPublicKey());
        if(rootCert.getBasicConstraints()<0||leaf.getBasicConstraints()!=-1||!Set.copyOf(leaf.getSubjectAlternativeNames()).equals(Set.of(List.of(7,host))))throw new IllegalStateException("测试证书用途或指定地址范围不符");
        return new ThingsLinkNativeTls(directory,root,certificate,key);
    }
    /** 仅支持显式loopback或RFC1918 IPv4；不解析DNS、不开放通配或公网地址。 */
    public static void validateHost(String host) {
        if (host == null || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))
            throw new IllegalArgumentException("须使用规范本地IPv4地址");
        int[] parts = java.util.Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();
        for (int part : parts) if (part > 255) throw new IllegalArgumentException("IPv4越界");
        if (!(host.equals("127.0.0.1") || parts[0] == 10 || (parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31)
                || (parts[0] == 192 && parts[1] == 168)))
            throw new IllegalArgumentException("仅允许loopback或RFC1918地址");
    }
    private static void keytool(Path directory,String password,String... args)throws Exception {
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","keytool").toString());command.addAll(List.of(args));command.addAll(List.of("-storepass:env","THINGSX_NATIVE_TLS_PASSWORD"));
        Path log=directory.resolve("keytool.log");
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("THINGSX_NATIVE_TLS_PASSWORD",password);
        Process process=builder.start();
        try{if(!process.waitFor(30,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("测试证书生成失败，诊断："+log);}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
}
