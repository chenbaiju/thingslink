import com.things.link.assistant.application.*;
import com.things.link.assistant.domain.ProbeLedger.*;
import com.things.link.assistant.infrastructure.transport.InternalProbeClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Actual Java sender -> Python mTLS, only a hardcoded synthetic credential. */
class ProbeHttpProbe {
    public static void main(String[] args) {
        char[] password="synthetic-test-password".toCharArray();
        byte[] key="synthetic-probe-credential".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        UUID tenant=UUID.randomUUID(),project=UUID.randomUUID();var now=Instant.now();
        var attempt=new Attempt(UUID.fromString(args[4]),UUID.randomUUID(),tenant,project,UUID.randomUUID(),1,Status.CLAIMED,now,now.plusSeconds(60),null);
        var grant=new ProbeAuthorization("interop","synthetic",project,2,args[3]);
        try(var client=new InternalProbeClient(URI.create(args[0]),Path.of(args[1]),password,Path.of(args[2]),password)){
            var result=client.execute(attempt,grant,key);
            if(!result.category().equals("COUNT_MISMATCH")||result.usage().delta()!=1)throw new IllegalStateException();
            System.out.println("PROBE_OK");
        }catch(IllegalStateException rejected){System.out.println("PROBE_REJECTED");System.exit(2);}
        finally{Arrays.fill(password,'\0');Arrays.fill(key,(byte)0);}
    }
}
