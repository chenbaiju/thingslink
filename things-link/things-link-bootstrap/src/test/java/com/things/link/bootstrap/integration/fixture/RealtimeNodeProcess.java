package com.things.link.bootstrap.integration.fixture;
import com.things.link.ThingsLinkApplication;
import com.things.link.integration.application.*;
import com.things.link.integration.domain.RealtimeDeliveryRepository;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.io.*;
import java.util.*;
/** Test-only process controller; all lease and send work uses production Spring beans. */
public final class RealtimeNodeProcess {
    public static void main(String[] args)throws Exception{
        System.setProperty("socksProxyHost","");System.setProperty("http.proxyHost","");
        Path dir=Path.of(System.getProperty("realtime.fixture.directory"));var held=new HashMap<UUID,RealtimeDeliveryRepository.Delivery>();
        try(var context=new SpringApplicationBuilder(ThingsLinkApplication.class,Pause.class).profiles("test").run(args);var input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))){
            Files.writeString(dir.resolve("ready"),Long.toString(ProcessHandle.current().pid()));
            for(String line;(line=input.readLine())!=null;){var parts=line.split(" ");if(parts[0].equals("quit"))break;String result="ok";
                try{var candidate=new RealtimeDeliveryRepository.Candidate(UUID.fromString(parts[2]),UUID.fromString(parts[3]),UUID.fromString(parts[4]));
                    switch(parts[0]){
                        case "claim"->{var value=context.getBean(RealtimeDeliveryState.class).claim(candidate,true);if(value.isPresent())held.put(candidate.id(),value.get());else result="empty";}
                        case "send"->context.getBean(RealtimeDeliveryDispatcher.class).send(candidate,held.get(candidate.id()));
                        case "dispatch"->context.getBean(RealtimeDeliveryDispatcher.class).dispatch(candidate);
                        default->throw new IllegalArgumentException("unknown test command");
                    }
                }catch(RuntimeException failure){result="FAIL:"+failure.getClass().getSimpleName();failure.printStackTrace();}
                Path tmp=dir.resolve("pending-"+parts[1]);Files.writeString(tmp,result);Files.move(tmp,dir.resolve("done-"+parts[1]),StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    public static class Pause {
        @org.springframework.context.annotation.Bean static org.springframework.beans.factory.config.BeanPostProcessor pause(){
            return new org.springframework.beans.factory.config.BeanPostProcessor(){
                public Object postProcessAfterInitialization(Object bean,String name){String type=org.springframework.aop.support.AopUtils.getTargetClass(bean).getSimpleName();
                    if(!Set.of("RealtimeDeliveryWorker","RealtimeTicketMaintenance","RealtimeRetentionMaintenance","RealtimeMqttWatchdog").contains(type))return bean;
                    org.aopalliance.intercept.MethodInterceptor hook=i->i.getMethod().getName().equals("tick")?null:i.proceed();
                    if(bean instanceof org.springframework.aop.framework.Advised advised){advised.addAdvice(hook);return bean;}
                    var proxy=new org.springframework.aop.framework.ProxyFactory(bean);proxy.setProxyTargetClass(true);proxy.addAdvice(hook);return proxy.getProxy();
                }
            };
        }
    }
}
