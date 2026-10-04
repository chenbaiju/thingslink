package com.things.link.bootstrap.integration;
import com.things.link.integration.application.*;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true"})
class PublicRealtimeWsBackpressureTests extends RealtimeEventFixture {
    static class Wire implements AutoCloseable {
        final Socket socket=new Socket();final InputStream in;final OutputStream out;
        Wire(int port,String credential)throws Exception{socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setSoTimeout(15000);in=socket.getInputStream();out=socket.getOutputStream();
            out.write(("GET /api/open/v1/realtime/ws HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: tc-realtime-v1, "+credential+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();var header=new ByteArrayOutputStream();while(header.size()<8192){int b=in.read();if(b<0)throw new EOFException();header.write(b);if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}assertThat(header.toString(StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 101").doesNotContain(credential);assertThat(new String(frame(1),StandardCharsets.UTF_8)).contains("READY");}
        byte[] frame(int opcode)throws Exception{int first=in.read(),second=in.read();assertThat(first&15).isEqualTo(opcode);assertThat(second&128).isZero();long length=second&127;if(length==126)length=((long)in.read()<<8)|in.read();else if(length==127)throw new IOException("oversized frame");assertThat(length).isLessThanOrEqualTo(32768);byte[] body=in.readNBytes((int)length);assertThat(body).hasSize((int)length);return body;}
        void pings(int count)throws Exception{var batch=new ByteArrayOutputStream();byte[] body="{\"type\":\"PING\"}".getBytes(StandardCharsets.UTF_8),mask={1,2,3,4};for(int n=0;n<count;n++){batch.write(0x81);batch.write(128|body.length);batch.write(mask);for(int i=0;i<body.length;i++)batch.write(body[i]^mask[i%4]);}out.write(batch.toByteArray());out.flush();}
        int closeCode()throws Exception{byte[] body=frame(8);return ((body[0]&255)<<8)|(body[1]&255);}
        public void close()throws Exception{socket.close();}
    }
    RealtimeTicketService.Issued issue()throws Exception{return tickets.issue(new RealtimeIdentity(RealtimeIdentity.Kind.CONSOLE,tenant,project,0,account,account,null,Instant.now().plusSeconds(120)),new RealtimeTicketParser().parse(json.writeValueAsBytes(Map.of("protocol","WS","eventTypes",List.of("device.property.report"),"devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value")))))),"127.0.0.1");}
    void closed(UUID ticket){org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(25)).until(()->"CLOSED".equals(owner.queryForObject("SELECT status FROM integ_realtime_ticket WHERE id=?",String.class,ticket)));}
    @Test void atomic257EventAdmissionCloses1013WithoutPartialStaleOutput()throws Exception{
        var ticket=issue();try(var wire=new Wire(port,ticket.credential())){
            tx.execute(s->{for(int n=0;n<257;n++)admission.accept(update(Uuid7.generate(),Integer.toString(n)));return null;});
            assertThat(wire.closeCode()).isEqualTo(1013);closed(ticket.ticketId());assertThat(count("integ_realtime_delivery")).isEqualTo(256);
            assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_delivery WHERE ticket_id=? AND status='DELIVERED'",Integer.class,ticket.ticketId())).isZero();
        }
    }
    @Test void controlOverflowIsBoundedWhileAuthorityRowIsBusy()throws Exception{
        var ticket=issue();try(var wire=new Wire(port,ticket.credential());var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())){
            connection.setAutoCommit(false);try(var query=connection.prepareStatement("SELECT id FROM integ_realtime_ticket WHERE id=? FOR UPDATE")){query.setObject(1,ticket.ticketId());query.executeQuery().close();}
            try{wire.pings(64);assertThat(wire.closeCode()).isEqualTo(1013);}finally{connection.rollback();}closed(ticket.ticketId());
        }
    }
    @Test void nonReadingPeerCannotKeepWriterOrDeliveryAliveIndefinitely()throws Exception{
        var ticket=issue();try(var wire=new Wire(port,ticket.credential())){
            String value="1".repeat(30000);tx.execute(s->{for(int n=0;n<256;n++)admission.accept(update(Uuid7.generate(),value));return null;});
            closed(ticket.ticketId());assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_delivery WHERE ticket_id=? AND status='DELIVERED'",Integer.class,ticket.ticketId())).isBetween(1,255);
            var fresh=issue();try(var responsive=new Wire(port,fresh.credential())){responsive.pings(1);assertThat(new String(responsive.frame(1),StandardCharsets.UTF_8)).contains("PONG");}
            closed(fresh.ticketId());
        }
    }
}
