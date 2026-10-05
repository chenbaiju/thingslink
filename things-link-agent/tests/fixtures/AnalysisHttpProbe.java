import com.things.link.assistant.application.PreparedModelEvidence.*;
import com.things.link.assistant.application.OutboundPropertyPolicy.*;
import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.infrastructure.transport.SingleInternalAnalysisClient;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 合成离线生产者证明；无平台连接、真实设备或供应商凭据。 */
class AnalysisHttpProbe {
    public static void main(String[] args) {
        var time = Instant.parse("2026-10-04T00:00:00.123456789Z");
        var foreign = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var now = Instant.now();
        var call = new AnalysisCall(UUID.fromString("019c1234-5678-7890-8123-456789abcdef"),
                foreign, foreign, foreign, foreign, foreign, "private-key-hash", "private-request-hash",
                2, AnalysisCall.Status.DISPATCHED, now, now.plusSeconds(60), now.plusSeconds(86400), now, null);
        var template = Template.values()[0];
        {
            var input = new Input(template, "device-1", time, time,
                    new Device("e-device", DeviceStatus.INACTIVE, null, time),
                    new Alarm("e-alarm", AlarmState.ACTIVE, time), List.of(
                        new Reading("e-property-1", Semantic.TEMPERATURE, Unit.CELSIUS,
                                new NumberValue(new BigDecimal("0.12345678901234567890123456789")), time, time),
                        new Reading("e-property-2", Semantic.BINARY_STATE, Unit.UNITLESS,
                                new BooleanValue(false), time, time)));
            char[] password="synthetic-test-password".toCharArray();
            byte[] key="synthetic-probe-credential".getBytes(StandardCharsets.US_ASCII);
            boolean accepted=false;
            try {
                var client=new SingleInternalAnalysisClient(URI.create(args[0]),Path.of(args[1]),password,
                        Path.of(args[2]),password);
                var result=client.execute(call,input,args[3],args[4],key);
                if (!result.qualification().equals("OFFLINE_UNQUALIFIED")) throw new IllegalStateException();
                for (byte value:key) if(value!=0) throw new AssertionError();
                try { client.execute(call,input,args[3],args[4],key); throw new AssertionError(); }
                catch(IllegalStateException expected) { }
                accepted=true;
            } catch(IllegalStateException expected) { }
            finally { Arrays.fill(password,'\0'); Arrays.fill(key,(byte)0); }
            System.out.println(accepted ? "ANALYSIS_OK" : "ANALYSIS_REJECTED");
            if(!accepted) System.exit(2);
        }
    }
}
