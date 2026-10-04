package com.things.link.bootstrap.integration;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Bounded MQTT3/5 wire fixture; no MQTT client library hides session negotiation. */
final class PublicMqttWire implements AutoCloseable {
    final Socket socket;final DataInputStream in;final DataOutputStream out;final int version;final Packet connack;final boolean extras;int lastPacketBytes;
    PublicMqttWire(String host,int port,int version,String client,String user,String password,boolean clean)throws IOException{
        this(host,port,version,client,user,password,clean,false);
    }
    PublicMqttWire(String host,int port,int version,String client,String user,String password,boolean clean,boolean extras)throws IOException{
        this(new Socket(host,port),version,client,user,password,clean,extras);
    }
    /** 允许调用方提供已校验证书与主机名的TLS套接字，其余MQTT行为完全一致。 */
    PublicMqttWire(Socket connected,int version,String client,String user,String password,boolean clean,boolean extras)throws IOException{
        this.extras=extras;this.version=version;socket=connected;socket.setSoTimeout(7000);in=new DataInputStream(socket.getInputStream());out=new DataOutputStream(socket.getOutputStream());
        var bytes=new ByteArrayOutputStream();var b=new DataOutputStream(bytes);utf(b,"MQTT");b.writeByte(version);b.writeByte(0xC0|(clean?2:0));b.writeShort(120);
        if(version==5){b.writeByte(extras?8:5);b.writeByte(0x11);b.writeInt(-1);if(extras){b.writeByte(0x22);b.writeShort(65535);}}
        utf(b,client);utf(b,user);utf(b,password);send(0x10,bytes.toByteArray());connack=read();
    }
    Packet subscribe(String topic,int qos)throws IOException{
        var bytes=new ByteArrayOutputStream();var b=new DataOutputStream(bytes);b.writeShort(1);if(version==5){if(extras){b.writeByte(5);b.writeByte(0x0B);writeVariable(b,268435455);}else b.writeByte(0);}utf(b,topic);b.writeByte(qos);send(0x82,bytes.toByteArray());return read();
    }
    byte[] message(String expectedTopic)throws IOException{
        var packet=read();if((packet.header()&0xF7)!=0x32)throw new IOException("expected QoS1 nonretained PUBLISH");
        var body=new DataInputStream(new ByteArrayInputStream(packet.body()));int length=body.readUnsignedShort();String topic=new String(body.readNBytes(length),StandardCharsets.UTF_8);if(!topic.equals(expectedTopic))throw new IOException("unexpected topic");
        int id=body.readUnsignedShort();if(version==5){int properties=variable(body);if(body.readNBytes(properties).length!=properties)throw new EOFException();}
        byte[] payload=body.readAllBytes();send(0x40,new byte[]{(byte)(id>>>8),(byte)id});return payload;
    }
    void forbiddenPublish(String topic)throws IOException{var bytes=new ByteArrayOutputStream();var b=new DataOutputStream(bytes);utf(b,topic);b.writeShort(9);if(version==5)b.writeByte(0);b.writeByte('x');send(0x32,bytes.toByteArray());}
    void disconnectWithLongExpiry()throws IOException{send(0xE0,version==5?new byte[]{0,5,0x11,-1,-1,-1,-1}:new byte[0]);socket.close();}
    void send(int header,byte[] body)throws IOException{out.writeByte(header);writeVariable(out,body.length);out.write(body);out.flush();}
    Packet read()throws IOException{int header=in.readUnsignedByte();int length=variable(in);int headerBytes=2;for(int n=length;n>=128;n/=128)headerBytes++;lastPacketBytes=headerBytes+length;if(length>65536)throw new IOException("wire fixture packet limit");byte[] body=in.readNBytes(length);if(body.length!=length)throw new EOFException();return new Packet(header,body);}
    static int variable(DataInputStream in)throws IOException{int value=0,factor=1;for(int i=0;i<4;i++){int b=in.readUnsignedByte();value+=(b&127)*factor;if((b&128)==0)return value;factor*=128;}throw new IOException("malformed variable integer");}
    static void writeVariable(DataOutputStream out,int value)throws IOException{do{int b=value%128;value/=128;out.writeByte(b|(value==0?0:128));}while(value!=0);}
    static void utf(DataOutputStream out,String text)throws IOException{byte[] value=text.getBytes(StandardCharsets.UTF_8);out.writeShort(value.length);out.write(value);}
    public void close()throws IOException{socket.close();}
    record Packet(int header,byte[] body){}
}
