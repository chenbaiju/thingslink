package com.things.link.bootstrap.integration.fixture;
import com.things.link.ThingsLinkApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
/** Independent production WS server; fixture publishes only ephemeral port and accepts shutdown. */
public final class RealtimeWsNodeProcess {
    public static void main(String[] args)throws Exception{
        System.setProperty("socksProxyHost","");System.setProperty("http.proxyHost","");
        try(var context=new SpringApplicationBuilder(ThingsLinkApplication.class).profiles("test").run(args);var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in))){
            Files.writeString(Path.of(System.getProperty("realtime.fixture.directory"),"ready"),context.getEnvironment().getRequiredProperty("local.server.port"));
            while(true){String line=input.readLine();if(line==null||line.equals("quit"))break;}
        }
    }
}
