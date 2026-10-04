package com.things.link.bootstrap.ota.fixture;

import com.things.link.ThingsLinkApplication;
import com.things.link.ota.application.OtaBusinessRetryService;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.ObjectMapper;

/** 独立JVM测试入口；所有领取、范围验证、CAS及审计均使用生产事务代理。 */
public final class OtaRetryNodeProcess {
    private OtaRetryNodeProcess() { }
    public static void main(String[] args) throws Exception {
        System.setProperty("socksProxyHost", "");
        System.setProperty("http.proxyHost", "");
        Path directory = Path.of(System.getProperty("ota.retry.fixture.directory"));
        try (var context = new SpringApplicationBuilder(ThingsLinkApplication.class).profiles("test").run(args);
             var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            var service = context.getBean(OtaBusinessRetryService.class);
            var json = context.getBean(ObjectMapper.class);
            write(directory.resolve("ready"), Long.toString(ProcessHandle.current().pid()));
            for (String line; (line = input.readLine()) != null;) {
                if ("quit".equals(line)) break;
                var parts = line.split(" ", 3);
                String response;
                try {
                    response = switch (parts[0]) {
                        case "claim" -> json.writeValueAsString(service.claimDue().orElse(null));
                        case "classify" -> Boolean.toString(service.classify(json.readValue(
                                Files.readString(Path.of(parts[2])), OtaCampaignRuntimeRepository.RetryDue.class),
                                "RETRY_BACKOFF_ELAPSED"));
                        default -> throw new IllegalArgumentException("unknown test command");
                    };
                } catch (RuntimeException failure) { response = "FAIL:" + failure.getClass().getSimpleName(); }
                write(directory.resolve("done-" + parts[1]), response);
            }
        }
    }
    private static void write(Path target, String value) throws Exception {
        Path pending = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(pending, value);
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
