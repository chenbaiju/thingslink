import com.things.link.assistant.infrastructure.transport.InternalAgentHealthClient;
import java.net.URI;
import java.nio.file.Path;

/** Java source-file launcher for synthetic PKI only; no production passwords. */
class InternalHealthProbe {
    public static void main(String[] args) {
        char[] password = "synthetic-test-password".toCharArray();
        try (var client = new InternalAgentHealthClient(URI.create(args[0]), Path.of(args[1]), password, Path.of(args[2]), password)) {
            client.verifyHealth();
            System.out.println("HEALTH_OK");
        } catch (InternalAgentHealthClient.TransportException rejected) {
            System.out.println("HEALTH_REJECTED");
            System.exit(2);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }
}
