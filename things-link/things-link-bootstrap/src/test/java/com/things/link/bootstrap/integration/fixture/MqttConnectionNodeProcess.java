package com.things.link.bootstrap.integration.fixture;

import com.things.link.ThingsLinkApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 双进程原连接验收入口；全部认证、回调和持久化均由生产Spring HTTP链处理。 */
public final class MqttConnectionNodeProcess {
    private MqttConnectionNodeProcess() { }
    public static void main(String[] args) throws Exception {
        System.setProperty("socksProxyHost", ""); System.setProperty("http.proxyHost", "");
        Path directory = Path.of(System.getProperty("mqtt.fixture.directory"));
        try (var context = new SpringApplicationBuilder(ThingsLinkApplication.class).profiles("test").run(args);
             var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            Files.writeString(directory.resolve("ready"), Integer.toString(((WebServerApplicationContext) context).getWebServer().getPort()));
            while (input.readLine() != null) break;
        }
    }
}
